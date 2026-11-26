package io.github.hhenson.hgl.run

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.github.hhenson.hgl.diagnostics.HglCheckService
import io.github.hhenson.hgl.project.HglBuild
import io.github.hhenson.hgl.project.HglBuildFile

@Service(Service.Level.PROJECT)
@State(name = "HglTestRunner", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class HglTestSettings : PersistentStateComponent<HglTestSettings.Options> {
    data class Options(
        var executable: String = "hgl",
        var roots: String = "",
        var checkOnSave: Boolean = true,
        var documentationModel: String = "",
        var compilerOverride: String = "",
    ) {
        fun sourceRoots(): List<String> = roots.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    private var options = Options()
    override fun getState() = options
    override fun loadState(state: Options) { options = state }
}

internal fun HglTestSettings.Options.sharedDefaults() = HglBuild(compiler = executable, sourceRoots = sourceRoots())

internal fun Project.buildSettings(): HglBuild {
    val local = service<HglTestSettings>().state
    val shared = service<HglBuildFile>().read()
        ?: HglBuild(compiler = local.executable, sourceRoots = local.sourceRoots(), buildDir = "")
    return if (local.compilerOverride.isBlank()) shared else shared.copy(compiler = local.compilerOverride)
}

class HglTestSettingsPage(private val project: Project) : BoundConfigurable("HGL") {
    private val file = project.service<HglBuildFile>()
    private val local = project.service<HglTestSettings>().state
    private var previous: HglBuild? = null
    private fun existing() = previous ?: local.sharedDefaults()
    private var compiler = ""
    private val rootFields = HglRootFields(project)
    private var buildDir = ""
    private var binDir = ""
    private var docsDir = ""
    private var localDraft = local.copy()

    override fun reset() {
        previous = runCatching { file.read() }.getOrNull()
        val build = existing()
        compiler = build.compiler
        rootFields.reset(build)
        buildDir = build.buildDir
        binDir = build.binDir
        docsDir = build.docsDir
        localDraft = local.copy()
        super.reset()
    }

    override fun apply() {
        super.apply()
        val build = HglBuild(
            compiler = compiler.trim(),
            buildDir = buildDir.trim(), binDir = binDir.trim(), docsDir = docsDir.trim(),
        ).let(rootFields::applyTo)
        try {
            if (build != existing()) {
                file.save(build, previous)
                previous = build
            }
        } catch (error: Exception) {
            throw ConfigurationException(error.localizedMessage ?: "Cannot save hgl.json.")
        }
        local.compilerOverride = localDraft.compilerOverride.trim()
        local.checkOnSave = localDraft.checkOnSave
        local.documentationModel = localDraft.documentationModel.trim()
        project.getServiceIfCreated(HglCheckService::class.java)?.reset()
    }

    override fun createPanel() = panel {
        group("Project build settings (hgl.json)") {
            row("Compiler:") { textField().bindText(::compiler).align(AlignX.FILL) }
            rootFields.rows(this)
            row("Build directory:") { textField().bindText(::buildDir).align(AlignX.FILL) }
            row("Binary directory:") { textField().bindText(::binDir).align(AlignX.FILL) }
            row("Documentation directory:") { textField().bindText(::docsDir).align(AlignX.FILL) }
        }
        group("This workspace") {
            row("Compiler override:") { textField().bindText({ localDraft.compilerOverride }, { localDraft.compilerOverride = it }).align(AlignX.FILL) }
            row("Documentation model override:") {
                textField().bindText({ localDraft.documentationModel }, { localDraft.documentationModel = it }).align(AlignX.FILL)
            }
            row { checkBox("Check HGL sources on save").bindSelected({ localDraft.checkOnSave }, { localDraft.checkOnSave = it }) }
        }
    }
}
