package io.github.hhenson.hgl

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import io.github.hhenson.hgl.debug.HglLineBreakpoint
import io.github.hhenson.hgl.project.HglBuild
import io.github.hhenson.hgl.project.HglBuildFile
import io.github.hhenson.hgl.run.HglProjectTargets
import io.github.hhenson.hgl.run.HglScopedTestConfiguration
import io.github.hhenson.hgl.run.HglScopedTestType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class HglDebugTestSuiteTest : HglEditorFixture() {
    private val descriptor = LightProjectDescriptor()
    override fun getProjectDescriptor() = descriptor

    fun testAssertionBreakpointsForNamedModuleAndProjectSelections() {
        VfsRootAccess.allowRootAccess(testRootDisposable, "/usr/include")
        val dir = Files.createDirectory(Path.of(project.basePath!!, "src"))
        // An assertion is on its own line so a stop on the test declaration cannot pass.
        val sources = mapOf("alpha" to "alpha", "beta" to "beta", "extra" to "beta part extra")
        for ((file, header) in sources) Files.writeString(dir.resolve("$file.hgl"), """
            module $header
            test ${file}_unit {
                assert true
            }
            integration_test ${file}_live {
                assert true
            }
        """.trimIndent())
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
        PsiTestUtil.addContentRoot(module, root)
        project.service<HglBuildFile>().save(HglBuild(compiler = System.getenv("HGL_TEST_CLI")), null)
        project.service<HglProjectTargets>().start()
        val runManager = RunManager.getInstance(project)
        PlatformTestUtil.waitWithEventsDispatching("Missing beta suite configurations", {
            runManager.allSettings.any { it.name == "beta — Integration Tests" }
        }, 15)
        val manager = XDebuggerManager.getInstance(project)
        val type = XDebuggerUtil.getInstance().findBreakpointType(HglLineBreakpoint::class.java)
        val locations = mutableMapOf<Pair<VirtualFile, Int>, String>()
        for (file in sources.keys) {
            val source = root.findChild("$file.hgl")!!
            locations[source to 2] = "${file}_unit"
            locations[source to 5] = "${file}_live"
        }
        val breaks = WriteCommandAction.writeCommandAction(project)
            .compute<Map<String, XLineBreakpoint<*>>, RuntimeException> {
                locations.entries.associate { (position, name) ->
                    name to manager.breakpointManager.addLineBreakpoint(type, position.first.url, position.second, null)
                }
            }
        val outcomes = CopyOnWriteArrayList<String>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "HGL compiler") outcomes.add(notification.title.ifEmpty { notification.content })
            }
        })
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        fun verify(config: RunConfiguration, expected: Set<String>, modules: List<String>) {
            outcomes.clear()
            WriteCommandAction.runWriteCommandAction(project) { breaks.values.forEach { it.isEnabled = true } }
            val env = ExecutionEnvironmentBuilder.create(executor, config).build()
            env.runner.execute(env)
            val hits = mutableSetOf<String>()
            val sessions = mutableSetOf<com.intellij.xdebugger.XDebugSession>()
            val expectedReports = modules.map { "Passed executable HGL tests in $it" }
            PlatformTestUtil.waitWithEventsDispatching("Missing assertion stop or result for ${config.name}", {
                for (session in manager.debugSessions.filter { it.isSuspended }) {
                    val pos = session.suspendContext?.activeExecutionStack?.topFrame?.sourcePosition ?: continue
                    val name = locations[pos.file to pos.line] ?: error(
                        "${config.name} suspended at ${pos.file.name}:${pos.line + 1} instead of an HGL assertion")
                    val breakpoint = breaks.getValue(name)
                    if (!breakpoint.isEnabled) continue // Resume may still be in flight.
                    assertTrue("${config.name} reached unselected test $name", name in expected)
                    hits.add(name)
                    sessions.add(session)
                    println("HGL_ASSERTION_BREAKPOINT ${config.name} $name ${pos.file.name}:${pos.line + 1}")
                    WriteCommandAction.runWriteCommandAction(project) { breakpoint.isEnabled = false }
                    session.resume()
                }
                assertTrue("Unexpected outcome for ${config.name}: $outcomes", outcomes.all { it in expectedReports })
                outcomes.contains(expectedReports.last())
            }, 120)
            assertEquals(config.name, expected, hits)
            assertEquals(config.name, expectedReports, outcomes.toList())
            for (session in sessions) RunContentManager.getInstance(project).removeRunContent(executor, session.runContentDescriptor)
        }
        try {
            val source = root.findChild("beta.hgl")!!
            myFixture.configureFromExistingVirtualFile(source)
            for ((kind, suffix) in listOf("Tests" to "unit", "Integration Tests" to "live")) {
                val name = "beta_$suffix"
                val leaf = myFixture.file.findElementAt(myFixture.file.text.indexOf(name))!!
                verify(runConfigurationAt(leaf), setOf(name), listOf("beta"))
                val moduleSuite = runManager.allSettings.single { it.name == "beta — $kind" }.configuration
                verify(moduleSuite, setOf(name, "extra_$suffix"), listOf("beta"))
                val projectSuite = runManager.allSettings.single { it.name == "All HGL $kind" }.configuration
                verify(projectSuite, setOf("alpha_$suffix", name, "extra_$suffix"), listOf("alpha", "beta"))
            }
        } finally {
            manager.debugSessions.forEach { it.stop() }
            WriteCommandAction.runWriteCommandAction(project) { breaks.values.forEach(manager.breakpointManager::removeBreakpoint) }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }

    fun testStepAcrossAssertionsAfterCompilerCheckedFailure() {
        VfsRootAccess.allowRootAccess(testRootDisposable, "/usr/include")
        val dir = Files.createDirectory(Path.of(project.basePath!!, "src"))
        val path = dir.resolve("verdicts.hgl")
        Files.writeString(path, """
            module verdicts
            integration_test missing_error {
                # expect-error(type, "type.mismatch")
                assert true
            }
            @timeout(100ms)
            integration_test paused {
                assert true
                assert false
            }
            integration_test subsequent { assert true }
        """.trimIndent())
        val source = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        PsiTestUtil.addContentRoot(module, source.parent)
        project.service<HglBuildFile>().save(HglBuild(compiler = System.getenv("HGL_TEST_CLI")), null)
        val errors = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "HGL compiler" && notification.type == NotificationType.ERROR) errors.add(notification)
            }
        })
        val manager = XDebuggerManager.getInstance(project)
        val type = XDebuggerUtil.getInstance().findBreakpointType(HglLineBreakpoint::class.java)
        val breakpoint = WriteCommandAction.writeCommandAction(project).compute<XLineBreakpoint<*>, RuntimeException> {
            manager.breakpointManager.addLineBreakpoint(type, source.url, 7, null)
        }
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        try {
            val config = HglScopedTestConfiguration(project, HglScopedTestType()).apply {
                allModules = true
                integrationOnly = true
                testPath = "."
            }
            val env = ExecutionEnvironmentBuilder.create(executor, config).build()
            env.runner.execute(env)
            PlatformTestUtil.waitWithEventsDispatching("Missing assertion breakpoint", {
                manager.debugSessions.any { it.isSuspended && it.currentPosition?.file == source && it.currentPosition?.line == 7 }
            }, 90)
            val session = manager.debugSessions.single { it.isSuspended }
            val rejection = errors.single { it.title == "Failed HGL rejection cases" }.content
            assertTrue(rejection, "verdicts::missing_error" in rejection && "type.mismatch on line 4" in rejection)
            // Hold the first assertion beyond the integration test's wall-clock limit.
            val heldUntil = System.nanoTime() + 250_000_000L
            PlatformTestUtil.waitWithEventsDispatching("Hold integration breakpoint", { System.nanoTime() >= heldUntil }, 3)
            WriteCommandAction.runWriteCommandAction(project) { breakpoint.isEnabled = false }
            session.stepOver(false)
            PlatformTestUtil.waitWithEventsDispatching("Step Over missed the next HGL assertion", {
                val position = session.suspendContext?.activeExecutionStack?.topFrame?.sourcePosition
                if (!session.isSuspended || position == null) false
                else {
                    assertEquals("Step Over left the HGL source", source, position.file)
                    position.line == 8
                }
            }, 15)
            session.resume()
            PlatformTestUtil.waitWithEventsDispatching("Missing completed module result", {
                errors.any { it.title == "HGL test failures in verdicts" }
            }, 15)
            val failure = errors.single { it.title == "HGL test failures in verdicts" }.content
            assertTrue(failure, "verdicts::paused: failed 9 " in failure)
            assertFalse(failure, "timed out" in failure)
            assertFalse(failure, "subsequent" in failure)
            RunContentManager.getInstance(project).removeRunContent(executor, session.runContentDescriptor)
        } finally {
            manager.debugSessions.forEach { it.stop() }
            WriteCommandAction.runWriteCommandAction(project) { manager.breakpointManager.removeBreakpoint(breakpoint) }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }

    fun testContinueRunsTheNextModuleAndStopCancelsTheGroup() {
        VfsRootAccess.allowRootAccess(testRootDisposable, "/usr/include")
        val root = Files.createDirectory(Path.of(project.basePath!!, "src"))
        val modules = listOf("alpha", "beta")
        for (name in modules) Files.writeString(root.resolve("$name.hgl"), """
            module $name
            fn ticks() -> i64 {
                yield 0s: 7
            }
            test sample { assert eval ticks() == [${if (name == "alpha") 99 else 7}] }
            integration_test excluded { assert false }
        """.trimIndent())
        val folder = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        PsiTestUtil.addContentRoot(module, folder)
        project.service<HglBuildFile>().save(HglBuild(compiler = System.getenv("HGL_TEST_CLI")), null)
        val manager = XDebuggerManager.getInstance(project)
        val type = XDebuggerUtil.getInstance().findBreakpointType(HglLineBreakpoint::class.java)
        val files = modules.map { folder.findChild("$it.hgl")!! }
        val breaks = WriteCommandAction.writeCommandAction(project).compute<List<XLineBreakpoint<*>>, RuntimeException> {
            files.map { manager.breakpointManager.addLineBreakpoint(type, it.url, 2, null) }
        }
        val messages = CopyOnWriteArrayList<String>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                messages.add(notification.title.ifEmpty { notification.content })
                println("HGL_DEBUG_SUITE ${messages.last()} ${notification.content}")
            }
        })
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        val config = HglScopedTestConfiguration(project, HglScopedTestType()).apply {
            name = "All HGL Tests"
            allModules = true
            testPath = "."
        }
        fun start() {
            val env = ExecutionEnvironmentBuilder.create(executor, config).build()
            env.runner.execute(env)
        }
        fun at(index: Int): com.intellij.xdebugger.XDebugSession {
            fun suspended() = manager.debugSessions.firstOrNull {
                val pos = it.suspendContext?.activeExecutionStack?.topFrame?.sourcePosition
                it.isSuspended && pos?.file == files[index] && pos.line == 2
            }
            PlatformTestUtil.waitWithEventsDispatching("No HGL breakpoint in ${modules[index]}; notices $messages", {
                suspended() != null
            }, 75)
            return suspended()!!
        }
        try {
            start()
            for (i in modules.indices) {
                val session = at(i)
                WriteCommandAction.runWriteCommandAction(project) { breaks[i].isEnabled = false }
                session.resume()
                PlatformTestUtil.waitWithEventsDispatching("Module did not finish", { session.isStopped }, 30)
                val message = if (i == 0) "HGL test failures in alpha" else "Passed executable HGL tests in beta"
                PlatformTestUtil.waitWithEventsDispatching("Missing outcome: $message", { message in messages }, 15)
                RunContentManager.getInstance(project).removeRunContent(executor, session.runContentDescriptor)
            }
            PlatformTestUtil.waitWithEventsDispatching("Missing module outcomes $messages", {
                "HGL test failures in alpha" in messages && "Passed executable HGL tests in beta" in messages
            }, 15)

            messages.clear()
            WriteCommandAction.runWriteCommandAction(project) { breaks.forEach { it.isEnabled = true } }
            start()
            val first = at(0)
            first.stop()
            PlatformTestUtil.waitWithEventsDispatching("Stop did not cancel the group", {
                "HGL test debugging stopped in alpha" in messages
            }, 15)
            assertTrue(manager.debugSessions.all { it.isStopped })
            RunContentManager.getInstance(project).removeRunContent(executor, first.runContentDescriptor)
        } finally {
            manager.debugSessions.forEach { it.stop() }
            WriteCommandAction.runWriteCommandAction(project) { breaks.forEach(manager.breakpointManager::removeBreakpoint) }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }
}
