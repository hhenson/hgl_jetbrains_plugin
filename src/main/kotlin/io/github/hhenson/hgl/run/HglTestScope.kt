package io.github.hhenson.hgl.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.hhenson.hgl.HglFileType
import io.github.hhenson.hgl.HglIcons
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JComboBox

class HglScopedTestType : SimpleConfigurationType(
    "HglScopedTests", "HGL Test Suite", "Run HGL tests in a file, folder, module or project",
    NotNullLazyValue.createValue { HglIcons.FILE },
) {
    override fun createTemplateConfiguration(project: Project) = HglScopedTestConfiguration(project, this)
    override fun getOptionsClass() = HglScopedTestOptions::class.java
}

class HglScopedTestOptions : RunConfigurationOptions() {
    var discoveredId by string("")
    var testPath by string("")
    var moduleName by string("")
    var allModules by property(false)
    var integrationOnly by property(false)
}

class HglScopedTestConfiguration(project: Project, type: HglScopedTestType) :
    RunConfigurationBase<HglScopedTestOptions>(project, type, type.displayName) {
    private val scope get() = options as HglScopedTestOptions
    internal var discoveredId: String
        get() = scope.discoveredId.orEmpty()
        set(value) { scope.discoveredId = value }
    var testPath: String
        get() = scope.testPath.orEmpty()
        set(value) { scope.testPath = value }
    var moduleName: String
        get() = scope.moduleName.orEmpty()
        set(value) { scope.moduleName = value }
    var allModules: Boolean
        get() = scope.allModules
        set(value) { scope.allModules = value }
    var integrationOnly: Boolean
        get() = scope.integrationOnly
        set(value) { scope.integrationOnly = value }

    override fun getConfigurationEditor() = object : SettingsEditor<HglScopedTestConfiguration>() {
        private val path = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor(HglFileType))
        }
        private val kinds = JComboBox(arrayOf("Tests", "Integration tests"))
        override fun createEditor() = panel {
            if (allModules) row("Scope:") { label("All modules") }
            else if (moduleName.isEmpty()) row("File or folder:") { cell(path).resizableColumn().align(AlignX.FILL) }
            else row("Module:") { label(moduleName) }
            row("Run:") { cell(kinds) }
            row { link("Configure HGL Test Roots…") { HglTestRootsDialog.open(project) } }
        }
        override fun resetEditorFrom(configuration: HglScopedTestConfiguration) {
            path.text = configuration.testPath
            kinds.selectedIndex = if (configuration.integrationOnly) 1 else 0
        }
        override fun applyEditorTo(configuration: HglScopedTestConfiguration) {
            configuration.testPath = path.text.trim()
            configuration.integrationOnly = kinds.selectedIndex == 1
        }
    }

    override fun checkConfiguration() {
        if (testPath.isBlank()) throw RuntimeConfigurationError("Choose an HGL file or folder.")
        val base = project.basePath ?: throw RuntimeConfigurationError("Open a project directory to run HGL tests.")
        val path = try { Path.of(base).resolve(testPath) } catch (_: InvalidPathException) {
            throw RuntimeConfigurationError("Invalid test path: $testPath")
        }
        if (!Files.isDirectory(path) && !(Files.isRegularFile(path) && path.fileName.toString().endsWith(".hgl"))) {
            throw RuntimeConfigurationError("Choose an existing HGL file or folder: $testPath")
        }
        try { project.buildSettings() } catch (error: IllegalArgumentException) {
            throw RuntimeConfigurationError(error.localizedMessage)
        }
    }

    internal fun commandLine(debugBuild: Path? = null): GeneralCommandLine {
        checkConfiguration()
        val build = project.buildSettings()
        val base = Path.of(requireNotNull(project.basePath))
        val selected = base.resolve(testPath).normalize()
        val caseRoots = build.caseRoots.associateWith { base.resolve(it).normalize() }
        val contained = caseRoots.filterValues { it.startsWith(selected) }.keys
        val roots = if (allModules || moduleName.isNotEmpty()) build.testInputs(base, null).ifEmpty { listOf(".") }
            else build.testInputs(base, testPath)
        val command = hglCommandLine(build.compiler).withWorkDirectory(base.toFile())
            .withParameters("test", "--target", "cpp")
        val scopes = when {
            allModules -> build.testRoots
            moduleName.isNotEmpty() -> project.service<HglProjectTargets>().moduleSources(moduleName, caseRoots.values.ifEmpty { listOf(base) })
            caseRoots.isEmpty() || (build.testRoots.isEmpty() && contained.size == caseRoots.size) -> emptyList()
            caseRoots.values.any { selected.startsWith(it) } || contained.isEmpty() -> listOf(testPath)
            else -> contained
        }
        for (path in scopes) command.addParameters("--test-path", path)
        if (integrationOnly) command.addParameter("--integration-only")
        if (debugBuild != null) command.addParameters("--debug-build", debugBuild.toString())
        else if (build.buildDir.isNotBlank()) command.addParameters("--keep", build.buildDir)
        command.addParameter("--")
        command.addParameters(roots)
        return command
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment) =
        hglRunState(this, environment, isTest = true) { commandLine() }
}
