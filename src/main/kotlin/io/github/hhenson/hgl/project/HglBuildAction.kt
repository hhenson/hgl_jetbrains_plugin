package io.github.hhenson.hgl.project

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.hhenson.hgl.HglFileType
import io.github.hhenson.hgl.run.buildSettings
import io.github.hhenson.hgl.run.hglCommandLine
import java.nio.file.Files
import java.nio.file.Path

/** Implemented by the optional CLion Compilation Database integration. */
internal interface HglCompilationDatabaseImporter {
    fun load(database: VirtualFile)
}

class HglBuildAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isVisible = project != null && (project.service<HglBuildFile>().file() != null ||
            event.getData(CommonDataKeys.VIRTUAL_FILE)?.fileType == HglFileType)
        event.presentation.isEnabled = project != null && project.getUserData(running) != true && TrustedProjects.isProjectTrusted(project)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        if (!TrustedProjects.isProjectTrusted(project) || project.getUserData(running) == true) return
        FileDocumentManager.getInstance().saveAllDocuments()
        val selected = event.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.fileType == HglFileType }?.path
        project.putUserData(running, true)
        object : Task.Backgroundable(project, "Build HGL project", true) {
            private lateinit var database: VirtualFile
            override fun run(indicator: ProgressIndicator) {
                database = build(project, selected, indicator)
            }
            override fun onSuccess() {
                project.getService(HglCompilationDatabaseImporter::class.java)?.load(database)
                group().createNotification("HGL build succeeded", database.presentableUrl, NotificationType.INFORMATION).notify(project)
            }
            override fun onThrowable(error: Throwable) {
                group().createNotification("HGL build failed", error.localizedMessage.orEmpty(), NotificationType.ERROR).notify(project)
            }
            override fun onFinished() { project.putUserData(running, null) }
        }.queue()
    }

    companion object {
        private val running = Key.create<Boolean>("hgl.build.running")
        private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("HGL compiler")

        internal fun build(project: Project, selected: String?, indicator: ProgressIndicator): VirtualFile {
            val root = Path.of(requireNotNull(project.basePath))
            val settings = project.buildSettings()
            val sources = settings.sourceRoots.ifEmpty { listOfNotNull(selected) }
            if (sources.isEmpty()) throw ExecutionException("Set HGL source roots or select an HGL file to build.")
            val out = root.resolve(settings.buildDir.ifBlank { HglBuild().buildDir }).normalize()
            val command = hglCommandLine(settings.compiler).withWorkDirectory(root.toFile())
                .withParameters("build", "--target", "cpp", "--compile-commands", "--out", out.toString(), "--")
                .withParameters(sources)
            indicator.checkCanceled()
            val result = CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator)
            indicator.checkCanceled()
            if (result.exitCode != 0) {
                val detail = result.stderr.ifBlank { result.stdout }.trim()
                if ("unrecognized subcommand 'build'" in detail || "unexpected argument '--compile-commands'" in detail) {
                    throw ExecutionException("The configured compiler must support hgl build --compile-commands.\n$detail")
                }
                throw ExecutionException(detail.ifEmpty { "hgl build exited with code ${result.exitCode}." })
            }
            val path = out.resolve("compile_commands.json")
            if (!Files.isRegularFile(path)) throw ExecutionException("hgl build succeeded but did not produce $path")
            val workspace = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(out)
                ?: throw ExecutionException("Cannot load $out")
            // A rebuild can rewrite generated C++ while leaving compile_commands.json unchanged.
            VfsUtil.markDirtyAndRefresh(false, true, true, workspace)
            return workspace.findChild(path.fileName.toString()) ?: throw ExecutionException("Cannot load $path")
        }
    }
}
