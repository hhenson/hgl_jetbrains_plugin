package io.github.hhenson.hgl.documentation

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.BrowserUtil
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.hhenson.hgl.HglFileType
import io.github.hhenson.hgl.project.HglBuildFile
import io.github.hhenson.hgl.run.buildSettings
import io.github.hhenson.hgl.run.hglCommandLine
import com.intellij.openapi.actionSystem.CommonDataKeys
import java.nio.file.Path

class HglGenerateDocs : DumbAwareAction() {
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
        object : Task.Backgroundable(project, "Generate HGL documentation", true) {
            private lateinit var index: Path
            override fun run(indicator: ProgressIndicator) {
                index = generate(project, selected, indicator)
            }
            override fun onSuccess() {
                val notification = group().createNotification("HGL documentation generated", index.toString(), NotificationType.INFORMATION)
                notification.addAction(NotificationAction.createSimple("Open documentation") { BrowserUtil.browse(index.toUri()) })
                notification.notify(project)
            }
            override fun onThrowable(error: Throwable) {
                group().createNotification("HGL documentation failed", error.localizedMessage.orEmpty(), NotificationType.ERROR).notify(project)
            }
            override fun onFinished() { project.putUserData(running, null) }
        }.queue()
    }

    companion object {
        private val running = Key.create<Boolean>("hgl.generating.docs")
        private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("HGL compiler")

        internal fun generate(project: Project, selected: String?, indicator: ProgressIndicator): Path {
            val base = Path.of(requireNotNull(project.basePath))
            val build = project.buildSettings()
            val roots = build.sourceRoots.ifEmpty { listOfNotNull(selected) }
            if (roots.isEmpty()) throw ExecutionException("Set source roots in HGL build settings or select an HGL file.")
            val out = base.resolve(build.docsDir.ifBlank { "docs" }).normalize()
            for (format in listOf("html", "json")) {
                indicator.checkCanceled()
                val command = hglCommandLine(build.compiler).withWorkDirectory(base.toFile())
                    .withParameters("doc", "--format", format, "--out", out.toString(), "--").withParameters(roots)
                val result = CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator)
                indicator.checkCanceled()
                if (result.exitCode != 0) throw ExecutionException(result.stderr.ifBlank { result.stdout }.trim())
            }
            LocalFileSystem.getInstance().refreshNioFiles(listOf(out), true, true, null)
            return out.resolve("index.html")
        }
    }
}
