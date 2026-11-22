package io.github.hhenson.hgl

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentManager
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
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
import io.github.hhenson.hgl.run.HglScopedTestConfiguration
import io.github.hhenson.hgl.run.HglScopedTestType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class HglDebugTestSuiteTest : HglEditorFixture() {
    private val descriptor = LightProjectDescriptor()
    override fun getProjectDescriptor() = descriptor

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
        project.service<HglBuildFile>().save(HglBuild(compiler = System.getenv("HGL_TEST_DEBUG_CLI")), null)
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
                val message = if (i == 0) "HGL test failures in alpha" else "Passed all HGL tests in beta"
                PlatformTestUtil.waitWithEventsDispatching("Missing outcome: $message", { message in messages }, 15)
                RunContentManager.getInstance(project).removeRunContent(executor, session.runContentDescriptor)
            }
            PlatformTestUtil.waitWithEventsDispatching("Missing module outcomes $messages", {
                "HGL test failures in alpha" in messages && "Passed all HGL tests in beta" in messages
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
