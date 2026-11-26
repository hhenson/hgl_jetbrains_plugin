package io.github.hhenson.hgl.project

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal const val DEFAULT_DOCS_DIR = "docs"

internal data class HglBuild(
    val compiler: String = "hgl",
    val sourceRoots: List<String> = listOf("src"),
    val buildDir: String = "build",
    val binDir: String = "bin",
    val docsDir: String = DEFAULT_DOCS_DIR,
    val testRoots: List<String> = emptyList(),
) {
    // Empty test roots retain inline test discovery in the production sources.
    val caseRoots get() = testRoots.ifEmpty { sourceRoots }
    val programRootsForTests get() = (sourceRoots + testRoots).distinct()

    fun testInputs(base: Path, selected: String?): List<String> {
        val roots = programRootsForTests.distinctBy { base.resolve(it).normalize() }
        if (selected.isNullOrEmpty()) return roots
        val path = base.resolve(selected).normalize()
        val covered = roots.any {
            val root = base.resolve(it).normalize()
            path.startsWith(root) || root.startsWith(path)
        }
        return if (covered) roots else roots + selected
    }

    fun validate() {
        require(compiler.isNotBlank()) { "Enter an HGL compiler command or path." }
        for (path in programRootsForTests + listOf(buildDir, binDir, docsDir)) {
            require(path.isNotBlank()) { "Build paths must not be empty." }
            try {
                Path.of(path)
            } catch (error: InvalidPathException) {
                throw IllegalArgumentException("Invalid path: $path", error)
            }
        }
    }
}

/** The plugin's shared build settings. Other tools may own additional JSON fields. */
@Service(Service.Level.PROJECT)
internal class HglBuildFile(private val project: Project) {
    private data class Snapshot(val file: VirtualFile, val stamp: Long, val build: HglBuild)
    @Volatile private var cached: Snapshot? = null
    private fun root() = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
    fun file() = root()?.findChild(NAME)

    fun read(): HglBuild? {
        val file = file() ?: return null
        val stamp = file.modificationStamp
        cached?.let { if (it.file == file && it.stamp == stamp) return it.build }
        val build = decode(VfsUtil.loadText(file))
        cached = Snapshot(file, stamp, build)
        return build
    }

    fun save(build: HglBuild, expected: HglBuild?) {
        build.validate()
        val root = requireNotNull(root()) { "The project needs a directory." }
        WriteCommandAction.runWriteCommandAction(project) {
            val file = root.findChild(NAME)
            val documents = FileDocumentManager.getInstance()
            require(file == null || !documents.isFileModified(file)) { "Save your edits to $NAME before applying build settings." }
            val text = file?.let(VfsUtil::loadText)
            require(text?.let(::decode) == expected) { "$NAME changed. Reopen the build settings to load it." }
            val json = text?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject()
            val target = file ?: root.createChildData(this, NAME)
            VfsUtil.saveText(target, encode(build, json))
        }
    }

    companion object {
        const val NAME = "hgl.json"
        private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        fun encode(build: HglBuild, json: JsonObject = JsonObject()): String {
            val section = json.getAsJsonObject("build") ?: JsonObject().also { json.add("build", it) }
            gson.toJsonTree(build).asJsonObject.entrySet().forEach { (key, value) -> section.add(key, value) }
            return gson.toJson(json) + "\n"
        }

        fun decode(text: String): HglBuild {
            try {
                val section = JsonParser.parseString(text).asJsonObject.getAsJsonObject("build")
                    ?: throw IllegalArgumentException("Missing build section.")
                fun string(key: String): String {
                    val value = section.get(key)
                    require(value is JsonPrimitive && value.isString) { "Expected a string for build.$key." }
                    return value.asString
                }
                fun paths(key: String): List<String> {
                    val roots = section.get(key)
                    require(roots != null && roots.isJsonArray) { "Expected an array for build.$key." }
                    return roots.asJsonArray.map {
                        require(it is JsonPrimitive && it.isString) { "Expected a path in build.$key." }
                        it.asString
                    }
                }
                return HglBuild(
                    compiler = string("compiler"), sourceRoots = paths("sourceRoots"),
                    testRoots = if (section.has("testRoots")) paths("testRoots") else emptyList(),
                    buildDir = string("buildDir"), binDir = string("binDir"), docsDir = string("docsDir"),
                ).also { it.validate() }
            } catch (error: RuntimeException) {
                throw IllegalArgumentException("Invalid $NAME: ${error.message}", error)
            }
        }
    }
}
