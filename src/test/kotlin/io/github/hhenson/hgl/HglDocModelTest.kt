package io.github.hhenson.hgl

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.documentation.*
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.run.HglTestSettings
import java.io.File
import java.nio.file.Files

class HglDocModelTest : HglEditorFixture() {
    // Subset of the pinned compiler's export of crates/hgl_cli/tests/fixtures/doc with --private.
    private val model get() = File("src/test/testData/documentation/model-v1.json").readText()
    private val provider = HglDocumentationTargetProvider()
    private fun targets(code: String) = run {
        myFixture.configureByText("consumer.hgl", code.trimIndent())
        provider.documentationTargets(myFixture.file, myFixture.caretOffset)
    }

    fun testExportSuppliesDistinctOverloadsParameterHelpAndBundledCore() {
        myFixture.addFileToProject("documentation.json", model)
        val prefix = "module consumer\nuse examples.apidoc as api\n"
        val variants = targets(prefix + "fn g(x: i64) => api::iden<caret>tity(x)")
        assertSize(2, variants)
        val descriptions = variants.map { it.computeDocumentationHint()!! }
        assertTrue(descriptions.any { it.contains("First public callable.") })
        assertTrue(descriptions.any { it.contains("The temporal callable") })
        val args = targets(prefix + "fn g(x: i64) => api::identity(inp<caret>ut: x)")
        assertTrue(args.any { it.computeDocumentationHint()!!.contains("A café value") })
        assertEmpty(targets(prefix + "fn g() => api::int<caret>ernal()"))
        assertEmpty(targets("module consumer\nfn g() => ide<caret>ntity(2)"))
        val core = targets("module consumer\nfn g() => smallest_st<caret>ep").single()
        assertTrue(core.computeDocumentationHint()!!.contains("smallest_step"))
        assertNotNull(core.createPointer().dereference())
    }

    fun testExportedStructConstructorLabelsShowFieldDocumentation() {
        myFixture.addFileToProject("documentation.json", model)
        val code = "module consumer\nuse examples.apidoc as api\nfn g() => api::Box<i64>(val<caret>ue: 7)"
        val field = targets(code).single()
        val hint = field.computeDocumentationHint()!!
        assertTrue(hint, hint.contains("value: T") && hint.contains("The payload."))
        assertEquals(hint, field.createPointer().dereference()?.computeDocumentationHint())
        assertEmpty(targets(code.replace("val<caret>ue", "miss<caret>ing")))
    }

    fun testSelectiveImportAndSourcePriority() {
        myFixture.addFileToProject("documentation.json", model)
        val code = "module consumer\nuse examples.apidoc::{identity}\nfn g() => ide<caret>ntity(2)"
        assertSize(2, targets(code))
        myFixture.addFileToProject("library.hgl",
            "module examples.apidoc\n/** Source summary. */\nexport const fn identity(x: i64) -> i64 => x")
        assertTrue(targets(code).single().computeDocumentationHint()!!.contains("Source summary."))
        assertEmpty(targets(code.replace("identity}", "shifted}").replace("ide<caret>ntity", "shif<caret>ted")))
    }

    fun testConfiguredExportOutsideProject() {
        val settings = project.service<HglTestSettings>().state
        val previous = settings.documentationModel
        val path = Files.createTempFile("hgl-doc-test", ".json")
        try {
            Files.writeString(path, model)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            settings.documentationModel = path.toString()
            assertSize(2, targets("module consumer\nuse examples.apidoc as api\nfn g() => api::ide<caret>ntity(2)"))
        } finally {
            settings.documentationModel = previous
            Files.deleteIfExists(path)
        }
    }

    fun testOperatorKeepsItsOwnDocumentationAndAllCandidateOrigins() {
        val docs = HglDocModel.read(model)
        val operator = docs.declarations("examples.apidoc::shifted").single()
        assertEquals("A public operator's own documentation.", operator.sections.first().lines.single())
        assertEquals(listOf("examples.apidoc", "examples.apidoc", "examples.other"), operator.candidates.map { it.source.module })
        assertEquals("An externally declared duration candidate.", operator.candidates.last().sections.first().lines.single())
        val identity = docs.declarations("examples.apidoc::identity").first()
        val custom = identity.sections.single { it.heading == "Unknown:" }
        assertFalse(custom.recognized)
        assertEquals("A heading that stays prose.", custom.lines.single())
    }

    fun testModelChangesInvalidateOldTargetsAndUnknownVersionsAreIgnored() {
        val json = myFixture.addFileToProject("documentation.json", model)
        val code = "module consumer\nuse examples.apidoc as api\nfn g() => api::shif<caret>ted(2)"
        val pointer = targets(code).single().createPointer()
        val doc = PsiDocumentManager.getInstance(project).getDocument(json)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.setText("""{"schemaVersion":2,"modules":[]}""") }
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        assertNull(pointer.dereference())
        assertEmpty(targets(code))
    }

    fun testMarkdownTablesLiteralHtmlMathAndLinks() {
        val target = targets("""
            module sample
            const fn triple(x: i64) -> i64 => x * 3
            /** Call `triple` or `sample.triple`. Parameter `x`, unknown `missing`, formula ${'$'}`triple`$.

            | Input | Output |
            | --- | --- |
            | *one* | **three** |

            <b>literal</b>

            ~~~mermaid
            graph LR
                A-->B
            ~~~
            */
            const fn do<caret>uble(x: i64) -> i64 => x * 2
        """).single()
        val function = PsiTreeUtil.findChildrenOfType(myFixture.file, HglFunctionDecl::class.java).last()
        val html = HglDocumentation.html(function)!!
        for (part in listOf("<table>", "<em>one</em>", "<strong>three</strong>", "&lt;b&gt;literal&lt;/b&gt;",
            "language-mermaid", "hgl-doc:triple", "hgl-doc:sample.triple", "$<code>triple</code>$")) assertTrue(html, html.contains(part))
        assertFalse(html.contains("hgl-doc:x"))
        assertFalse(html.contains("hgl-doc:missing"))
        val handler = DocumentationLinkHandler.EP_NAME.extensionList.filterIsInstance<HglDocLinkHandler>().single()
        assertNotNull(handler.resolveLink(target, "hgl-doc:sample.triple"))
        assertNull(handler.resolveLink(target, "hgl-doc:missing"))
    }

    fun testOptionsRulesAndStatusAreRecognized() {
        val docs = docSections("Options:\n    - One.\nRules:\n    GEN-3.\nStatus:\n    Agreed.")
        assertEquals(listOf("Options:", "Rules:", "Status:"), docs.filter { it.recognized }.map { it.heading })
    }

    fun testLiteralSectionHeadingInsideFenceStaysInProse() {
        val docs = docSections("A fence.\n\n~~~text\nArgs:\n    not_a_parameter: literal\n~~~\n\nReturns:\n    Result.")
        assertEquals(listOf(null, "Returns:"), docs.map { it.heading })
    }
}
