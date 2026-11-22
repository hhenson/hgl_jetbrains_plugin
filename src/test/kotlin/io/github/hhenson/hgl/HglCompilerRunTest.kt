package io.github.hhenson.hgl

import com.intellij.execution.ExecutionException
import com.google.gson.JsonParser
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.progress.EmptyProgressIndicator
import io.github.hhenson.hgl.documentation.HglGenerateDocs
import io.github.hhenson.hgl.project.HglBuild
import io.github.hhenson.hgl.project.HglBuildAction
import io.github.hhenson.hgl.project.HglBuildFile
import io.github.hhenson.hgl.project.HglCompilationDatabaseImporter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import io.github.hhenson.hgl.run.HglRunGutter
import io.github.hhenson.hgl.run.HglRunConfiguration
import io.github.hhenson.hgl.run.HglTestSettings
import java.io.File

/** Opt in with HGL_TEST_CLI and the native hgraph SDK. */
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
                val output = runInConsole(runConfigurationAt(leaf)) { view ->
                    val root = view.resultsViewer.testsRootNode
                    PlatformTestUtil.waitWithEventsDispatching("missing test result", { root.isFinal && root.children.isNotEmpty() }, 10)
                    val result = root.children.single()
                    assertEquals("checkout.suite::$case", result.name)
                    assertEquals(case != "broken", result.isPassed)
                    assertEquals(case == "broken", result.isDefect)
                }
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
                eval emit(value: $value)
            """.trimIndent())
        }
        val settings = project.service<HglTestSettings>()
        val before = settings.state
        try {
            settings.loadState(HglTestSettings.Options(System.getenv("HGL_TEST_CLI"), root.name))
            for ((name, value) in apps) {
                myFixture.configureByText("$name.hgl", "<caret>" + root.resolve("$name.hgl").readText())
                val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!
                val output = runInConsole(runConfigurationAt(leaf))
                assertEquals(output.stdout + output.stderr, 0, output.exitCode)
                assertEquals(listOf("1970-01-01T00:00:01.000001Z  $value",
                    "ran demo.$name.backtest on cpp: 1 publication"), output.stdout.trim().lines())
            }
        } finally {
            settings.loadState(before)
        }
    }

    fun testGenerateDocsPreservesAnUnmarkedSiteAndRegeneratesInAFreshDirectory() {
        val root = FileUtil.createTempDirectory("hgl docs", null, true)
        val source = root.resolve("guide.hgl")
        source.writeText("module guide\nexport const fn greeting() -> str => \"Hello\"\n")
        val existing = root.resolve("legacy").apply { mkdir() }
        existing.resolve("index.html").writeText("old site")
        existing.resolve("documentation.json").writeText("old model")
        val settings = project.service<HglBuildFile>()
        val build = HglBuild(compiler = requireNotNull(System.getenv("HGL_TEST_CLI")),
            sourceRoots = listOf(source.path), docsDir = existing.path)
        settings.save(build, null)
        try {
            HglGenerateDocs.generate(project, null, EmptyProgressIndicator())
            fail("An unmarked site's files must be preserved")
        } catch (error: ExecutionException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("documentation output"))
        }
        assertEquals("old site", existing.resolve("index.html").readText())
        assertEquals("old model", existing.resolve("documentation.json").readText())

        val output = root.resolve("reference")
        settings.save(build.copy(docsDir = output.path), build)
        val index = HglGenerateDocs.generate(project, null, EmptyProgressIndicator()).toFile()
        assertEquals(output.resolve("index.html"), index)
        output.resolve("notes.txt").writeText("keep")
        assertEquals(index, HglGenerateDocs.generate(project, null, EmptyProgressIndicator()).toFile())
        assertTrue(index.isFile)
        assertTrue(output.resolve("documentation.json").readText().contains("greeting"))
        assertEquals("keep", output.resolve("notes.txt").readText())
    }

    fun testNewProjectRunsHelloWorldAndItsTestFromTheGutter() {
        val root = VfsUtil.findFileByIoFile(File(project.basePath!!), true)!!
        val settings = project.service<HglTestSettings>()
        val before = settings.state
        try {
            settings.loadState(HglTestSettings.Options(compilerOverride = System.getenv("HGL_TEST_CLI")))
            val starter = hglStarter()
            starter.generateProject(project, root, starter.createPeer().settings, module)

            val shared = project.service<HglBuildFile>()
            val initial = shared.read()!!
            val build = initial.copy(buildDir = "custom build", docsDir = "api reference")
            shared.save(build, initial)
            val index = HglGenerateDocs.generate(project, null, EmptyProgressIndicator()).toFile()
            assertEquals(root.toNioPath().resolve("api reference/index.html"), index.toPath())
            assertTrue(index.isFile)
            assertTrue(index.parentFile.resolve("documentation.json").readText().contains("greeting"))

            fun runAt(path: String, token: String): ProcessOutput {
                myFixture.configureFromExistingVirtualFile(root.findFileByRelativePath(path)!!)
                val leaf = myFixture.file.findElementAt(myFixture.file.text.indexOf(token))!!
                assertNotNull(HglRunGutter().getInfo(leaf))
                return runInConsole(runConfigurationAt(leaf))
            }

            val app = runAt("src/main.hgl", "application")
            assertEquals(app.stderr, 0, app.exitCode)
            assertEquals(listOf("1970-01-01T00:00:00.000001Z  \"Hello, world!\"",
                "ran hello.main on cpp: 1 publication"), app.stdout.trim().lines())
            assertTrue(root.toNioPath().resolve("custom build/build.ninja").toFile().isFile)
            val test = runAt("src/hello.hgl", "says_hello")
            assertEquals(test.stdout + test.stderr, 0, test.exitCode)
            assertTrue(test.stdout, Regex("passed\\s+executable\\s+hello::says_hello").containsMatchIn(test.stdout))
        } finally {
            settings.loadState(before)
        }
    }

    fun testBuildProducesIndexableCppForStarterProject() {
        val root = VfsUtil.findFileByIoFile(File(project.basePath!!), true)!!
        val starter = hglStarter()
        starter.generateProject(project, root, starter.createPeer().settings, module)
        val shared = project.service<HglBuildFile>()
        val initial = shared.read()!!
        val compiler = requireNotNull(System.getenv("HGL_TEST_CLI"))
        shared.save(initial.copy(compiler = compiler, buildDir = "native output"), initial)
        val database = HglBuildAction.build(project, null, EmptyProgressIndicator())
        assertEquals(root.toNioPath().resolve("native output/compile_commands.json"), database.toNioPath())
        val units = JsonParser.parseString(database.toNioPath().toFile().readText()).asJsonArray
            .map { File(it.asJsonObject["file"].asString).name }.toSet()
        val expected = setOf("mod-hello.cpp", "app-hello-main.cpp")
        assertTrue(units.toString(), units.containsAll(expected))
        project.getService(HglCompilationDatabaseImporter::class.java)?.let { importer ->
            Registry.get("external.system.auto.import.disabled").setValue(false, testRootDisposable)
            ExternalProjectsManagerImpl.getInstance(project).init()
            importer.load(database)
            PlatformTestUtil.waitWithEventsDispatching("CLion did not import the starter's generated C++", {
                importedCppFiles(project, database).containsAll(expected)
            }, 60)
        }
    }

    private fun runInConsole(configuration: HglRunConfiguration, inspect: ((SMTRunnerConsoleView) -> Unit)? = null): ProcessOutput {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(executor, configuration).build()
        val result = configuration.getState(executor, environment).execute(executor, environment.runner)
        val handler = result.processHandler!!
        try {
            val output = ProcessOutput()
            handler.addProcessListener(CapturingProcessAdapter(output))
            handler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("hgl timed out", { handler.isProcessTerminated }, 150)
            inspect?.invoke(result.executionConsole as SMTRunnerConsoleView)
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
