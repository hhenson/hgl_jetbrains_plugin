package io.github.hhenson.hgl.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.runners.ExecutionEnvironment
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
    "HglScopedTests", "HGL Tests in File or Folder", "Run HGL tests in a file or folder",
    NotNullLazyValue.createValue { HglIcons.FILE },
) {
    override fun createTemplateConfiguration(project: Project) = HglScopedTestConfiguration(project, this)
    override fun getOptionsClass() = HglScopedTestOptions::class.java
}

class HglScopedTestOptions : RunConfigurationOptions() {
    var testPath by string("")
    var integrationOnly by property(false)
}

class HglScopedTestConfiguration(project: Project, type: HglScopedTestType) :
    RunConfigurationBase<HglScopedTestOptions>(project, type, type.displayName) {
    private val scope get() = options as HglScopedTestOptions
    var testPath: String
        get() = scope.testPath.orEmpty()
        set(value) { scope.testPath = value }
    var integrationOnly: Boolean
        get() = scope.integrationOnly
        set(value) { scope.integrationOnly = value }

    override fun getConfigurationEditor() = object : SettingsEditor<HglScopedTestConfiguration>() {
        private val path = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor(HglFileType))
        }
        private val kinds = JComboBox(arrayOf("Tests", "Integration tests"))
        override fun createEditor() = panel {
            row("File or folder:") { cell(path).resizableColumn().align(AlignX.FILL) }
            row("Run:") { cell(kinds) }
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

    internal fun commandLine(): GeneralCommandLine {
        checkConfiguration()
        val build = project.buildSettings()
        val base = Path.of(requireNotNull(project.basePath))
        val selected = base.resolve(testPath).normalize()
        val rootPaths = build.sourceRoots.associateWith { base.resolve(it).normalize() }
        val containedRoots = rootPaths.filterValues { it.startsWith(selected) }.keys
        val withinRoot = rootPaths.values.any { selected.startsWith(it) }
        // An explicitly selected HGL file outside configured roots can still import from them.
        val roots = if (withinRoot || containedRoots.isNotEmpty()) build.sourceRoots else build.sourceRoots + testPath
        val command = hglCommandLine(build.compiler).withWorkDirectory(base.toFile())
            .withParameters("test", "--target", "cpp")
        // A project or group folder can contain several configured roots. Keep other roots for imports.
        val scopes = when {
            rootPaths.isEmpty() || containedRoots.size == rootPaths.size -> emptyList()
            withinRoot || containedRoots.isEmpty() -> listOf(testPath)
            else -> containedRoots
        }
        for (path in scopes) command.addParameters("--test-path", path)
        if (integrationOnly) command.addParameter("--integration-only")
        if (build.buildDir.isNotBlank()) command.addParameters("--keep", build.buildDir)
        command.addParameter("--")
        command.addParameters(roots)
        return command
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment) =
        hglRunState(this, environment, isTest = true) { commandLine() }
}
