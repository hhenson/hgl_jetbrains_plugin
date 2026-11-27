package io.github.hhenson.hgl

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.psi.util.parentOfType
import io.github.hhenson.hgl.psi.HglFunctionTypeParameter
import io.github.hhenson.hgl.psi.HglConfigurationParameter
import io.github.hhenson.hgl.psi.HglGenericParameter
import io.github.hhenson.hgl.psi.HglParameter

class HglCallbackArgumentsTest : HglEditorFixture() {
    private fun configure(body: String) {
        myFixture.configureByText("callbacks.hgl", "module callbacks\n" + body.trimIndent())
    }

    private fun complete(body: String): List<String> {
        configure(body)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun navigationTarget(body: String): HglFunctionTypeParameter? {
        configure(body)
        val reference = TargetElementUtil.findReference(myFixture.editor, myFixture.caretOffset) ?: return null
        return TargetElementUtil.getInstance().getTargetCandidates(reference).singleOrNull() as? HglFunctionTypeParameter
    }

    fun testConstAndTemporalCallbackLabelsNavigateToTheirOwnSignatures() {
        val signature = "fn relay(outer: fn(count: i64) -> i64, inner: const fn(count: i64) -> i64) -> i64"
        val inner = navigationTarget("$signature => outer(count: inner(co<caret>unt: 4))")
        assertEquals("count", inner?.name)
        assertEquals("inner", inner?.parentOfType<HglParameter>()?.name)
        val outer = navigationTarget("$signature => outer(co<caret>unt: inner(count: 4))")
        assertEquals("count", outer?.name)
        assertEquals("outer", outer?.parentOfType<HglParameter>()?.name)
    }

    fun testUnnamedSlotsPreservePositionAndBoundLabelsAreOmitted() {
        val signature = "const fn relay(f: const fn(i64, count: i64, step: i64, end: i64) -> i64) -> i64"
        assertContainsElements(complete("$signature => f(1, <caret>)"), "count", "step", "end")
        val names = complete("$signature => f(1, 2, <caret>, end: 4)")
        assertContainsElements(names, "step")
        assertDoesntContain(names, "count", "end")
        val afterNamed = complete("$signature => f(1, end: 4, <caret>)")
        assertContainsElements(afterNamed, "count", "step")
        assertDoesntContain(afterNamed, "end")
    }

    fun testJoinConfiguresItsSeparatorOnlyInBraces() {
        val core = java.io.File("src/test/testData/parser/core/hgl_core_core.hgl")
        myFixture.addFileToProject("core.hgl", core.readText())
        val header = "const fn text() -> str => "
        configure(header + """join{sep<caret>}("a", "b")""")
        val variants = myFixture.completeBasic()
        if (variants != null) {
            val lookup = myFixture.lookup ?: error("expected join option")
            lookup.currentItem = lookup.items.single { it.lookupString == "separator" }
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }
        myFixture.checkResult("module callbacks\n" + header + """join{separator: <caret>}("a", "b")""")
        configure(header + """join{sepa<caret>rator: "/"}("a", "b")""")
        val parameter = myFixture.elementAtCaret as HglConfigurationParameter
        assertEquals("separator", parameter.name)
        assertEquals("join", parameter.parentOfType<io.github.hhenson.hgl.psi.HglOperatorDecl>()?.name)
        assertDoesntContain(complete(header + """join("a", "b", <caret>)"""), "pieces", "separator")
        configure(header + """join("a", sepa<caret>rator: "/")""")
        assertNull(myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve())
    }

    fun testConfiguredCallLabelsUseThePublicSignatureAndOmitBoundInputs() {
        myFixture.addFileToProject("lib.hgl", """
            module lib
            export operator pick<T, const key: str = "id">{limit: i64 = 1, strict: bool = true}(value: T) -> T
        """.trimIndent())
        val header = "use lib as l\nfn example() -> i64 => "
        val names = complete(header + "l::pick<i64>{strict: true, <caret>}(1)")
        assertContainsElements(names, "limit", "key")
        assertDoesntContain(names, "strict", "value", "T")
        configure(header + """l::pick{ke<caret>y: "code"}(1)""")
        assertEquals("key", (myFixture.elementAtCaret as HglGenericParameter).name)
        assertDoesntContain(complete(header + "l::pick{limit: 1}(<caret>)"), "limit", "strict")
        val ordinary = "const fn pick(first: i64, second: i64) -> i64 => first\nconst fn use_it() -> i64 => "
        assertDoesntContain(complete(ordinary + "pick{first: 1}(<caret>)"), "first")
        configure(ordinary + "pick{fi<caret>rst: 1}(second: 2)")
        assertEquals("first", (myFixture.elementAtCaret as HglParameter).name)
    }

    fun testCoreDictionaryFallbackCompletesAndNavigatesToItsImplementation() {
        val core = java.io.File("src/test/testData/parser/core/hgl_core_core.hgl")
        myFixture.addFileToProject("core.hgl", core.readText())
        val header = "const fn count(items: dict<str, i64>) -> i64 => "
        assertContainsElements(complete(header + """get(items, "missing", <caret>)"""), "fallback")
        assertDoesntContain(complete(header + """get(items, "missing", 0, <caret>)"""), "fallback")
        for (call in listOf("""get(items, "missing", fall<caret>back: 0)""", """get{fall<caret>back: 0}(items, "missing")""")) {
            configure(header + call)
            val target = myFixture.elementAtCaret as HglParameter
            assertEquals("fallback", target.name)
            assertEquals("get", target.parentOfType<io.github.hhenson.hgl.psi.HglFunctionDecl>()?.name)
        }
        configure(header + """get(st<caret>ore: items, key: "missing", fallback: 0)""")
        assertEquals("get", (myFixture.elementAtCaret as HglParameter).parentOfType<io.github.hhenson.hgl.psi.HglOperatorDecl>()?.name)
    }

    fun testCandidateLabelsAcrossModulesRetainTheirOwnPositionsAndTargets() {
        myFixture.addFileToProject("api.hgl", "module api\nexport operator choose<T>(x: T) -> T")
        myFixture.addFileToProject("ints.hgl", "module ints\nuse api::{choose}\nimpl const fn choose<const precision: i64 = 1>(x: i64, size: i64) -> i64 => size")
        myFixture.addFileToProject("texts.hgl", "module texts\nuse api::{choose}\nimpl const fn choose(x: str, pad: str, size: i64) -> str => pad")
        myFixture.addFileToProject("other.hgl", "module other\noperator choose(x: i64) -> i64\nimpl fn choose(x: i64, hidden: bool) -> i64 => x")
        val header = "use api as a\nconst fn picked(x: str) -> str => "
        val names = complete(header + "a::choose(x, <caret>)")
        assertContainsElements(names, "pad", "size")
        assertEquals(1, names.count { it == "size" })
        assertDoesntContain(names, "hidden")
        val afterTwo = complete(header + """a::choose(x, "-", <caret>)""")
        assertContainsElements(afterTwo, "size")
        assertDoesntContain(afterTwo, "pad")
        assertDoesntContain(complete(header + """a::choose(x, "-", 2, <caret>)"""), "precision")
        configure(header + "a::choose(x, si<caret>ze: 2)")
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as com.intellij.psi.PsiPolyVariantReference
        assertEquals(setOf("ints.hgl", "texts.hgl"), ref.multiResolve(false).map { it.element!!.containingFile.name }.toSet())
        configure(header + """a::choose(x, "-", si<caret>ze: 2)""")
        assertEquals("texts.hgl", myFixture.elementAtCaret.containingFile.name)
        configure(header + """a::choose(x, pad: "-", si<caret>ze: 2)""")
        assertEquals("texts.hgl", myFixture.elementAtCaret.containingFile.name)
    }

    fun testNamedPackDoesNotSupplyAnExtraPositionalSlot() {
        val header = """
            operator named<T, ...Fields>(x: T, args: ...{Fields}) -> T
            impl const fn named<...Fields, const scale: i64 = 1>(x: i64, args: ...{Fields}) -> i64 => x
            const fn example() -> i64 =>
        """.trimIndent() + " "
        assertContainsElements(complete(header + "named(1, <caret>)"), "scale")
        assertDoesntContain(complete(header + "named(1, 2, <caret>)"), "scale")
    }

    fun testSameFileNativeCandidateKeepsBraceOnlyOptionsAndGenericLabelsSeparate() {
        val header = """
            operator quantize(x: i64) -> i64
            native impl const fn quantize<const units: i64 = 1>{strict: bool = true}(x: i64, bias: i64) -> i64
            const fn score(value: i64) -> i64 =>
        """.trimIndent() + " "
        val ordinary = complete(header + "quantize(value, <caret>)")
        assertContainsElements(ordinary, "bias", "units")
        assertDoesntContain(ordinary, "strict", "x")
        val options = complete(header + "quantize{<caret>}(value, units: 2)")
        assertContainsElements(options, "strict", "bias")
        assertDoesntContain(options, "units", "x")
        assertDoesntContain(complete(header + "quantize{units: 2}(value, <caret>)"), "units")
    }

    fun testOnlyTheDeclaringModulesTestsSeeItsTestCandidateOptions() {
        myFixture.addFileToProject("api.hgl", "module api\nexport operator choose(x: i64) -> i64")
        myFixture.addFileToProject("helpers.hgl", """
            module callbacks part helpers
            use api::{choose}
            test with_option {
                impl const fn choose(x: i64, scale: i64) -> i64 => x * scale
            }
        """.trimIndent())
        val imported = "use api::{choose}\n"
        assertContainsElements(complete(imported + "test another { assert choose(1, <caret>) == 2 }"), "scale")
        assertDoesntContain(complete(imported + "const fn regular() -> i64 => choose(1, <caret>)"), "scale")
    }

    fun testQualifiedGenericCallUsesDeclaredLabels() {
        myFixture.addFileToProject("lib.hgl", "module lib\nexport const fn pick<T>(first: T, second: T) -> T => first")
        val header = "use lib as l\nconst fn example() -> i64 => "
        val labels = complete(header + "l::pick<i64>(1, <caret>)")
        assertContainsElements(labels, "second")
        assertDoesntContain(labels, "first")
        configure(header + "l::pick(1, sec<caret>ond: 2)")
        assertEquals("lib.hgl", (myFixture.elementAtCaret as HglParameter).containingFile.name)
    }

    fun testFixedParametersEndAtTheFirstPack() {
        val header = "fn relay(f: fn(first: i64, ...i64) -> i64) -> i64 => "
        assertContainsElements(complete(header + "f(<caret>)"), "first")
        assertDoesntContain(complete(header + "f(1, 2, 3, <caret>)"), "first")
        val invalid = "fn relay(f: fn(...i64, former: i64) -> i64) -> i64 => "
        assertDoesntContain(complete(invalid + "f(1, <caret>)"), "former")
        assertNull(navigationTarget(invalid + "f(1, for<caret>mer: 2)"))
    }

    fun testSeparatorAndFollowingArgumentsConstrainCompletion() {
        val header = "fn relay(f: fn(count: i64, step: i64) -> i64) -> i64 => "
        val labels = complete(header + "f(<caret>: 1)")
        assertContainsElements(labels, "count", "step")
        assertDoesntContain(labels, "fn")
        val beforePositional = complete(header + "f(<caret>, 2)")
        assertContainsElements(beforePositional, "fn")
        assertDoesntContain(beforePositional, "count", "step")
    }

    fun testCompletingPrefixInsertsSeparatorAndPlacesCaretAtValue() {
        val header = "fn relay(f: fn(count: i64, counter: i64) -> i64) -> i64 => "
        complete(header + "f(co<caret>)")
        selectCount()
        myFixture.checkResult("module callbacks\n" + header + "f(count: <caret>)")
    }

    fun testCompletingExistingLabelPreservesSeparatorAndValue() {
        val header = "fn relay(f: fn(count: i64, counter: i64) -> i64) -> i64 => "
        complete(header + "f(co<caret>unter:  4)")
        selectCount()
        myFixture.checkResult("module callbacks\n" + header + "f(count:  <caret>4)")
    }

    private fun selectCount() {
        val lookup = myFixture.lookup ?: error("expected callback label lookup")
        lookup.currentItem = lookup.items.single { it.lookupString == "count" }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
    }

    fun testAnonymousSlotHidesConcreteFunctionName() {
        val source = """
            const fn actual(hidden: i64) -> i64 => hidden
            const fn relay(f: const fn(i64) -> i64) -> i64 => f(CALL)
            const fn entry() -> i64 => relay(actual)
        """.trimIndent()
        assertDoesntContain(complete(source.replace("CALL", "<caret>")), "hidden")
        assertNull(navigationTarget(source.replace("CALL", "hid<caret>den: 1")))
    }

    fun testLabelsAreLocalToCallArgumentHeads() {
        val header = "fn relay(f: fn(slot: i64, next: i64) -> i64, x: i64) -> i64 => "
        for (expression in listOf("f(slot: <caret>)", "f(1 + <caret>)", "f(helper(<caret>))", "f{slot: <caret>}(1, 2)")) {
            assertDoesntContain(complete(header + expression), "slot", "next")
        }
        assertNull(navigationTarget(header + "f{slo<caret>t: 1}(1, 2)"))
        assertNull(navigationTarget(header + "f(slot: helper(ne<caret>xt: x), next: x)"))
    }

    fun testLabelAndValueWithTheSameSpellingNavigateSeparately() {
        val header = "fn relay(f: fn(count: i64) -> i64, count: i64) -> i64 => "
        assertEquals("count", navigationTarget(header + "f(co<caret>unt: count)")?.name)
        configure(header + "f(count: co<caret>unt)")
        val target = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve() as? HglParameter
        assertEquals("count", target?.name)
    }

    fun testForwardedPackDoesNotConsumeAnExplicitPositionalSlot() {
        val body = """
            fn relay<...Ps, ...As>(f: fn(count: i64, ...{Ps}) -> i64, args: ...{As for f}) -> i64 {
                let given = {for arg in arg_keys(Ps): args[arg]}
                return f(...given, <caret>)
            }
        """
        assertContainsElements(complete(body), "count")
    }

    fun testInnerBindingShadowsCallbackForBothFeatures() {
        val body = """
            const fn relay(f: const fn(count: i64) -> i64) -> i64 {
                let f = 4
                return f(CALL)
            }
        """.trimIndent()
        assertNull(navigationTarget(body.replace("CALL", "co<caret>unt: 2")))
        assertDoesntContain(complete(body.replace("CALL", "<caret>")), "count")
    }
}
