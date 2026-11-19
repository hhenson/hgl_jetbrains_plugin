package io.github.hhenson.hgl.diagnostics

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.hhenson.hgl.HglFileType

internal val VirtualFile.isLocalHgl: Boolean
    get() = fileSystem == LocalFileSystem.getInstance() && fileType == HglFileType

class HglSaveListener : FileDocumentManagerListener {
    override fun afterDocumentSaved(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isLocalHgl) return
        ApplicationManager.getApplication().invokeLater {
            if (file.isValid) {
                for (project in ProjectLocator.getInstance().getProjectsForFile(file)) {
                    if (!project.isDisposed) project.service<HglCheckService>().saved(file)
                }
            }
        }
    }
}
