package io.github.hhenson.hgl

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import io.github.hhenson.hgl.run.HglTestSettings

/** Opt in with HGL_TEST_CLI and a Python environment containing hgraph. */
class HglCompilerRunTest : HglEditorFixture() {
    fun testRunConsoleExecutesOnlyTheSelectedCase() {
        val source = """
            module checkout.suite part checks
            test ordinary { assert true }
            integration_test live { assert true }
            test broken { assert false }
        """.trimIndent()
        val root = com.intellij.openapi.util.io.FileUtil.createTempDirectory("case with spaces", null, true)
        root.resolve("checks.hgl").writeText(source)
        root.resolve("other.hgl").writeText("module other\ntest ordinary { assert false }\nintegration_test live { assert false }\n")
        val settings = project.service<HglTestSettings>()
        val before = settings.state
        try {
            settings.loadState(HglTestSettings.Options(System.getenv("HGL_TEST_CLI"), root.path))
            for (case in listOf("ordinary", "live", "broken")) {
                myFixture.configureByText("checks.hgl", source.replace(" $case {", " <caret>$case {"))
                val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!
                val config = runConfigurationAt(leaf)
                val executor = DefaultRunExecutor.getRunExecutorInstance()
                val environment = ExecutionEnvironmentBuilder.create(executor, config).build()
                val result = config.getState(executor, environment).execute(executor, environment.runner)
                val handler = result.processHandler!!
                try {
                    val output = ProcessOutput()
                    handler.addProcessListener(CapturingProcessAdapter(output))
                    handler.startNotify()
                    PlatformTestUtil.waitWithEventsDispatching("hgl timed out", { handler.isProcessTerminated }, 30)
                    val expected = if (case == "broken") "failed" else "passed"
                    val cases = output.stdout.lines().filter { "executable " in it && "::" in it }
                    assertSize(1, cases)
                    assertTrue(output.stdout + output.stderr, cases.single().contains("checkout.suite::$case"))
                    assertTrue(output.stdout, cases.single().trimStart().startsWith(expected, ignoreCase = true))
                    assertEquals(output.stdout + output.stderr, case != "broken", handler.exitCode == 0)
                } finally {
                    if (!handler.isProcessTerminated) handler.destroyProcess()
                    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                    result.executionConsole?.let(Disposer::dispose)
                    Disposer.dispose(environment)
                }
            }
        } finally {
            settings.loadState(before)
        }
    }
}
