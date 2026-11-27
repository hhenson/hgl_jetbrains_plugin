package io.github.hhenson.hgl.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.openapi.util.NotNullLazyValue
import io.github.hhenson.hgl.HglIcons
import java.nio.file.Path
import javax.swing.JTextField

private const val IDENTIFIER = "[A-Za-z_][A-Za-z_0-9]*"
private const val DOTTED_PATH = "$IDENTIFIER(?:\\.$IDENTIFIER)*"

// The CLI prints these identities for examples owned by declarations, members and unit headers.
private const val EXAMPLE_OWNER = "(?:$DOTTED_PATH|\\[(?:module|declaration|part $IDENTIFIER)\\])"
private const val EXAMPLE = "$EXAMPLE_OWNER(?:~[1-9][0-9]*)?#example-[1-9][0-9]*"

abstract class HglRunType(
    id: String, name: String, description: String,
    val selectorLabel: String, val selectorPattern: Regex, val command: List<String>,
) : SimpleConfigurationType(id, name, description, NotNullLazyValue.createValue { HglIcons.FILE }) {
    override fun createTemplateConfiguration(project: Project) = HglRunConfiguration(project, this)
    override fun getOptionsClass() = HglRunOptions::class.java
}

class HglTestType : HglRunType(
    "HglSingleTest", "HGL Test", "Run one named HGL test",
    "Module-qualified test or documentation example", Regex("$DOTTED_PATH::(?:$IDENTIFIER|$EXAMPLE)"), listOf("test", "--test"),
)

class HglApplicationType : HglRunType(
    "HglApplication", "HGL Application", "Run one HGL application",
    "Application path (e.g. demo.backtest)", Regex(DOTTED_PATH), listOf("run", "--application"),
)

class HglRunOptions : RunConfigurationOptions() {
    var discoveredId by string("")
    var selector by string("")
    var sourcePath by string("")
}

class HglRunConfiguration(project: Project, private val runType: HglRunType) :
    RunConfigurationBase<HglRunOptions>(project, runType, runType.displayName) {
    private val runOptions get() = options as HglRunOptions
    internal var discoveredId: String
        get() = runOptions.discoveredId.orEmpty()
        set(value) { runOptions.discoveredId = value }
    var selector: String
        get() = runOptions.selector.orEmpty()
        set(value) { runOptions.selector = value }
    var sourcePath: String
        get() = runOptions.sourcePath.orEmpty()
        set(value) { runOptions.sourcePath = value }

    override fun getConfigurationEditor() = object : SettingsEditor<HglRunConfiguration>() {
        private val selectorField = JTextField()
        override fun createEditor() = panel {
            row(runType.selectorLabel) { cell(selectorField).align(AlignX.FILL) }
            if (runType is HglTestType) row { link("Configure HGL Test Roots…") { HglTestRootsDialog.open(project) } }
        }
        override fun resetEditorFrom(configuration: HglRunConfiguration) { selectorField.text = configuration.selector }
        override fun applyEditorTo(configuration: HglRunConfiguration) { configuration.selector = selectorField.text.trim() }
    }

    override fun checkConfiguration() {
        if (!selector.matches(runType.selectorPattern)) throw RuntimeConfigurationError("Invalid ${runType.selectorLabel.lowercase()}.")
        val settings = try { project.buildSettings() } catch (error: IllegalArgumentException) {
            throw RuntimeConfigurationError(error.localizedMessage)
        }
        if (settings.compiler.isBlank()) throw RuntimeConfigurationError("Set the HGL executable in Settings | Build, Execution, Deployment | HGL.")
        val roots = if (runType is HglTestType) settings.programRootsForTests else settings.sourceRoots
        if (sourcePath.isBlank() && roots.isEmpty()) {
            throw RuntimeConfigurationError("Run from a declaration or set source roots in Settings | Build, Execution, Deployment | HGL.")
        }
    }

    internal fun commandLine(debugBuild: Path? = null): GeneralCommandLine {
        checkConfiguration()
        val settings = project.buildSettings()
        val roots = if (runType is HglTestType) settings.testInputs(Path.of(requireNotNull(project.basePath)), sourcePath)
            else settings.sourceRoots
        val command = hglCommandLine(settings.compiler)
            .withParameters(runType.command)
            .withParameters(selector, "--target", "cpp")
            .withWorkDirectory(project.basePath)
        if (runType is HglTestType && settings.programRootsForTests.isNotEmpty()) {
            val scopes = if (sourcePath.isNotBlank()) listOf(sourcePath) else settings.testRoots
            for (path in scopes) command.addParameters("--test-path", path)
        }
        if (debugBuild != null) command.addParameters("--debug-build", debugBuild.toString())
        else if (settings.buildDir.isNotBlank()) command.addParameters("--keep", settings.buildDir)
        command.addParameter("--")
        command.addParameters(roots.ifEmpty { listOf(sourcePath) })
        return command
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment) =
        hglRunState(this, environment, isTest = runType is HglTestType) { commandLine() }
}

internal fun hglRunState(
    configuration: RunConfiguration, environment: ExecutionEnvironment, isTest: Boolean,
    commandLine: () -> GeneralCommandLine,
) = object : CommandLineState(environment) {
    override fun createConsole(executor: Executor) =
        if (isTest) SMTestRunnerConnectionUtil.createConsole(HglTestConsole(configuration, executor))
        else super.createConsole(executor)

    override fun startProcess() = KillableColoredProcessHandler(commandLine()).also {
        ProcessTerminatedListener.attach(it, configuration.project)
    }
}
