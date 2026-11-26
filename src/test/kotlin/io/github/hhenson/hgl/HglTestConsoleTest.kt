package io.github.hhenson.hgl

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import io.github.hhenson.hgl.run.HglTestConsole
import io.github.hhenson.hgl.run.HglTestSettings
import javax.swing.JPanel

class HglTestConsoleTest : HglEditorFixture() {
    private class Process : NopProcessHandler() {
        fun finish(code: Int) = notifyProcessTerminated(code)
    }

    private fun results(chunks: List<String>, code: Int, stderr: String = "", check: (List<SMTestProxy>) -> Unit) {
        myFixture.configureByText("suite.hgl", "module suite\ntest <caret>selected { assert true }")
        val config = runConfigurationAt(myFixture.file.findElementAt(myFixture.caretOffset)!!)
        val console = SMTestRunnerConnectionUtil.createConsole(HglTestConsole(config, DefaultRunExecutor.getRunExecutorInstance()))
        val process = Process()
        try {
            console.attachToProcess(process)
            process.startNotify()
            process.notifyTextAvailable(stderr, ProcessOutputTypes.STDERR)
            for (chunk in chunks) process.notifyTextAvailable(chunk, ProcessOutputTypes.STDOUT)
            process.finish(code)
            val root = console.resultsViewer.testsRootNode
            PlatformTestUtil.waitWithEventsDispatching("test results not delivered", { root.isFinal }, 10)
            check(root.children)
        } finally {
            Disposer.dispose(console)
        }
    }

    fun testResultsOfferRootSettingsWithoutAProjectViewSelection() {
        myFixture.configureByText("suite.hgl", "module suite\ntest <caret>selected { assert true }")
        val config = runConfigurationAt(myFixture.file.findElementAt(myFixture.caretOffset)!!)
        val properties = HglTestConsole(config, DefaultRunExecutor.getRunExecutorInstance())
        val actions = DefaultActionGroup()
        properties.appendAdditionalActions(actions, JPanel(), properties)
        assertTrue(actions.getChildren(null).any { it.templatePresentation.text == "Configure HGL Test Roots…" })
    }

    fun testPassFailAndSkipWithChunkedOutputAndRejectionCases() {
        results(listOf("passed   executable  demo::good\r", "\nFAILED   rejection   file with spaces.hgl:4 fn bad\n    expected ",
            "a diagnostic\nnot run  executable  demo::later\n    target unavailable"), 1) { cases ->
            assertEquals(listOf("demo::good", "file with spaces.hgl:4 fn bad", "demo::later"), cases.map { it.name })
            assertTrue(cases[0].isPassed)
            assertTrue(cases[1].isDefect)
            assertTrue(cases[1].errorMessage.orEmpty().contains("expected a diagnostic"))
            assertTrue(cases[2].isIgnored)
        }
    }

    fun testCompilerInvocationErrorAppearsInResults() {
        results(emptyList(), 2, "hgl: invalid source root") { cases ->
            assertEquals("hgl test", cases.single().name)
            assertTrue(cases.single().isDefect)
            assertTrue(cases.single().errorMessage.orEmpty().contains("invalid source root"))
        }
    }

    fun testFailureBeforeCasesPointsToSetupOnlyWhenSourceRootsAreEmpty() {
        val diagnostic = "error[operator/operator.no_candidate]: no value candidate of operator `convert`"
        val settings = project.service<HglTestSettings>().state
        val previous = settings.roots
        try {
            settings.roots = ""
            results(emptyList(), 1, diagnostic) {
                val message = it.single().errorMessage.orEmpty()
                assertTrue(message.startsWith(diagnostic))
                assertTrue(message.contains("Configure HGL Test Roots…"))
            }
            settings.roots = "library"
            results(emptyList(), 1, diagnostic) { assertEquals(diagnostic, it.single().errorMessage) }
        } finally {
            settings.roots = previous
        }
    }

    fun testOlderCompilerExplainsWhichTestSelectionOptionIsRequired() {
        for (option in listOf("--test-path", "--integration-only")) {
            val diagnostic = "error: unexpected argument '$option' found"
            results(emptyList(), 2, diagnostic) { cases ->
                val error = cases.single()
                assertTrue(error.isDefect)
                assertTrue(error.errorMessage.orEmpty().contains("requires an HGL compiler with hgl test $option"))
                assertTrue(error.errorMessage.orEmpty().contains(diagnostic))
            }
        }
    }

    fun testNotRunExitDoesNotAddAFailedCase() {
        results(listOf("not run  executable  demo::later\n    no target\n"), 2) {
            assertTrue(it.single().isIgnored)
        }
    }
}
