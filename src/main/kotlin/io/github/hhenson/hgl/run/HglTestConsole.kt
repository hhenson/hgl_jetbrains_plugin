package io.github.hhenson.hgl.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.events.TestFailedEvent
import com.intellij.execution.testframework.sm.runner.events.TestFinishedEvent
import com.intellij.execution.testframework.sm.runner.events.TestIgnoredEvent
import com.intellij.execution.testframework.sm.runner.events.TestStartedEvent
import com.intellij.openapi.util.Key

internal class HglTestConsole(configuration: RunConfiguration, executor: Executor) :
    SMTRunnerConsoleProperties(configuration, "HGL", executor), SMCustomMessagesParsing {
    override fun createTestEventsConverter(framework: String, properties: TestConsoleProperties) =
        HglTestEvents(framework, properties)
}

/** hgl test prints one fixed-column outcome per case followed by indented diagnostic lines. */
internal class HglTestEvents(framework: String, properties: TestConsoleProperties) :
    OutputToGeneralTestEventsConverter(framework, properties) {
    private val line = StringBuilder()
    private val stderr = StringBuilder()
    private var current: MatchResult? = null
    private val details = mutableListOf<String>()
    private var reportedFailure = false
    private var reportedSkip = false

    @Synchronized
    override fun process(text: String, outputType: Key<*>) {
        fireOnUncapturedOutput(text, outputType)
        if (ProcessOutputType.isStderr(outputType)) {
            stderr.append(text)
            if (stderr.length > 16000) stderr.delete(0, stderr.length - 16000)
        }
        if (!ProcessOutputType.isStdout(outputType)) return
        for (part in text.splitToSequence('\n').withIndex()) {
            if (part.index > 0) consumeLine()
            line.append(part.value)
        }
    }

    private fun consumeLine() {
        val text = line.toString().removeSuffix("\r")
        line.clear()
        if (text.startsWith("    ") && current != null) {
            details.add(text.removePrefix("    "))
            return
        }
        finishCase()
        current = header.matchEntire(text)
        current?.let { processor.onTestStarted(TestStartedEvent(it.groupValues[2], null)) }
    }

    private fun finishCase() {
        val case = current ?: return
        val name = case.groupValues[2]
        val reason = details.joinToString("\n")
        when (case.groupValues[1]) {
            "FAILED" -> {
                reportedFailure = true
                processor.onTestFailure(TestFailedEvent(name, reason.ifBlank { "HGL test failed." }, null, false, null, null))
            }
            "not run" -> {
                reportedSkip = true
                processor.onTestIgnored(TestIgnoredEvent(name, reason.ifBlank { "The compiler did not run this case." }, null))
            }
        }
        processor.onTestFinished(TestFinishedEvent(name, null as Long?))
        current = null
        details.clear()
    }

    @Synchronized
    override fun flushBufferOnProcessTermination(exitCode: Int) {
        if (line.isNotEmpty()) consumeLine()
        finishCase()
        if (exitCode == 1 && !reportedFailure || exitCode == 2 && !reportedFailure && !reportedSkip) {
            val name = "hgl test"
            val diagnostic = stderr.toString().trim().ifBlank { "Compiler exited with code $exitCode. See the console output." }
            val missingOption = listOf("--test-path", "--integration-only").firstOrNull { "unexpected argument '$it'" in diagnostic }
            val message = if (missingOption == null) diagnostic else
                "This test selection requires an HGL compiler with hgl test $missingOption. Check the compiler in HGL build settings.\n$diagnostic"
            processor.onTestStarted(TestStartedEvent(name, null))
            processor.onTestFailure(TestFailedEvent(name, message, null, true, null, null))
            processor.onTestFinished(TestFinishedEvent(name, null as Long?))
        }
    }

    companion object {
        private val header = Regex("""(passed|FAILED|not run) +(?:executable|rejection) +(.+)""")
    }
}
