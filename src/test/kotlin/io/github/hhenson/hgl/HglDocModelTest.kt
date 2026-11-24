package io.github.hhenson.hgl

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.documentation.*
import io.github.hhenson.hgl.psi.HglConstantDecl
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.psi.HglNativeProperty
import io.github.hhenson.hgl.run.HglTestSettings
import java.io.File
import java.nio.file.Files

class HglDocModelTest : HglEditorFixture() {
    // Generated from the pinned compiler's documentation and test-local declaration fixtures with --private.
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

    fun testBundledFormatPacksAndWriteParameterHelp() {
        myFixture.addFileToProject("documentation.json", model)
        val format = targets("""module consumer
            |const fn text() => for<caret>mat("{} {price:.2f}", 7, price: 2.5)
        """.trimMargin()).single().computeDocumentationHint()!!
        assertTrue(format, format.contains("args: ...As, kwargs: ...{Ks}"))
        assertTrue(format, format.contains("Fill the places in a constant format string."))

        val flush = targets("""module consumer
            |const fn emit() { write("ready", flu<caret>sh: true) }
        """.trimMargin()).single().computeDocumentationHint()!!
        assertTrue(flush, flush.contains("flush: bool = false") && flush.contains("Flush the stream before returning."))
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

    fun testNativePropertyDocsMatchTheExportAndResolveFromTheOwner() {
        myFixture.addFileToProject("documentation.json", model)
        val source = File("src/test/testData/documentation/native_property.hgl").readText()
        val owner = targets("""
            module consumer
            use examples.properties as api
            const fn read(m: api::Me<caret>ter) -> i64 => m.if
        """).single()
        assertTrue(owner.computeDocumentationHint()!!.contains("hgl-doc:Meter.if"))
        val property = checkNotNull((owner as HglLinkedDoc).symbol("Meter.if"))
        val hint = property.computeDocumentationHint()!!
        assertTrue(hint, hint.contains("if: i64") && hint.contains("Current <strong>level</strong>."))
        assertEquals(hint, property.createPointer().dereference()?.computeDocumentationHint())

        val sourceHint = targets(source.replace("if: i64", "i<caret>f: i64")).single().computeDocumentationHint()
        assertEquals(hint, sourceHint)
        val members = PsiTreeUtil.findChildrenOfType(myFixture.file, HglNativeProperty::class.java)
        val html = HglDocumentation.html(members.single { it.name == "if" })!!
        val record = HglDocModel.read(model).declarations("examples.properties::Meter.if").single()
        assertEquals(docHtml(record.signature, record.sections, false) { false }, html)
        assertTrue(html, html.contains("Runnable example") && html.contains("assert true"))
        assertNull(HglDocumentation.attached(members.single { it.name == "limit" }))
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

    fun testParameterHidingAliasAlsoHidesExportedDocumentation() {
        myFixture.addFileToProject("documentation.json", model)
        val imports = "module consumer\nuse examples.apidoc as api\n"
        assertEmpty(targets(imports + "fn g(api: i64) => api::ide<caret>ntity(2)"))
        assertSize(2, targets(imports + "fn g() => api::ide<caret>ntity(2)"))
    }

    fun testQualifiedDocLinksUseOnlyTheAliasesVisibleToTheComment() {
        myFixture.addFileToProject("provider.hgl", """
            module doc.provider
            export const fn marker() -> i64 => 7
            export enum Side { buy, sell }
            const fn internal() -> i64 => 0
        """.trimIndent())
        val code = """
            module consumer
            use doc.provider as dep
            /** Read `dep::marker`, `dep::Side.buy`, or `dep::internal`. */
            const fn ex<caret>ample() -> i64 => 7
        """.trimIndent()
        val target = targets(code).single()
        val html = target.computeDocumentationHint()!!
        val handler = HglDocLinkHandler()
        for (name in listOf("dep::marker", "dep::Side.buy")) {
            assertTrue(html, html.contains("href=\"hgl-doc:$name\""))
            assertNotNull(handler.resolveLink(target, "hgl-doc:$name"))
        }
        assertNull(handler.resolveLink(target, "hgl-doc:dep::internal"))
        val shadowed = targets(code.replace("ex<caret>ample()", "ex<caret>ample(dep: i64)")).single()
        assertFalse(shadowed.computeDocumentationHint()!!.contains("hgl-doc:dep::"))
        assertNull(handler.resolveLink(shadowed, "hgl-doc:dep::marker"))
    }

    fun testSourceAliasLinksToExportedDocsWithoutProviderSource() {
        myFixture.addFileToProject("documentation.json", model)
        val target = targets("""
            module consumer
            use examples.apidoc as lib
            /** See `lib::Box.value`. */
            const fn ma<caret>in() -> i64 => 2
        """).single()
        val field = (target as HglLinkedDoc).symbol("lib::Box.value")
        assertTrue(field?.computeDocumentationHint().orEmpty().contains("The payload."))
        assertNull((field as HglLinkedDoc).symbol("lib::Box.value"))
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

    fun testGeneratedModelTakesPrecedenceOverLegacyRootExport() {
        myFixture.addFileToProject("documentation.json", """{"schemaVersion":1,"modules":[]}""")
        myFixture.addFileToProject("docs/documentation.json", model)
        assertSize(2, targets("module consumer\nuse examples.apidoc as api\nfn g() => api::ide<caret>ntity(2)"))
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

    fun testPrivateExportKeepsDeclarationsWithTheirOwningTest() {
        myFixture.addFileToProject("documentation.json", model)
        val docs = HglDocModel.read(model)
        val module = "demo.test_local_declarations"
        val operator = docs.declarations("$module::classify").single()
        assertEquals("operator", operator.kind)
        assertEquals("$module::classify", operator.candidates.single().canonicalName)
        assertEquals("candidate", docs.declarations("$module::local_candidate::classify").single().kind)
        assertEmpty(docs.declarations("$module::Closed"))
        for (test in listOf("with_a_type", "independent_types_and_operator")) {
            assertEquals("struct", docs.declarations("$module::$test::Closed").single().kind)
        }

        val consumer = "module consumer\nuse $module as lib\n"
        assertSize(1, targets(consumer + "const fn show() => lib::class<caret>ify(1)"))
        assertEmpty(targets(consumer + "const fn show() => lib::Clo<caret>sed()"))
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

    fun testSourceAndExportLabelExecutableDocumentationExamples() {
        val exported = HglDocModel.read(model).declarations("demo.documentation_examples::twice").single()
        val html = docHtml("twice", exported.sections, false) { false }
        assertEquals(2, "Runnable example".toRegex().findAll(html).count())
        assertTrue(html, html.contains("Shown example</p>\n<pre><code class=\"language-hgl\">This displayed fragment"))

        val sections = docSections("""
            ~~~hgl
            shown
            ~~~

            Examples:
                > ~~~hgl
                > assert true
                > ~~~

                ~~~hgl no-run
                shown only
                ~~~

                ~~~rust
                other language
                ~~~
        """.trimIndent())
        val source = docHtml("sample", sections, false) { false }
        assertEquals(1, "Runnable example".toRegex().findAll(source).count())
        assertEquals(2, "Shown example".toRegex().findAll(source).count())
        assertTrue(source, source.contains("<blockquote>"))
    }

    fun testSourceAndExportRenderTheSameParameterMarkdown() {
        val source = File("src/test/testData/documentation/doc_extraction.hgl").readText()
        myFixture.configureByText("doc_extraction.hgl", source)
        val function = PsiTreeUtil.findChildrenOfType(myFixture.file, HglFunctionDecl::class.java)
            .single { it.name == "describe_value" }
        val exported = HglDocModel.read(model).declarations("extraction_check::describe_value").single()
        val parameter = function.functionSignature!!.parameterList.single()
        val argument = exported.arguments.getValue("value")
        val argumentHtml = docHtml(argument.signature, listOf(HglDocSection(null, argument.description!!.lines())), false) { false }
        assertEquals(argumentHtml, HglDocumentation.html(parameter))
        val full = docHtml(exported.signature, exported.sections, false) { false }
        assertEquals(full, HglDocumentation.html(function))
        for (html in listOf(full, argumentHtml)) {
            for (part in listOf("<table>", "<li>Item", "Second line", "Shown example", "<p>Last paragraph.</p>")) {
                assertTrue("Missing $part in $html", html.contains(part))
            }
        }
        assertTrue(full, full.contains("<p>Notes for display:</p>") && full.contains("<p>A custom section.</p>"))
    }

    fun testHeadingOnTheOpeningLineDoesNotMakeAnExampleRunnable() {
        val source = File("src/test/testData/documentation/doc_extraction.hgl").readText()
        myFixture.configureByText("doc_extraction.hgl", source)
        val constant = PsiTreeUtil.findChildrenOfType(myFixture.file, HglConstantDecl::class.java)
            .single { it.name == "prose_example" }
        val exported = HglDocModel.read(model).declarations("extraction_check::prose_example").single()
        val full = docHtml(exported.signature, exported.sections, false) { false }
        assertEquals(full, HglDocumentation.html(constant))
        assertTrue(full, full.contains("<p>Examples:</p>") && full.contains("Shown example"))
        assertFalse(full, full.contains("Runnable example"))
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
