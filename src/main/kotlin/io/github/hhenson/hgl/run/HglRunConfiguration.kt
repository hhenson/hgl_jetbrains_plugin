package io.github.hhenson.hgl.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.util.NotNullLazyValue
import io.github.hhenson.hgl.HglIcons
import javax.swing.JTextField

private const val IDENTIFIER = "[A-Za-z_][A-Za-z_0-9]*"
private const val DOTTED_PATH = "$IDENTIFIER(?:\\.$IDENTIFIER)*"

abstract class HglRunType(
    id: String, name: String, description: String,
    val selectorLabel: String, val selectorPattern: Regex, val command: List<String>,
) : SimpleConfigurationType(id, name, description, NotNullLazyValue.createValue { HglIcons.FILE }) {
    override fun createTemplateConfiguration(project: Project) = HglRunConfiguration(project, this)
    override fun getOptionsClass() = HglRunOptions::class.java
}

class HglTestType : HglRunType(
    "HglSingleTest", "HGL Test", "Run one named HGL test",
    "Module-qualified test (module.path::name)", Regex("$DOTTED_PATH::$IDENTIFIER"), listOf("test", "--test"),
)

class HglApplicationType : HglRunType(
    "HglApplication", "HGL Application", "Run one HGL application",
    "Application path (e.g. demo.backtest)", Regex(DOTTED_PATH), listOf("run", "--application"),
)

class HglRunOptions : RunConfigurationOptions() {
    var selector by string("")
    var sourcePath by string("")
}

class HglRunConfiguration(project: Project, private val runType: HglRunType) :
    RunConfigurationBase<HglRunOptions>(project, runType, runType.displayName) {
    private val runOptions get() = options as HglRunOptions
    var selector: String
        get() = runOptions.selector.orEmpty()
        set(value) { runOptions.selector = value }
    var sourcePath: String
        get() = runOptions.sourcePath.orEmpty()
        set(value) { runOptions.sourcePath = value }

    override fun getConfigurationEditor() = object : SettingsEditor<HglRunConfiguration>() {
        private val selectorField = JTextField()
        override fun createEditor() = LabeledComponent.create(selectorField, runType.selectorLabel)
        override fun resetEditorFrom(configuration: HglRunConfiguration) { selectorField.text = configuration.selector }
        override fun applyEditorTo(configuration: HglRunConfiguration) { configuration.selector = selectorField.text.trim() }
    }

    override fun checkConfiguration() {
        if (!selector.matches(runType.selectorPattern)) throw RuntimeConfigurationError("Invalid ${runType.selectorLabel.lowercase()}.")
        val settings = project.service<HglTestSettings>().state
        if (settings.executable.isBlank()) throw RuntimeConfigurationError("Set the HGL executable in Settings | HGL Test Runner.")
        if (sourcePath.isBlank() && settings.roots.isBlank()) {
            throw RuntimeConfigurationError("Run from a declaration or set source roots in Settings | HGL Test Runner.")
        }
    }

    internal fun commandLine(): GeneralCommandLine {
        checkConfiguration()
        val settings = project.service<HglTestSettings>().state
        val roots = settings.sourceRoots()
        val command = GeneralCommandLine(settings.executable.trim())
            .withParameters(runType.command)
            .withParameters(selector, "--target", "hgraph")
            .withWorkDirectory(project.basePath)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        settings.python.trim().takeIf(String::isNotEmpty)?.let { command.addParameters("--python", it) }
        command.addParameter("--")
        command.addParameters(roots.ifEmpty { listOf(sourcePath) })
        return command
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment) = object : CommandLineState(environment) {
        override fun startProcess() = KillableColoredProcessHandler(commandLine()).also {
            ProcessTerminatedListener.attach(it, project)
        }
    }
}
