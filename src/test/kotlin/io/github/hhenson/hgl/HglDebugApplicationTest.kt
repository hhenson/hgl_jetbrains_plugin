package io.github.hhenson.hgl

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.terminal.TerminalExecutionConsole
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import io.github.hhenson.hgl.debug.HglDebugRunner
import io.github.hhenson.hgl.debug.HglLineBreakpoint
import io.github.hhenson.hgl.run.HglTestSettings
import java.nio.file.Files
import java.nio.file.Path

/** Exercise the gutter configuration with the installed IDE's real debug session and LLDB. */
class HglDebugApplicationTest : HglEditorFixture() {
    private val descriptor = LightProjectDescriptor()
    override fun getProjectDescriptor() = descriptor

    fun testDebugApplicationStopsOnHglAndPublishesBothTicks() {
        // LLDB resolves native frames on the other engine threads at a breakpoint.
        VfsRootAccess.allowRootAccess(testRootDisposable, "/usr/include")
        val source = """
            module demo.source
            fn ticks() -> i64 {
                yield 1s: 1
                yield 1s: 2
            }
            fn watch(value: i64) -> i64 {
                when {
                    return value
                }
            }
            export fn stream() -> i64 => watch(ticks())
        """.trimIndent()
        val line = source.lines().indexOfFirst { "return value" in it }
        val dir = Files.createDirectory(Path.of(project.basePath!!, "debug sources"))
        Files.writeString(dir.resolve("nodes.hgl"), source)
        Files.writeString(dir.resolve("entry.hgl"), "application demo.backtest\nuse demo.source::{stream}\neval stream()\n")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
        PsiTestUtil.addContentRoot(module, root)
        val nodes = root.findChild("nodes.hgl")!!
        myFixture.configureFromExistingVirtualFile(root.findChild("entry.hgl")!!)
        val config = runConfigurationAt(myFixture.file.findElementAt(0)!!)
        myFixture.configureFromExistingVirtualFile(nodes)
        val settings = project.service<HglTestSettings>()
        val before = settings.state
        val manager = XDebuggerManager.getInstance(project)
        val type = XDebuggerUtil.getInstance().findBreakpointType(HglLineBreakpoint::class.java)
        val breakpoint = WriteCommandAction.writeCommandAction(project).compute<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>, RuntimeException> {
            manager.breakpointManager.addLineBreakpoint(type, nodes.url, line, null)
        }
        try {
            settings.loadState(HglTestSettings.Options(System.getenv("HGL_TEST_CLI"), dir.toString()))
            val env = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), config).build()
            assertTrue(env.runner is HglDebugRunner)
            env.runner.execute(env)
            PlatformTestUtil.waitWithEventsDispatching("HGL breakpoint was not reached", {
                manager.debugSessions.singleOrNull()?.suspendContext?.activeExecutionStack?.topFrame?.sourcePosition?.file == nodes
            }, 150)
            val session = manager.debugSessions.single()
            assertTrue(session.isSuspended)
            assertEquals(line, session.suspendContext!!.activeExecutionStack!!.topFrame!!.sourcePosition!!.line)
            WriteCommandAction.runWriteCommandAction(project) { breakpoint.isEnabled = false }
            val console = session.consoleView as TerminalExecutionConsole
            val expected = listOf(
                "published 1970-01-01T00:00:01.000001Z 1",
                "published 1970-01-01T00:00:02.000001Z 2",
            )
            session.resume()
            PlatformTestUtil.waitWithEventsDispatching("Missing output after Continue", {
                console.terminalWidget.terminalTextBuffer.getScreenLines().contains("stopped")
            }, 30)
            val publications = console.terminalWidget.terminalTextBuffer.getScreenLines()
                .lineSequence().map(String::trim).filter { it.startsWith("published ") }.toList()
            assertEquals(expected, publications)
            PlatformTestUtil.waitWithEventsDispatching("Debug session did not finish", { session.isStopped }, 30)
            RunContentManager.getInstance(project).removeRunContent(env.executor, session.runContentDescriptor)
            println("HGL_DEBUG demo.backtest stopped at nodes.hgl:${line + 1}; continued: $publications")
        } finally {
            manager.debugSessions.forEach { it.stop() }
            WriteCommandAction.runWriteCommandAction(project) { manager.breakpointManager.removeBreakpoint(breakpoint) }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            FileEditorManager.getInstance(project).closeFile(nodes)
            settings.loadState(before)
        }
    }
}
