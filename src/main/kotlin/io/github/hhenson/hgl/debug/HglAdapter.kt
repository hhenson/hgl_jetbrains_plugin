package io.github.hhenson.hgl.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dap.*
import com.intellij.platform.dap.connection.CommandLineDebugAdapterHandle
import com.intellij.xdebugger.breakpoints.*
import io.github.hhenson.hgl.HglDebugSourceFileType
import io.github.hhenson.hgl.HglFileType
import java.nio.file.Files
import java.nio.file.Path

object HglAdapterId : DebugAdapterId("hgl-lldb", "HGL (LLDB)")

class HglLineBreakpoint : XLineBreakpointType<XBreakpointProperties<*>>("hgl-line", "HGL Line Breakpoints") {
    override fun canPutAt(file: VirtualFile, line: Int, project: Project) = file.fileType.let { it == HglFileType || it == HglDebugSourceFileType }
    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? = null
}

// The DAP descriptor requires an exception type. HGL only registers line breakpoints.
private abstract class HglExceptionBreakpoint :
    XBreakpointType<XBreakpoint<XBreakpointProperties<*>>, XBreakpointProperties<*>>("hgl-exception", "HGL Exceptions")

class HglAdapterProvider : DebugAdapterSupportProvider<HglAdapterId> {
    override val adapterId = HglAdapterId
    override fun createDebugAdapterDescriptor(project: Project) = object : DebugAdapterDescriptor<HglAdapterId>() {
        override val id = HglAdapterId
        override val breakpointsDescription = DapBreakpointsDescription(
            sourceBreakpointType = HglLineBreakpoint::class.java,
            exceptionBreakpointType = HglExceptionBreakpoint::class.java,
            functionBreakpointType = null,
        )
        override suspend fun launchDebugAdapter(environment: ExecutionEnvironment, executionResult: ExecutionResult?, sessionId: String) =
            CommandLineDebugAdapterHandle(lldbCommandLine())
    }
}

internal fun lldbCommandLine(isMac: Boolean = SystemInfo.isMac): GeneralCommandLine {
    // Apple Silicon IDE bundles contain LLDB.framework. Apple's developer tools supply the DAP executable.
    if (isMac) return GeneralCommandLine("/usr/bin/xcrun", "lldb-dap")

    val root = Path.of(PathManager.getBinPath(), "lldb")
    if (Files.isDirectory(root)) {
        Files.find(root, 9, { path, _ -> path.fileName.toString() == "lldb-dap" && Files.isExecutable(path) }).use { paths ->
            paths.findFirst().orElse(null)?.let { return GeneralCommandLine(it.toString()) }
        }
    }
    throw ExecutionException("This IDE installation has no bundled LLDB debug adapter.")
}
