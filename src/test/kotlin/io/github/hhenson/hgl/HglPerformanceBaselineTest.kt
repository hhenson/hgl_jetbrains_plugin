package io.github.hhenson.hgl

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.hhenson.hgl.reference.HglModuleIndex
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.readBytes
import kotlin.system.measureNanoTime

/** Run with -PhglBenchmarkWorkspace pointing to a recursive checkout of hgl-project. */
class HglPerformanceBaselineTest : HglEditorFixture() {
    fun testPinnedWorkspace() {
        val checkout = Path.of(System.getProperty("hglBenchmarkWorkspace"))
        val git = ProcessBuilder("git", "ls-files", "--recurse-submodules", "-z", "--", "*.hgl")
            .directory(checkout.toFile()).start()
        val paths = git.inputStream.readBytes().decodeToString().split('\u0000').filter(String::isNotEmpty)
        assertEquals(0, git.waitFor())
        val sources = paths.associateWith { checkout.resolve(it).readBytes() }
        val root = myFixture.tempDirFixture.findOrCreateDir("workspace")
        val cold = measureNanoTime {
            WriteAction.run<RuntimeException> {
                for ((path, bytes) in sources) {
                    val directory = VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/', ""))!!
                    directory.createChildData(this, path.substringAfterLast('/')).setBinaryContent(bytes)
                }
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            assertTrue(HglModuleIndex.findFiles(project, "core").any { it.name == "scalar_operators.hgl" })
        }
        fun file(path: String): VirtualFile = root.findFileByRelativePath(path) ?: error("Missing fixture $path")
        val ticks = file("hgl_core/tests/conformance/collection_ticks.hgl")
        val highlight = measureNanoTime {
            myFixture.configureFromExistingVirtualFile(ticks)
            myFixture.doHighlighting()
        }

        val core = file("hgl_core/hgl/core/core.hgl")
        val operators = file("hgl_core/hgl/native/scalar_operators.hgl")
        myFixture.configureFromExistingVirtualFile(core)
        val call = myFixture.file.text.indexOf("=> add_(rhs, lhs)") + 3
        assertEquals("add_", myFixture.file.text.substring(call, call + 4))
        myFixture.editor.caretModel.moveToOffset(call)
        fun resolve() = TargetElementUtil.getInstance().getTargetCandidates(
            TargetElementUtil.findReference(myFixture.editor, call + 1)!!,
        )
        val original = resolve()
        assertTrue(original.any { it.containingFile.virtualFile == operators })
        fun fileVisits(): Int {
            var count = 0
            HglModuleIndex.fileVisits.set { count += it }
            try { resolve() } finally { HglModuleIndex.fileVisits.remove() }
            return count
        }
        fun complete(): List<String> {
            myFixture.completeBasic()
            val names = myFixture.lookupElementStrings.orEmpty()
            LookupManager.getInstance(project).hideActiveLookup()
            return names
        }
        val settings = CodeInsightSettings.getInstance()
        val autoInsert = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            repeat(3) { resolve(); assertContainsElements(complete(), "add_") }
            val visits = fileVisits()
            val navigation = p95 { resolve() }
            val completion = p95 { complete() }
            fun replaceText(file: VirtualFile, change: (String) -> String) {
                WriteAction.run<RuntimeException> {
                    val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)!!
                    document.setText(change(document.text))
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }
                IndexingTestUtil.waitUntilIndexesAreReady(project)
            }
            replaceText(file("hgl-compiler/crates/hgl_cli/tests/fixtures/accepted.hgl")) { it + "\n# unrelated edit\n" }
            val afterEdit = p95 { resolve() }
            val visitsAfterEdit = fileVisits()
            assertEquals(original.size, resolve().size)
            replaceText(operators) { it.replaceFirst("fn add_(", "fn renamed_for_baseline(") }
            assertEquals(original.size - 1, resolve().size)
            assertContainsElements(complete(), "renamed_for_baseline")

            val build = ApplicationInfo.getInstance().build.asString()
            println("HGL_BASELINE $build files=${paths.size} cold_import_ms=${ms(cold)} " +
                "first_highlight_ms=${ms(highlight)} completion_p95_ms=${ms(completion)} " +
                "navigation_p95_ms=${ms(navigation)} index_file_visits=$visits " +
                "after_edit_navigation_p95_ms=${ms(afterEdit)} after_edit_visits=$visitsAfterEdit overloads=${original.size}")
        } finally {
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autoInsert
        }
    }

    private fun p95(action: () -> Unit) = List(20) { measureNanoTime(action) }.sorted()[18]
    private fun ms(ns: Long) = String.format(Locale.ROOT, "%.2f", ns / 1_000_000.0)
}
