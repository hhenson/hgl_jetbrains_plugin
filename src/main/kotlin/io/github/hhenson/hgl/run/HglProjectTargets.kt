package io.github.hhenson.hgl.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.hhenson.hgl.HglFileType
import io.github.hhenson.hgl.project.HglBuildFile
import io.github.hhenson.hgl.reference.HglModuleIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.nio.file.Path

internal class HglTargetsStartup : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<HglProjectTargets>().start()
}

/** Publish HGL source declarations as native Run/Debug configurations. */
@Service(Service.Level.PROJECT)
internal class HglProjectTargets(private val project: Project, private val coroutineScope: CoroutineScope) : Disposable {
    private data class Catalog(val applications: Set<String>, val modules: Map<String, List<String>>)
    private var started = false
    private var rescan = true
    private val edited = mutableSetOf<Path>()
    @Volatile private var watchedRoots = emptyList<Path>()
    private val changes = MutableStateFlow(0L)
    private val headers = mutableMapOf<Path, HglModuleIndex.Companion.Header?>()
    @Volatile private var catalogVersion = -1L
    @Volatile private var catalog = Catalog(emptySet(), emptyMap())

    override fun dispose() {}

    fun start() {
        if (started) return
        val base = project.basePath?.let(Path::of) ?: return
        started = true
        watchedRoots = listOf(base)
        val config = base.resolve(HglBuildFile.NAME)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                var changed = false
                synchronized(edited) {
                    for (event in events) {
                        if (event.fileSystem.protocol != "file") continue
                        val paths = when (event) {
                            is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
                            is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME)
                                listOf(event.oldPath, event.newPath) else continue
                            else -> listOf(event.path)
                        }
                        val directory = event.file?.isDirectory == true || event is VFileCreateEvent && event.isDirectory
                        for (value in paths) {
                            val path = Path.of(value)
                            if (path == config) rescan = true
                            else if (directory && watchedRoots.any { path.startsWith(it) || it.startsWith(path) }) rescan = true
                            else if (value.endsWith(".hgl") && watchedRoots.any(path::startsWith)) {
                                if (event is VFileContentChangeEvent) edited.add(path) else rescan = true
                            } else continue
                            changed = true
                        }
                    }
                }
                if (changed) changes.update { it + 1 }
            }
        })
        coroutineScope.launch {
            var installed = false
            changes.collect { revision ->
                delay(150)
                val (full, paths) = synchronized(edited) {
                    (rescan to edited.toList()).also { rescan = false; edited.clear() }
                }
                val next = if (full) {
                    val roots = try { readAction { project.buildSettings().sourceRoots } }
                        catch (_: IllegalArgumentException) { return@collect }
                    watchedRoots = roots.map { base.resolve(it).normalize() }.ifEmpty { listOf(base) }
                    val files = sourceFiles(roots, base)
                    withContext(Dispatchers.IO) {
                        headers.keys.retainAll(files)
                        files.forEach(::readHeader)
                        snapshot()
                    }
                } else withContext(Dispatchers.IO) {
                    // A body edit reads only that file and leaves RunManager untouched.
                    var changed = false
                    for (path in paths) if (path in headers && readHeader(path)) changed = true
                    if (changed) snapshot() else null
                }
                if (next != null && (!installed || next != catalog)) {
                    withContext(Dispatchers.EDT) { install(next) }
                    installed = true
                }
                catalogVersion = revision
            }
        }
    }

    private suspend fun sourceFiles(roots: List<String>, base: Path): Set<Path> {
        if (roots.isEmpty()) return smartReadAction(project) {
            FileTypeIndex.getFiles(HglFileType, GlobalSearchScope.projectScope(project))
                .filter { it.isInLocalFileSystem }.map { it.toNioPath() }.filter { it.startsWith(base) }.toSet()
        }
        return withContext(Dispatchers.IO) { filesUnder(roots.map { base.resolve(it).normalize() }) }
    }

    private fun filesUnder(roots: Collection<Path>): Set<Path> = buildSet {
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && file.fileName.toString().endsWith(".hgl")) add(file)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            override fun postVisitDirectory(dir: Path, exc: IOException?) = FileVisitResult.CONTINUE
        }
        for (root in roots.distinct()) {
            Files.walkFileTree(root, setOf(FileVisitOption.FOLLOW_LINKS), Int.MAX_VALUE, visitor)
        }
    }

    private fun header(file: Path) = try { HglModuleIndex.headerOf(Files.readString(file)) } catch (_: IOException) { null }

    private fun readHeader(file: Path): Boolean {
        val next = header(file)
        return headers.put(file, next) != next
    }

    private fun snapshot(): Catalog {
        val applications = sortedSetOf<String>()
        val modules = sortedMapOf<String, MutableList<String>>()
        for ((file, header) in headers.toSortedMap()) {
            if (header == null) continue
            if (header.application) applications.add(header.name)
            else modules.getOrPut(header.name) { mutableListOf() }.add(file.toString())
        }
        return Catalog(applications, modules)
    }

    fun moduleSources(name: String, roots: Collection<Path>): List<String> {
        // Run can be clicked immediately after Save or before project startup completes.
        val paths = if (catalogVersion == changes.value) catalog.modules[name].orEmpty() else {
            val expected = HglModuleIndex.Companion.Header(name, application = false)
            filesUnder(roots).filter { header(it) == expected }.sorted().map(Path::toString)
        }
        if (paths.isEmpty()) throw RuntimeConfigurationError("Module $name is absent from the HGL source roots.")
        return paths
    }

    private fun install(next: Catalog) {
        catalog = next
        val manager = RunManager.getInstance(project)
        val desired = buildMap<String, Pair<String, Boolean>> {
            for (name in next.applications) put("application:$name", name to false)
            if (next.modules.isNotEmpty()) for (name in listOf("") + next.modules.keys) {
                put("test:$name", name to false)
                put("integration:$name", name to true)
            }
        }
        val retained = mutableSetOf<String>()
        for (settings in manager.allSettings) {
            val key = discoveredKey(settings) ?: continue
            if (key in desired) retained.add(key) else manager.removeConfiguration(settings)
        }
        for ((key, target) in desired) {
            if (key in retained) continue
            val (name, integration) = target
            val application = key.startsWith("application:")
            val type = if (application) ConfigurationTypeUtil.findConfigurationType(HglApplicationType::class.java)
                else ConfigurationTypeUtil.findConfigurationType(HglScopedTestType::class.java)
            val title = when {
                application -> name
                name.isEmpty() -> if (integration) "All HGL Integration Tests" else "All HGL Tests"
                integration -> "$name — Integration Tests"
                else -> "$name — Tests"
            }
            val settings = manager.createConfiguration(manager.suggestUniqueName(title, type), type)
            settings.storeInLocalWorkspace()
            when (val config = settings.configuration) {
                is HglRunConfiguration -> {
                    config.discoveredId = "$key|${settings.uniqueID}"
                    config.selector = name
                    config.sourcePath = "."
                }
                is HglScopedTestConfiguration -> {
                    config.discoveredId = "$key|${settings.uniqueID}"
                    config.moduleName = name
                    config.allModules = name.isEmpty()
                    config.testPath = "."
                    config.integrationOnly = integration
                }
            }
            settings.folderName = if (application) "HGL Applications" else "HGL Tests"
            manager.addConfiguration(settings)
        }
    }

    // Only retire entries created by discovery. Copies, renamed or retargeted entries, and shared
    // configurations belong to the user, even if another declaration later takes the same name.
    private fun discoveredKey(settings: RunnerAndConfigurationSettings): String? {
        if (settings.isShared) return null
        val (key, id) = when (val config = settings.configuration) {
            is HglRunConfiguration -> {
                if (config.type !is HglApplicationType || config.sourcePath != ".") return null
                "application:${config.selector}" to config.discoveredId
            }
            is HglScopedTestConfiguration -> {
                if (config.testPath != "." || config.allModules != config.moduleName.isEmpty()) return null
                val kind = if (config.integrationOnly) "integration" else "test"
                "$kind:${config.moduleName}" to config.discoveredId
            }
            else -> return null
        }
        return key.takeIf { id == "$key|${settings.uniqueID}" }
    }
}
