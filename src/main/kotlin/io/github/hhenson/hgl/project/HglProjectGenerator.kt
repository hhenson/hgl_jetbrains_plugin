package io.github.hhenson.hgl.project

import com.intellij.facet.ui.ValidationResult
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGenerator
import io.github.hhenson.hgl.HglIcons
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

class HglProjectGenerator : DirectoryProjectGenerator<Any> {
    private val build = HglBuild()
    private val directories = build.sourceRoots + listOf(build.buildDir, build.binDir, build.docsDir)
    private val files = listOf("src/main.hgl", "src/hello.hgl", HglBuildFile.NAME)

    override fun getName() = "HGL"
    override fun getLogo() = HglIcons.FILE
    override fun getDescription() = "Create a Hello World application and its test."

    override fun validate(baseDirPath: String): ValidationResult = try {
        val conflict = conflictAt(Path.of(baseDirPath))
        if (conflict == null) ValidationResult.OK else ValidationResult(conflict)
    } catch (error: InvalidPathException) {
        ValidationResult(error.reason)
    }

    private fun conflictAt(root: Path): String? {
        if (Files.exists(root) && !Files.isDirectory(root)) return "Select a project directory."
        for (name in directories) {
            val path = root.resolve(name)
            if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isDirectory(path, NOFOLLOW_LINKS)) {
                return "The starter needs a directory at $name."
            }
        }
        val existing = files.firstOrNull { Files.exists(root.resolve(it), NOFOLLOW_LINKS) }
        return existing?.let { "$it already exists." }
    }

    override fun generateProject(project: Project, baseDir: VirtualFile, settings: Any, module: Module) {
        val root = baseDir.toNioPath()
        val created = mutableListOf<Path>()
        try {
            conflictAt(root)?.let { throw IOException(it) }
            for (name in directories) {
                val dir = root.resolve(name)
                if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) created.add(Files.createDirectory(dir))
            }
            val names = if (Files.exists(root.resolve(".gitignore"), NOFOLLOW_LINKS)) files else files + ".gitignore"
            for (name in names) {
                val text = when (name) {
                    HglBuildFile.NAME -> HglBuildFile.encode(build)
                    ".gitignore" -> "/build/\n/bin/\n/docs/\n/.idea/workspace.xml\n"
                    else -> javaClass.getResourceAsStream("/projectTemplates/hello/$name")?.bufferedReader()?.use { it.readText() }
                        ?: throw IOException("Missing starter file $name.")
                }
                val file = root.resolve(name)
                Files.newBufferedWriter(file, CREATE_NEW).use {
                    created.add(file)
                    it.write(text)
                }
            }
        } catch (error: IOException) {
            for (path in created.asReversed()) {
                try {
                    Files.deleteIfExists(path)
                } catch (cleanup: IOException) {
                    error.addSuppressed(cleanup)
                }
            }
            Messages.showErrorDialog(project, error.localizedMessage, "Create HGL Project")
            return
        }

        VfsUtil.markDirtyAndRefresh(false, true, true, baseDir)
        baseDir.findFileByRelativePath("src/main.hgl")?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }
}
