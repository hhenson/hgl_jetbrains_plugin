package io.github.hhenson.hgl

import com.google.gson.JsonParser
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.CapturingProcessHandler
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
import io.github.hhenson.hgl.run.HglProjectTargets
import com.intellij.execution.RunManager
import io.github.hhenson.hgl.run.HglRunGutter
import io.github.hhenson.hgl.run.HglRunTestsAction
import io.github.hhenson.hgl.run.HglRunIntegrationTestsAction
import io.github.hhenson.hgl.run.hglCommandLine
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
        val releaseCommands = database.toNioPath().toFile().readText()
        val debugDatabase = HglBuildAction.build(project, null, EmptyProgressIndicator(), debug = true)
        assertEquals(root.toNioPath().resolve("native output/debug/compile_commands.json"), debugDatabase.toNioPath())
        val debugCommands = debugDatabase.toNioPath().toFile().readText()
        assertTrue(debugCommands, "-O0" in debugCommands && "-g" in debugCommands)
        assertEquals(releaseCommands, database.toNioPath().toFile().readText())
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

    fun testProjectFolderRunsOrdinaryTestsRecursivelyInTheResultsTree() {
        val base = File(project.basePath!!)
        val src = base.resolve("src").apply { mkdirs() }
        src.resolve("outer.hgl").writeText("module outer\ntest first { assert true }")
        src.resolve("nested").apply { mkdir() }.resolve("inner.hgl").writeText(
            "module inner\ntest second { assert true }\nintegration_test live { assert false }")
        val shared = project.service<HglBuildFile>()
        shared.save(HglBuild(compiler = System.getenv("HGL_TEST_CLI")), null)
        val folder = VfsUtil.findFileByIoFile(src, true)!!
        val configuration = HglRunTestsAction().settingsFor(project, folder).configuration
        val output = runInConsole(configuration) { view ->
            val root = view.resultsViewer.testsRootNode
            PlatformTestUtil.waitWithEventsDispatching("missing ordinary test results", { root.isFinal && root.children.size == 2 }, 10)
            assertEquals(setOf("outer::first", "inner::second"), root.children.map { it.name }.toSet())
            assertTrue(root.children.all { it.isPassed })
        }
        assertEquals(output.stdout + output.stderr, 0, output.exitCode)
    }

    fun testScopedActionsUseDependenciesAndSeparateOrdinaryAndIntegrationCases() {
        val base = File(project.basePath!!)
        fun source(path: String, text: String) = base.resolve(path).apply {
            parentFile.mkdirs()
            writeText(text.trimIndent())
        }
        source("support/value.hgl", "module support\nexport const fn one() -> i64 => 1")
        // These deliberately failing neighbours must never appear in the selected results.
        source("src/unrelated.hgl", """
            module elsewhere
            test bad { assert false }
            integration_test also_bad { assert false }
            # expect-error(type, "type.mismatch")
            const fn wrong() -> i64 => true
        """)
        val file = source("src/chosen/cases.hgl", """
            module features
            use support::{one}
            test ordinary { assert one() == 1 }
            integration_test live { assert one() == 1 }
        """)
        source("src/chosen/nested/cases.hgl", "module nested\ntest ordinary { assert true }\nintegration_test live { assert true }")
        val preview = System.getenv("HGL_TEST_SCOPE_CLI")
        val compiler = preview ?: requireNotNull(System.getenv("HGL_TEST_CLI"))
        val help = CapturingProcessHandler(hglCommandLine(compiler).withParameters("test", "--help")).runProcess()
        assertEquals(help.stderr, 0, help.exitCode)
        val supportsSelection = "--test-path" in help.stdout && "--integration-only" in help.stdout
        if (preview != null) assertTrue("The selected compiler must support both test filters", supportsSelection)
        project.service<HglBuildFile>().save(HglBuild(compiler = compiler, sourceRoots = listOf("src", "support")), null)
        val selected = VfsUtil.findFileByIoFile(file, true)!!
        for (target in listOf(selected, selected.parent)) {
            for ((action, test) in listOf(HglRunTestsAction() to "ordinary", HglRunIntegrationTestsAction() to "live")) {
                val settings = action.settingsFor(project, target)
                val output = runInConsole(settings.configuration) { view ->
                    val root = view.resultsViewer.testsRootNode
                    PlatformTestUtil.waitWithEventsDispatching("missing scope results", { root.isFinal && root.children.isNotEmpty() }, 10)
                    if (supportsSelection) {
                        val modules = if (target.isDirectory) listOf("features", "nested") else listOf("features")
                        assertEquals(modules.map { "${it}::$test" }.toSet(), root.children.map { it.name }.toSet())
                        assertTrue(root.children.all { it.isPassed })
                    } else {
                        val error = root.children.single()
                        assertTrue(error.isDefect)
                        assertTrue(error.errorMessage.orEmpty().contains("requires an HGL compiler with hgl test --test-path"))
                    }
                }
                assertEquals(output.stdout + output.stderr, if (supportsSelection) 0 else 2, output.exitCode)
            }
        }
    }

    fun testToolbarApplicationsModulePartsAndAllTests() {
        val root = File(project.basePath!!).resolve("src").apply { mkdirs() }
        fun source(path: String, text: String) {
            val file = root.resolve(path)
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        source("primary.hgl", "module prices\nexport fn emit() -> i64 { yield 1s: 7 }\ntest ordinary { assert true }")
        source("nested/parts.hgl", "module prices part extra\ntest extra_check { assert true }\nintegration_test live { assert true }")
        source("other.hgl", "module other\ntest ordinary { assert true }\nintegration_test live { assert true }")
        for (app in listOf("demo.east", "demo.west")) {
            source("$app.hgl", "application $app\nuse prices::{emit}\neval emit()")
        }
        val shared = project.service<HglBuildFile>()
        val compiler = System.getenv("HGL_TEST_SCOPE_CLI") ?: requireNotNull(System.getenv("HGL_TEST_CLI"))
        shared.save(HglBuild(compiler = compiler), null)
        val targets = project.service<HglProjectTargets>()
        targets.start()
        val manager = RunManager.getInstance(project)
        PlatformTestUtil.waitWithEventsDispatching("Toolbar did not discover both applications", {
            manager.allSettings.any { it.name == "demo.west" }
        }, 15)
        fun target(name: String) = manager.allSettings.singleOrNull { it.name == name }?.configuration
            ?: error("Missing $name among ${manager.allSettings.map { it.name }}")
        for (app in listOf("demo.east", "demo.west")) {
            val config = target(app)
            val result = runInConsole(config)
            assertEquals(result.stdout + result.stderr, 0, result.exitCode)
            assertTrue(result.stdout, result.stdout.contains("ran $app on cpp: 1 publication"))
        }
        val groups = mapOf(
            "prices — Tests" to setOf("prices::ordinary", "prices::extra_check"),
            "prices — Integration Tests" to setOf("prices::live"),
            "All HGL Tests" to setOf("prices::ordinary", "prices::extra_check", "other::ordinary"),
            "All HGL Integration Tests" to setOf("prices::live", "other::live"),
        )
        val supportsModuleTests = "--test-path" in CapturingProcessHandler(hglCommandLine(compiler)
            .withParameters("test", "--help")).runProcess().stdout
        if (System.getenv("HGL_TEST_SCOPE_CLI") != null) assertTrue(supportsModuleTests)
        for ((name, expected) in groups) {
            val configuration = target(name)
            val supported = supportsModuleTests || name == "All HGL Tests"
            val result = runInConsole(configuration) { view ->
                val tree = view.resultsViewer.testsRootNode
                PlatformTestUtil.waitWithEventsDispatching("missing toolbar results", { tree.isFinal && tree.children.isNotEmpty() }, 10)
                if (supported) assertEquals(expected, tree.children.map { it.name }.toSet())
                else assertTrue(tree.children.single().errorMessage.orEmpty().contains("requires an HGL compiler"))
            }
            assertEquals(result.stdout + result.stderr, if (supported) 0 else 2, result.exitCode)
        }
    }

    private fun runInConsole(configuration: RunConfiguration, inspect: ((SMTRunnerConsoleView) -> Unit)? = null): ProcessOutput {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(executor, configuration).build()
        val result = configuration.getState(executor, environment)!!.execute(executor, environment.runner)!!
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
