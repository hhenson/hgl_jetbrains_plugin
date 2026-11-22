package io.github.hhenson.hgl.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.platform.dap.DapProcessStarter
import com.intellij.platform.dap.DapStartRequest
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import io.github.hhenson.hgl.run.HglApplicationType
import io.github.hhenson.hgl.run.HglRunConfiguration
import io.github.hhenson.hgl.run.HglScopedTestConfiguration
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class HglDebugRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "HglDebugApplication"
    override fun canRun(executorId: String, profile: RunProfile) = executorId == DefaultDebugExecutor.EXECUTOR_ID &&
        (profile is HglRunConfiguration || profile is HglScopedTestConfiguration)

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val result = AsyncPromise<RunContentDescriptor?>()
        val continuation = environment.getUserData(NEXT)
        if (continuation != null) {
            environment.putUserData(NEXT, null)
            launch(environment, continuation, result)
            return result
        }
        val profile = environment.runProfile
        val testRun = profile !is HglRunConfiguration || profile.type !is HglApplicationType
        object : Task.Backgroundable(environment.project, "Build ${profile.name} for debugging", true) {
            private var directory: Path? = null
            private lateinit var plan: HglDebugPlan

            override fun run(indicator: ProgressIndicator) {
                val workspace = Files.createTempDirectory("hgl-debug-")
                directory = workspace
                val command = when (profile) {
                    is HglRunConfiguration -> profile.commandLine(workspace)
                    is HglScopedTestConfiguration -> profile.commandLine(workspace)
                    else -> error("Unknown HGL run configuration")
                }
                val build = CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator)
                indicator.checkCanceled()
                if (build.exitCode != 0) {
                    val diagnostic = build.stderr.ifBlank { build.stdout }.trim()
                    if (testRun && "unexpected argument '--debug-build'" in diagnostic) {
                        throw ExecutionException("Debug Tests requires a compiler with hgl test --debug-build.\n$diagnostic")
                    }
                    throw ExecutionException(diagnostic)
                }
                plan = HglDebugPlan.parse(build.stdout, testRun)
            }

            override fun onSuccess() {
                if (plan.skipped.isNotEmpty()) notice(environment, "Skipped HGL debug cases", plan.skipped, NotificationType.WARNING)
                val workspace = requireNotNull(directory)
                if (plan.launches.isEmpty() || environment.project.isDisposed) {
                    if (plan.launches.isEmpty() && plan.skipped.isEmpty()) notice(environment, "No HGL tests selected", emptyList())
                    clean(workspace)
                    result.setResult(null)
                } else launch(environment, Sequence(workspace, plan.launches), result)
            }

            override fun onCancel() {
                directory?.let(::clean)
                result.cancel()
            }

            override fun onThrowable(error: Throwable) {
                directory?.let(::clean)
                result.setError(error)
            }
        }.queue()
        return result
    }

    private fun launch(environment: ExecutionEnvironment, sequence: Sequence, result: AsyncPromise<RunContentDescriptor?>) {
        val current = sequence.launches[sequence.index]
        try {
            // LLDB starts the executable. The ordinary Run state would execute the tests again.
            val starter = DapProcessStarter(environment, environment.executor, RunProfileState { _, _ -> null },
                HglAdapterId, DapStartRequest.Launch, current.arguments)
            val builder = XDebuggerManager.getInstance(environment.project).newSessionBuilder(starter).environment(environment)
            current.module?.let { builder.sessionName("${environment.runProfile.name} · $it") }
            val session = builder.startSession()
            val stopped = AtomicBoolean()
            val listener = object : XDebugSessionListener {
                override fun sessionStopped() {
                    if (!stopped.compareAndSet(false, true)) return
                    val file = current.resultsFile
                    if (file == null) {
                        clean(sequence.workspace)
                        return
                    }
                    AppExecutorUtil.getAppExecutorService().execute {
                        val lines = try { Files.readAllLines(file) } catch (_: IOException) { emptyList() }
                        val outcomes = HglDebugResults(current.tests, lines)
                        ApplicationManager.getApplication().invokeLater {
                            if (environment.project.isDisposed) clean(sequence.workspace)
                            else finished(environment, sequence, outcomes)
                        }
                    }
                }
            }
            session.session.addSessionListener(listener)
            if (session.session.isStopped) listener.sessionStopped()
            result.setResult(session.runContentDescriptor)
        } catch (error: Exception) {
            clean(sequence.workspace)
            result.setError(error)
        }
    }

    private fun finished(environment: ExecutionEnvironment, sequence: Sequence, outcomes: HglDebugResults) {
        val current = sequence.launches[sequence.index]
        when {
            !outcomes.complete -> notice(environment, "HGL test debugging stopped in ${current.module}",
                outcomes.failures, NotificationType.WARNING)
            outcomes.failures.isNotEmpty() -> notice(environment, "HGL test failures in ${current.module}",
                outcomes.failures, NotificationType.ERROR)
            else -> notice(environment, "Passed all HGL tests in ${current.module}", emptyList())
        }
        val next = sequence.index + 1
        if (!outcomes.complete || next == sequence.launches.size) {
            clean(sequence.workspace)
            return
        }
        try {
            val env = ExecutionEnvironmentBuilder(environment).contentToReuse(null).build()
            env.putUserData(NEXT, sequence.copy(index = next))
            env.runner.execute(env)
        } catch (error: Exception) {
            clean(sequence.workspace)
            notice(environment, "Could not start the next HGL debug session", listOf(error.toString()), NotificationType.ERROR)
        }
    }

    private data class Sequence(val workspace: Path, val launches: List<HglDebugLaunch>, val index: Int = 0)

    private companion object {
        val NEXT = Key.create<Sequence>("hgl.debug.nextModule")
        fun clean(path: Path) { AppExecutorUtil.getAppExecutorService().execute { NioFiles.deleteRecursively(path) } }
        fun notice(env: ExecutionEnvironment, title: String, details: List<String>, type: NotificationType = NotificationType.INFORMATION) {
            NotificationGroupManager.getInstance().getNotificationGroup("HGL compiler").createNotification(
                StringUtil.escapeXmlEntities(title), details.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }, type,
            ).notify(env.project)
        }
    }
}
