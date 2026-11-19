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

class HglTestType : SimpleConfigurationType(
    "HglSingleTest", "HGL Test", "Run one named HGL test", NotNullLazyValue.createValue { HglIcons.FILE },
) {
    override fun createTemplateConfiguration(project: Project) = HglTestConfiguration(project, this)
    override fun getOptionsClass() = HglTestOptions::class.java
}

class HglTestOptions : RunConfigurationOptions() {
    var selector by string("")
    var sourcePath by string("")
}

class HglTestConfiguration(project: Project, type: HglTestType) :
    RunConfigurationBase<HglTestOptions>(project, type, "HGL Test") {
    private val testOptions get() = options as HglTestOptions
    var selector: String
        get() = testOptions.selector.orEmpty()
        set(value) { testOptions.selector = value }
    var sourcePath: String
        get() = testOptions.sourcePath.orEmpty()
        set(value) { testOptions.sourcePath = value }

    override fun getConfigurationEditor() = object : SettingsEditor<HglTestConfiguration>() {
        private val test = JTextField()
        override fun createEditor() = LabeledComponent.create(test, "Module-qualified test")
        override fun resetEditorFrom(configuration: HglTestConfiguration) { test.text = configuration.selector }
        override fun applyEditorTo(configuration: HglTestConfiguration) { configuration.selector = test.text.trim() }
    }

    override fun checkConfiguration() {
        if (!selector.matches(qualifiedName)) throw RuntimeConfigurationError("Choose a test as module.path::name.")
        val settings = project.service<HglTestSettings>().state
        if (settings.executable.isBlank()) throw RuntimeConfigurationError("Set the HGL executable in Settings | HGL Test Runner.")
        if (sourcePath.isBlank() && settings.roots.isBlank()) {
            throw RuntimeConfigurationError("Run from a test declaration or set source roots in Settings | HGL Test Runner.")
        }
    }

    internal fun commandLine(): GeneralCommandLine {
        checkConfiguration()
        val settings = project.service<HglTestSettings>().state
        val roots = settings.roots.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val command = GeneralCommandLine(settings.executable.trim(), "test", "--test", selector, "--target", "hgraph")
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

    private companion object {
        val qualifiedName = Regex("""[A-Za-z_][A-Za-z_0-9]*(\.[A-Za-z_][A-Za-z_0-9]*)*::[A-Za-z_][A-Za-z_0-9]*""")
    }
}
