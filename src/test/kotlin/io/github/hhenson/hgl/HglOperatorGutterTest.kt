package io.github.hhenson.hgl

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.navigation.NavigationGutterIconRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import io.github.hhenson.hgl.reference.OperatorTargetRenderer
import java.nio.file.Path
import kotlin.io.path.readText

class HglOperatorGutterTest : HglEditorFixture() {
    private fun source(path: String, text: String) = myFixture.addFileToProject(path, text.trimIndent() + "\n")

    private fun markers(file: PsiFile): List<LineMarkerInfo<*>> {
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.doHighlighting()
        return DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project).filter {
            it.icon == AllIcons.Gutter.ImplementedMethod || it.icon == AllIcons.Gutter.ImplementingMethod
        }
    }

    private fun targets(marker: LineMarkerInfo<*>): List<PsiElement> =
        (marker.navigationHandler as NavigationGutterIconRenderer).targetElements

    fun testClickingASingleTargetOpensTheDeclarationNameInBothDirections() {
        val api = source("lib/api.hgl", "module quotes\noperator bid() -> f64")
        val part = source("lib/feed.hgl", "module quotes part feed\nnative impl fn bid() -> f64")
        for ((from, to) in listOf(api to part, part to api)) {
            val marker = markers(from).single()
            (marker.navigationHandler as NavigationGutterIconRenderer).navigate(null, marker.element)
            PlatformTestUtil.waitWithEventsDispatching("gutter did not navigate to ${to.name}", {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@waitWithEventsDispatching false
                FileDocumentManager.getInstance().getFile(editor.document) == to.virtualFile &&
                    editor.caretModel.offset == to.text.indexOf("bid")
            }, 5)
        }
    }

    fun testOperatorAndCandidatesNavigateBothWaysAcrossPartsAndSelectiveImports() {
        val api = source("api.hgl", "module math.ops\nexport operator twice<T>(value: T) -> T")
        val local = source("local.hgl", """
            module math.ops part local
            impl const fn twice(value: i64) -> i64 => value + value
            native impl const fn twice(value: f64) -> f64
        """)
        val external = source("external.hgl", """
            module extensions
            use math.ops::{twice}
            impl fn twice(value: str) -> str => value + value
        """)
        val timers = source("timers.hgl", """
            module clocks.timers
            use math.ops::{twice}
            native impl const fn twice(value: duration) -> duration
        """)
        source("other.hgl", "module other\noperator twice(value: bool) -> bool\nnative impl fn twice(value: bool) -> bool")
        val sibling = source("sibling.hgl", "module extensions part more\nimpl fn twice(value: i64) -> i64 => value")
        val alias = source("alias.hgl", "module alias\nuse math.ops as ops\nimpl fn twice(value: i64) -> i64 => value")
        val plain = source("plain.hgl", "module plain\nfn twice(value: i64) -> i64 => value")

        val forward = markers(api).single()
        assertEquals("twice", forward.element!!.text)
        assertEquals(api.text.indexOf("twice"), forward.startOffset)
        assertEquals(AllIcons.Gutter.ImplementedMethod, forward.icon)
        assertEquals("Implemented by 4 candidates", forward.lineMarkerTooltip)
        val candidates = targets(forward)
        assertEquals(listOf("external.hgl", "local.hgl", "local.hgl", "timers.hgl"), candidates.map { it.containingFile.name }.sorted())
        assertEquals(setOf("impl const fn twice(value: i64) -> i64", "native impl const fn twice(value: f64) -> f64",
            "impl fn twice(value: str) -> str", "native impl const fn twice(value: duration) -> duration"),
            candidates.map(OperatorTargetRenderer::getElementText).toSet())

        for (part in listOf(local, external, timers)) {
            val reverse = markers(part)
            assertEquals(candidates.count { it.containingFile == part }, reverse.size)
            for (marker in reverse) {
                assertEquals(AllIcons.Gutter.ImplementingMethod, marker.icon)
                assertEquals("Implements twice", marker.lineMarkerTooltip)
                assertEquals(forward.element!!.parent.parent, targets(marker).single())
            }
        }
        for (file in listOf(sibling, alias, plain)) assertEmpty(markers(file))
    }

    fun testPrivateOperatorIsVisibleOnlyToItsOwnModuleParts() {
        val declaration = source("owner.hgl", "module owner\noperator size_of(x: i64) -> i64")
        val local = source("local.hgl", "module owner part extra\nimpl fn size_of(x: i64) -> i64 => x")
        val external = source("external.hgl", "module ext\nuse owner::{size_of}\nimpl fn size_of(x: str) -> i64 => 0")
        assertEquals(local, targets(markers(declaration).single()).single().containingFile)
        assertEmpty(markers(external))
    }

    fun testCorePreludeApplicationScopeAndTestHelpers() {
        val core = source("core.hgl", "module core\nexport operator add_<T>(lhs: T, rhs: T) -> T")
        val app = source("app.hgl", """
            application demo
            native impl const fn add_(lhs: i64, rhs: i64) -> i64
            operator local(value: str) -> str
            impl const fn local(value: str) -> str => value
            test {
                impl fn add_(lhs: i64, rhs: i64) -> i64 => lhs
            }
        """)
        val other = source("elsewhere.hgl", "module elsewhere\nimpl fn local(value: str) -> str => value")
        val shadow = source("shadow.hgl", "module shadow\nnative fn add_()\nimpl fn add_() {}")
        val alias = source("alias.hgl", "module alias\nuse elsewhere as add_\nimpl fn add_() {}")
        val fromCore = targets(markers(core).single()).single()
        assertEquals(app, fromCore.containingFile)
        assertTrue(fromCore.text.startsWith("native impl const fn add_"))
        val inApp = markers(app)
        assertEquals(3, inApp.size)
        assertEquals(core, targets(inApp.single { it.element!!.text == "add_" }).single().containingFile)
        for (marker in inApp.filter { it.element!!.text == "local" }) {
            assertEquals(app, targets(marker).single().containingFile)
        }
        assertEmpty(markers(other))
        assertEmpty(markers(shadow))
        assertEmpty(markers(alias))
    }

    fun testPinnedCoreAdditionOffersNativeForwardingAndIdentitySignatures() {
        val fixtures = Path.of("src/test/testData/parser/core")
        val core = source("core/core.hgl", fixtures.resolve("hgl_core_core.hgl").readText())
        val native = source("native/scalar_operators.hgl", fixtures.resolve("hgl_native_scalar_operators.hgl").readText())
        val add = markers(core).single { it.icon == AllIcons.Gutter.ImplementedMethod && it.element!!.text == "add_" }
        val choices = targets(add)
        val labels = choices.map(OperatorTargetRenderer::getElementText)
        assertContainsElements(labels, "native impl const fn add_(lhs: i64, rhs: f64) -> f64",
            "impl const fn add_(lhs: f64, rhs: i64) -> f64",
            "impl fn add_<T, const invalid: InvalidInputPolicy>(lhs: T, rhs: T) -> T requires invalid == .identity && add_(T, T) -> T")
        val nativeChoices = choices.filter { it.containingFile == native }
        assertNotEmpty(nativeChoices)
        assertTrue(nativeChoices.all { OperatorTargetRenderer.getContainerText(it).endsWith("native/scalar_operators.hgl") })
        val reverse = markers(native).filter { it.element!!.text == "add_" }
        assertEquals(nativeChoices.size, reverse.size)
        assertTrue(reverse.all { targets(it).single() == add.element!!.parent.parent })
    }

    fun testRemovingAndRenamingAnImplementationRefreshesTheOperatorMarker() {
        val api = source("api.hgl", "module m\noperator a()\noperator b()")
        val candidate = source("part.hgl", "module m part more\nimpl fn a() {}")
        assertEquals("a", markers(api).single().element!!.text)
        val manager = PsiDocumentManager.getInstance(project)
        val document = manager.getDocument(candidate)!!
        fun replace(text: String) {
            WriteCommandAction.runWriteCommandAction(project) { document.setText("module m part more\n$text\n") }
            manager.commitDocument(document)
        }
        replace("impl fn b() {}")
        assertEquals("b", markers(api).single().element!!.text)
        replace("fn b() {}\n# impl fn b() {}")
        assertEmpty(markers(api))
        assertEmpty(markers(candidate))
    }
}
