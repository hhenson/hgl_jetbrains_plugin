package io.github.hhenson.hgl.run

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.github.hhenson.hgl.diagnostics.HglCheckService

@Service(Service.Level.PROJECT)
@State(name = "HglTestRunner", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class HglTestSettings : PersistentStateComponent<HglTestSettings.Options> {
    data class Options(
        var executable: String = "hgl",
        var roots: String = "",
        var checkOnSave: Boolean = true,
    ) {
        fun sourceRoots(): List<String> = roots.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    private var options = Options()
    override fun getState() = options
    override fun loadState(state: Options) { options = state }
}

class HglTestSettingsPage(private val project: Project) : BoundConfigurable("HGL Test Runner") {
    private val settings = project.service<HglTestSettings>().state

    override fun apply() {
        super.apply()
        project.getServiceIfCreated(HglCheckService::class.java)?.reset()
    }

    override fun createPanel() = panel {
        row { checkBox("Check HGL sources on save").bindSelected(settings::checkOnSave) }
        row("HGL executable:") { textField().bindText(settings::executable).align(AlignX.FILL) }
        row("Source roots:") {
            textArea().bindText(settings::roots).align(AlignX.FILL)
                .comment("One file or directory per line, relative to the project or absolute. Leave empty to use the selected source file.")
        }
    }
}
