package io.github.hhenson.hgl.project

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.cidr.cpp.compdb.CompDBUtil
import com.jetbrains.cidr.cpp.compdb.ID

/** Load the generated sources into the current project's native code model. Called on the EDT. */
internal class HglClionDatabaseImporter(private val project: Project) : HglCompilationDatabaseImporter {
    private var lastRequest: Pair<String, Long>? = null

    override fun load(database: VirtualFile) {
        val path = database.path
        val revision = path to database.modificationStamp
        val linked = CompDBUtil.linkedProjectSettings(project).map { it.externalProjectPath }
        val properties = PropertiesComponent.getInstance(project)
        when {
            path in linked -> {
                if (lastRequest == revision) {
                    val info = ProjectDataManager.getInstance().getExternalProjectData(project, ID, path)
                    if (info?.externalProjectStructure != null && info.lastImportTimestamp == info.lastSuccessfulImportTimestamp) return
                }
                ExternalSystemUtil.refreshProject(path, ImportSpecBuilder(project, ID))
            }
            linked.size == 1 && linked.single() == properties.getValue(OWNED_PATH) -> {
                CompDBUtil.changeLinkedProjectPath(project, database)
                properties.setValue(OWNED_PATH, path)
            }
            else -> {
                CompDBUtil.linkCompDBProject(project, database)
                properties.setValue(OWNED_PATH, path)
            }
        }
        lastRequest = revision
    }

    private companion object {
        const val OWNED_PATH = "io.github.hhenson.hgl.linkedCompilationDatabase"
    }
}
