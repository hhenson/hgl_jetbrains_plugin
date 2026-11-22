package io.github.hhenson.hgl.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScopesCore
import io.github.hhenson.hgl.HglFileType
import java.nio.file.Path

class HglRunTestsAction : HglTestScopeAction(false)
class HglRunIntegrationTestsAction : HglTestScopeAction(true)

abstract class HglTestScopeAction(private val integrationOnly: Boolean) : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun selection(event: AnActionEvent): VirtualFile? {
        if (event.place == ActionPlaces.EDITOR_TAB_POPUP) return event.getData(CommonDataKeys.VIRTUAL_FILE)
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        return if (files == null) event.getData(CommonDataKeys.VIRTUAL_FILE) else files.singleOrNull()
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = selection(event)
        val visible = project != null && file != null && file.isInLocalFileSystem && when {
            file.isDirectory -> FileTypeIndex.containsFileOfType(HglFileType, GlobalSearchScopesCore.directoryScope(project, file, true))
            else -> file.fileType == HglFileType
        }
        event.presentation.isVisible = visible
        event.presentation.isEnabled = visible && TrustedProjects.isProjectTrusted(project)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = selection(event) ?: return
        if (!TrustedProjects.isProjectTrusted(project)) return
        val settings = settingsFor(project, file)
        RunManager.getInstance(project).selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    internal fun settingsFor(project: Project, file: VirtualFile): RunnerAndConfigurationSettings {
        val manager = RunManager.getInstance(project)
        val type = ConfigurationTypeUtil.findConfigurationType(HglScopedTestType::class.java)
        val base = Path.of(requireNotNull(project.basePath))
        val selected = file.toNioPath()
        val path = if (selected.startsWith(base)) base.relativize(selected).joinToString("/").ifEmpty { "." } else file.path
        manager.getConfigurationSettingsList(type).firstOrNull {
            val configuration = it.configuration as HglScopedTestConfiguration
            configuration.moduleName.isEmpty() && !configuration.allModules &&
                configuration.testPath == path && configuration.integrationOnly == integrationOnly
        }?.let { return it }
        val kind = if (integrationOnly) "HGL Integration Tests" else "HGL Tests"
        val name = manager.suggestUniqueName("$kind in $path", type)
        return manager.createConfiguration(name, type).also {
            val configuration = it.configuration as HglScopedTestConfiguration
            configuration.testPath = path
            configuration.integrationOnly = integrationOnly
            manager.setTemporaryConfiguration(it)
        }
    }
}
