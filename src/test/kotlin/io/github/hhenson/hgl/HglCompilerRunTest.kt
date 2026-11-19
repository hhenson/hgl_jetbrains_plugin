package io.github.hhenson.hgl

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import io.github.hhenson.hgl.run.HglRunConfiguration
import io.github.hhenson.hgl.run.HglTestSettings
import java.io.File

/** Opt in with HGL_TEST_CLI and a Python environment containing hgraph. */
class HglCompilerRunTest : HglEditorFixture() {
    // A reused light project can refer to a previous test's deleted process working directory.
    private val descriptor = LightProjectDescriptor()
    override fun getProjectDescriptor() = descriptor

    fun testRunConsoleExecutesOnlyTheSelectedCase() {
        val source = """
            module checkout.suite part checks
            test ordinary { assert true }
            integration_test live { assert true }
            test broken { assert false }
        """.trimIndent()
        val root = FileUtil.createTempDirectory("case with spaces", null, true)
        root.resolve("checks.hgl").writeText(source)
        root.resolve("other.hgl").writeText("module other\ntest ordinary { assert false }\nintegration_test live { assert false }\n")
        val settings = project.service<HglTestSettings>()
        val before = settings.state
        try {
            settings.loadState(HglTestSettings.Options(System.getenv("HGL_TEST_CLI"), root.path))
            for (case in listOf("ordinary", "live", "broken")) {
                myFixture.configureByText("checks.hgl", source.replace(" $case {", " <caret>$case {"))
                val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!
                val output = runInConsole(runConfigurationAt(leaf))
                val expected = if (case == "broken") "failed" else "passed"
                val cases = output.stdout.lines().filter { "executable " in it && "::" in it }
                assertSize(1, cases)
                assertTrue(output.stdout + output.stderr, cases.single().contains("checkout.suite::$case"))
                assertTrue(output.stdout, cases.single().trimStart().startsWith(expected, ignoreCase = true))
                assertEquals(output.stdout + output.stderr, case != "broken", output.exitCode == 0)
            }
        } finally {
            settings.loadState(before)
        }
    }

    fun testRunConsoleExecutesOnlyTheSelectedApplication() {
        val root = File(project.basePath!!, "application sources").apply { mkdirs() }
        root.resolve("source.hgl").writeText("""
            module demo.source
            export fn emit(const value: i64) -> i64 {
                yield 1s: value
            }
        """.trimIndent())
        val apps = mapOf("east" to 7, "west" to 9)
        for ((name, value) in apps) {
            root.resolve("$name.hgl").writeText("""
                application demo.$name.backtest
                use demo.source::{emit}
                eval(emit(value: $value))
            """.trimIndent())
        }
        val settings = project.service<HglTestSettings>()
        val before = settings.state
        try {
            settings.loadState(HglTestSettings.Options(System.getenv("HGL_TEST_CLI"), root.name, System.getenv("HGL_HGRAPH_PYTHON").orEmpty()))
            for ((name, value) in apps) {
                myFixture.configureByText("$name.hgl", "<caret>" + root.resolve("$name.hgl").readText())
                val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!
                val output = runInConsole(runConfigurationAt(leaf))
                assertEquals(output.stdout + output.stderr, 0, output.exitCode)
                assertEquals(listOf("1970-01-01T00:00:01.000001Z  $value",
                    "ran demo.$name.backtest on hgraph: 1 publication"), output.stdout.trim().lines())
            }
        } finally {
            settings.loadState(before)
        }
    }

    private fun runInConsole(configuration: HglRunConfiguration): ProcessOutput {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(executor, configuration).build()
        val result = configuration.getState(executor, environment).execute(executor, environment.runner)
        val handler = result.processHandler!!
        try {
            val output = ProcessOutput()
            handler.addProcessListener(CapturingProcessAdapter(output))
            handler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("hgl timed out", { handler.isProcessTerminated }, 30)
            output.exitCode = handler.exitCode!!
            return output
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            result.executionConsole?.let(Disposer::dispose)
            Disposer.dispose(environment)
        }
    }
}
