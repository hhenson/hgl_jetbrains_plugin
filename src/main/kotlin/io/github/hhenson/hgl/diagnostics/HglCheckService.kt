package io.github.hhenson.hgl.diagnostics

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutput
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.github.hhenson.hgl.run.buildSettings
import io.github.hhenson.hgl.run.HglTestSettings
import io.github.hhenson.hgl.run.hglCommandLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

@Service(Service.Level.PROJECT)
class HglCheckService(private val project: Project, private val scope: CoroutineScope) {
    private data class Snapshot(val document: Long, val file: Long)
    private data class Findings(val stamp: Snapshot, val diagnostics: List<CheckDiagnostic>)

    // The annotator reads on background threads. Saves and result replacement are serialized on the EDT.
    private val findings = ConcurrentHashMap<VirtualFile, Findings>()
    private val running = mutableMapOf<List<Path>, Job>()
    private var lastFailure: String? = null

    internal fun diagnostics(file: VirtualFile, document: Document): List<CheckDiagnostic> {
        val result = findings[file] ?: return emptyList()
        return if (result.stamp == stamp(file, document)) result.diagnostics else emptyList()
    }

    private fun stamp(file: VirtualFile, document: Document) = Snapshot(document.modificationStamp, file.modificationStamp)

    internal fun saved(
        file: VirtualFile,
        execute: suspend (GeneralCommandLine) -> ProcessOutput = ::runCheck,
    ): Job? {
        val settings = project.service<HglTestSettings>().state
        if (!settings.checkOnSave || !TrustedProjects.isProjectTrusted(project)) return null
        val directory = project.basePath?.let(Path::of) ?: return null
        val build = try { project.buildSettings() } catch (error: IllegalArgumentException) {
            warn(error.localizedMessage)
            return null
        }
        val roots = try {
            build.sourceRoots.map { directory.resolve(it).normalize() }.ifEmpty { listOf(file.toNioPath()) }
        } catch (e: IllegalArgumentException) {
            warn(e.message ?: "Invalid HGL source root")
            return null
        }
        if (!inRoots(file, roots)) return null
        running.remove(roots)?.cancel()
        val executable = build.compiler
        val documents = FileDocumentManager.getInstance()
        val openFiles = FileEditorManager.getInstance(project).openFiles.toSet() + file
        val targets = openFiles.filter { it.isLocalHgl && inRoots(it, roots) }.mapNotNull { target ->
            val document = documents.getCachedDocument(target) ?: return@mapNotNull null
            if (documents.isDocumentUnsaved(document)) return@mapNotNull null
            target to stamp(target, document)
        }.toMap()

        // Save All can report every file in one set of roots. Only its latest check is useful.
        val job = scope.launch(Dispatchers.EDT) {
            delay(150)
            val result = try {
                checkRoots(directory, roots, executable, execute).also { lastFailure = null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn(e.message ?: "Invalid hgl check output")
                emptyMap()
            }
            publish(roots, targets, result)
        }
        running[roots] = job
        job.invokeOnCompletion {
            scope.launch(Dispatchers.EDT) { running.remove(roots, job) }
        }
        return job
    }

    private fun inRoots(file: VirtualFile, roots: List<Path>) = roots.any { file.toNioPath().startsWith(it) }

    private fun publish(roots: List<Path>, targets: Map<VirtualFile, Snapshot>, report: Map<Path, List<CheckDiagnostic>>) {
        val documents = FileDocumentManager.getInstance()
        // Retire findings for closed files too. A later reopen must not resurrect an earlier report.
        val affected = targets.keys + findings.keys.filter { !it.isValid || inRoots(it, roots) }
        for (file in affected) {
            val expected = targets[file]
            val document = if (file.isValid) documents.getCachedDocument(file) else null
            val current = if (document != null && expected != null && expected == stamp(file, document)
                && !documents.isDocumentUnsaved(document)) {
                report[file.toNioPath()]?.let { Findings(expected, it) }
            } else null
            val previous = if (current == null) findings.remove(file) else findings.put(file, current)
            if (previous != current) refresh(file)
        }
    }

    internal fun reset() {
        running.values.toList().forEach { it.cancel() }
        running.clear()
        lastFailure = null
        val affected = findings.keys.toList()
        findings.clear()
        affected.forEach(::refresh)
    }

    private fun refresh(file: VirtualFile) {
        if (!file.isValid) return
        PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it, this) }
    }

    private fun warn(message: String) {
        if (message == lastFailure) return
        lastFailure = message
        NotificationGroupManager.getInstance().getNotificationGroup("HGL compiler")
            .createNotification("HGL check on save failed", StringUtil.escapeXmlEntities(message), NotificationType.WARNING)
            .notify(project)
    }
}

private suspend fun checkRoots(
    directory: Path,
    roots: List<Path>,
    executable: String,
    execute: suspend (GeneralCommandLine) -> ProcessOutput,
): Map<Path, List<CheckDiagnostic>> = withContext(Dispatchers.IO) {
    val command = hglCommandLine(executable).withParameters("check", "--json", "--")
        .withWorkDirectory(directory.toFile()).withParameters(roots.map(Path::toString))
        .withCharset(Charsets.UTF_8)
    val output = execute(command)
    if (output.exitCode !in 0..1) {
        throw ExecutionException(output.stderr.trim().ifEmpty { "hgl check exited ${output.exitCode}" })
    }
    parseCheckReport(output.stdout, directory)
}

/** Cancellation stops the compiler and discards its buffered output. */
internal suspend fun runCheck(command: GeneralCommandLine): ProcessOutput {
    val handler = OSProcessHandler(command)
    return suspendCancellableCoroutine { continuation ->
        val output = ProcessOutput()
        handler.addProcessListener(object : CapturingProcessAdapter(output) {
            override fun processTerminated(event: ProcessEvent) {
                super.processTerminated(event)
                continuation.resume(output)
            }
        })
        continuation.invokeOnCancellation { handler.destroyProcess() }
        handler.startNotify()
    }
}
