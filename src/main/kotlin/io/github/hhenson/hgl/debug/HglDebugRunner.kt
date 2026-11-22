package io.github.hhenson.hgl.debug

import com.google.gson.JsonParser
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.platform.dap.DapProcessStarter
import com.intellij.platform.dap.DapStartRequest
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import io.github.hhenson.hgl.run.HglApplicationType
import io.github.hhenson.hgl.run.HglRunConfiguration
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.nio.file.Files
import java.nio.file.Path

class HglDebugRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "HglDebugApplication"
    override fun canRun(executorId: String, profile: RunProfile) =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is HglRunConfiguration && profile.type is HglApplicationType

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val result = AsyncPromise<RunContentDescriptor?>()
        val configuration = environment.runProfile as HglRunConfiguration
        object : Task.Backgroundable(environment.project, "Build ${configuration.selector} for debugging", true) {
            private var directory: Path? = null
            private lateinit var arguments: Map<String, Any>

            override fun run(indicator: ProgressIndicator) {
                val workspace = Files.createTempDirectory("hgl-debug-")
                directory = workspace
                val build = CapturingProcessHandler(configuration.commandLine(workspace)).runProcessWithProgressIndicator(indicator)
                indicator.checkCanceled()
                if (build.exitCode != 0) throw ExecutionException(build.stderr.ifBlank { build.stdout }.trim())
                arguments = launchArguments(build.stdout)
            }

            override fun onSuccess() {
                try {
                    // LLDB launches the prepared executable. The normal Run state would start a second copy.
                    val starter = DapProcessStarter(
                        environment, environment.executor, RunProfileState { _, _ -> null },
                        HglAdapterId, DapStartRequest.Launch, arguments,
                    )
                    val session = XDebuggerManager.getInstance(project).newSessionBuilder(starter)
                        .environment(environment).startSession()
                    session.session.addSessionListener(object : XDebugSessionListener {
                        override fun sessionStopped() { cleanup() }
                    })
                    result.setResult(session.runContentDescriptor)
                } catch (e: Exception) {
                    onThrowable(e)
                }
            }

            override fun onCancel() {
                cleanup()
                result.cancel()
            }

            override fun onThrowable(error: Throwable) {
                cleanup()
                result.setError(error)
            }

            private fun cleanup() {
                val workspace = directory ?: return
                AppExecutorUtil.getAppExecutorService().execute { NioFiles.deleteRecursively(workspace) }
            }
        }.queue()
        return result
    }
}

private fun launchArguments(report: String): Map<String, Any> {
    try {
        val data = JsonParser.parseString(report).asJsonObject
        if (data.get("version")?.asInt != 1) throw ExecutionException("Unsupported HGL debug launch version.")
        return mapOf(
            "program" to data["executable"].asString,
            "args" to data["arguments"].asJsonArray.map { it.asString },
            "cwd" to data["workingDirectory"].asString,
            "env" to data["environment"].asJsonObject.entrySet().associate { it.key to it.value.asString },
        )
    } catch (e: RuntimeException) {
        throw ExecutionException("Invalid debug launch description from the HGL compiler.", e)
    }
}
