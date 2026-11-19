package io.github.hhenson.hgl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import io.github.hhenson.hgl.diagnostics.HglCheckService
import io.github.hhenson.hgl.diagnostics.runCheck
import io.github.hhenson.hgl.run.HglTestSettings
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
import java.nio.file.Path

class HglCheckOnSaveTest : HglEditorFixture() {
    private val descriptor = LightProjectDescriptor()
    override fun getProjectDescriptor() = descriptor

    fun testSavingProjectSourcesPublishesAndClearsCrossFileDiagnostics() {
        val dir = Files.createDirectory(Path.of(project.basePath!!, "program sources"))
        Files.writeString(dir.resolve("first.hgl"), "module m\nfn scale() {}\n")
        Files.writeString(dir.resolve("second.hgl"), "module m part second\r\n/*😀*/ fn other() {}\r\n")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
        PsiTestUtil.addContentRoot(module, root)
        val first = root.findChild("first.hgl")!!
        val second = root.findChild("second.hgl")!!
        FileEditorManager.getInstance(project).openFile(second, true)
        myFixture.configureFromExistingVirtualFile(second)
        val manager = FileDocumentManager.getInstance()
        val document = manager.getDocument(second)!!
        val settings = project.service<HglTestSettings>()
        val previous = settings.state
        val service = project.service<HglCheckService>()
        try {
            settings.loadState(HglTestSettings.Options(System.getenv("HGL_TEST_CLI"), dir.fileName.toString()))
            WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text.replace("other", "scale")) }
            manager.saveDocument(document)
            PlatformTestUtil.waitWithEventsDispatching("missing saved compiler diagnostic", {
                service.diagnostics(second, document).any { "name.duplicate" in it.message }
            }, 15)
            val error = myFixture.doHighlighting(HighlightSeverity.ERROR).single { "name.duplicate" in it.description.orEmpty() }
            assertEquals(document.text.indexOf("scale"), error.startOffset)
            assertEquals("scale", document.text.substring(error.startOffset, error.endOffset))
            assertTrue(error.toolTip, "first.hgl:2:4" in error.toolTip.orEmpty() && "first declared here" in error.toolTip.orEmpty())

            // Only the first file changes. Saving it must clear the second file's old underline.
            val other = manager.getDocument(first)!!
            WriteCommandAction.runWriteCommandAction(project) { other.setText(other.text.replace("scale", "first")) }
            manager.saveDocument(other)
            PlatformTestUtil.waitWithEventsDispatching("stale cross-file diagnostic", {
                service.diagnostics(second, document).isEmpty()
            }, 15)
            assertFalse(myFixture.doHighlighting().any { "name.duplicate" in it.description.orEmpty() })

            WriteCommandAction.runWriteCommandAction(project) {
                document.setText("module m part second\nfn dormant(a: f64) -> f64 {\n    when modified([]) { return a }\n}\n")
            }
            manager.saveDocument(document)
            PlatformTestUtil.waitWithEventsDispatching("missing warning-only report", {
                service.diagnostics(second, document).any { "node.no_activation" in it.message }
            }, 15)
            val warning = myFixture.doHighlighting().single { "node.no_activation" in it.description.orEmpty() }
            assertEquals(HighlightSeverity.WARNING, warning.severity)
        } finally {
            service.reset()
            settings.loadState(previous)
        }
    }

    fun testNewSaveStopsTheOldProcessAndUnsavedEditsHideItsResults() {
        val dir = Files.createDirectory(Path.of(project.basePath!!, "fallback"))
        val source = Files.writeString(dir.resolve("m.hgl"), "module m\nfn f() {}\n")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
        PsiTestUtil.addContentRoot(module, root)
        val file = root.findChild("m.hgl")!!
        myFixture.configureFromExistingVirtualFile(file)
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val settings = project.service<HglTestSettings>()
        val before = settings.state
        val service = project.service<HglCheckService>()
        val release = CompletableDeferred<Unit>()
        try {
            settings.loadState(HglTestSettings.Options(System.getenv("HGL_TEST_CLI")))
            val pidFile = dir.resolve("pid")
            val old = service.saved(file) {
                runCheck(GeneralCommandLine(System.getenv("HGL_HGRAPH_PYTHON"), "-c",
                    "import os, pathlib, sys, time; pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(60)",
                    pidFile.toString()))
            }!!
            PlatformTestUtil.waitWithEventsDispatching("first check never started", {
                Files.exists(pidFile) && Files.readString(pidFile).isNotEmpty()
            }, 15)
            val pid = Files.readString(pidFile).toLong()
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "fn f() {}\n") }
            FileDocumentManager.getInstance().saveDocument(document)
            PlatformTestUtil.waitWithEventsDispatching("new save did not replace the running check", {
                old.isCancelled && !ProcessHandle.of(pid).map { it.isAlive }.orElse(false) &&
                    service.diagnostics(file, document).any { "name.duplicate" in it.message }
            }, 15)

            // Hold a real report in flight while the user edits the buffer without saving.
            val checked = CompletableDeferred<Unit>()
            val slow = service.saved(file) { command ->
                val output = runCheck(command)
                checked.complete(Unit)
                release.await()
                output
            }!!
            PlatformTestUtil.waitWithEventsDispatching("check did not read saved source", { checked.isCompleted }, 15)
            WriteCommandAction.runWriteCommandAction(project) { document.setText("module m\n") }
            release.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("slow result did not arrive", { slow.isCompleted }, 15)
            assertTrue(service.diagnostics(file, document).isEmpty())
            assertFalse(myFixture.doHighlighting().any { "name.duplicate" in it.description.orEmpty() })
            assertTrue(Files.readString(source).contains("fn f()"))
        } finally {
            release.complete(Unit)
            service.reset()
            settings.loadState(before)
        }
    }

    fun testFixingABadRootShowsTheBundledCoreLocationOnTheNextSave() {
        val dir = Files.createDirectory(Path.of(project.basePath!!, "sources"))
        val source = Files.writeString(dir.resolve("demo.hgl"), "module demo\n")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
        PsiTestUtil.addContentRoot(module, root)
        val file = root.findChild(source.fileName.toString())!!
        myFixture.configureFromExistingVirtualFile(file)
        val document = myFixture.editor.document
        val settings = project.service<HglTestSettings>()
        val previous = settings.state
        val service = project.service<HglCheckService>()
        val notifications = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "HGL compiler") notifications.add(notification)
            }
        })
        try {
            settings.loadState(HglTestSettings.Options(System.getenv("HGL_TEST_CLI"), "sources\nmissing-root"))
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(document.textLength, "const fn log_info(logger: Logger, message: str) {}\n")
            }
            FileDocumentManager.getInstance().saveDocument(document)
            PlatformTestUtil.waitWithEventsDispatching("missing compiler invocation notification", { notifications.isNotEmpty() }, 15)
            val failure = notifications.single()
            assertEquals("HGL check on save failed", failure.title)
            assertTrue(failure.content, "hgl: cannot read" in failure.content && "missing-root" in failure.content)
            assertTrue(service.diagnostics(file, document).isEmpty())

            settings.state.roots = "sources"
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "\n") }
            FileDocumentManager.getInstance().saveDocument(document)
            PlatformTestUtil.waitWithEventsDispatching("missing prelude conflict", {
                service.diagnostics(file, document).any { "name.duplicate" in it.message }
            }, 15)
            val conflict = myFixture.doHighlighting(HighlightSeverity.ERROR).single { "name.duplicate" in it.description.orEmpty() }
            assertEquals("log_info", document.text.substring(conflict.startOffset, conflict.endOffset))
            val tooltip = conflict.toolTip.orEmpty()
            assertTrue(tooltip, Regex("""<br>&lt;core&gt;/core/core\.hgl:\d+:\d+ — declared here""").containsMatchIn(tooltip))
        } finally {
            notifications.forEach { it.expire() }
            service.reset()
            settings.loadState(previous)
        }
    }
}
