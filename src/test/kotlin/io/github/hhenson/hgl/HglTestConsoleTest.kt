package io.github.hhenson.hgl

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import io.github.hhenson.hgl.run.HglTestConsole

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

    fun testNotRunExitDoesNotAddAFailedCase() {
        results(listOf("not run  executable  demo::later\n    no target\n"), 2) {
            assertTrue(it.single().isIgnored)
        }
    }
}
