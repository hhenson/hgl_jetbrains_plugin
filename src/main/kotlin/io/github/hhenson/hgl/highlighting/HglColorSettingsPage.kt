package io.github.hhenson.hgl.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import io.github.hhenson.hgl.HglIcons
import javax.swing.Icon

class HglColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = HglIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = HglSyntaxHighlighter()

    override fun getDemoText(): String = DEMO

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "HGL"

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keywords//Keyword", HglColors.KEYWORD),
            AttributesDescriptor("Keywords//Scalar type keyword", HglColors.TYPE_KEYWORD),
            AttributesDescriptor("Keywords//Contextual keyword", HglColors.CONTEXTUAL_KEYWORD),
            AttributesDescriptor("Keywords//true, false, null", HglColors.LITERAL_KEYWORD),
            AttributesDescriptor("Literals//Number", HglColors.NUMBER),
            AttributesDescriptor("Literals//Temporal literal", HglColors.TEMPORAL_LITERAL),
            AttributesDescriptor("Literals//String", HglColors.STRING),
            AttributesDescriptor("Literals//Placeholder _", HglColors.PLACEHOLDER),
            AttributesDescriptor("Comments//Line comment", HglColors.LINE_COMMENT),
            AttributesDescriptor("Comments//Documentation", HglColors.DOC_COMMENT),
            AttributesDescriptor("Comments//Block comment", HglColors.BLOCK_COMMENT),
            AttributesDescriptor("Punctuation//Operator", HglColors.OPERATOR),
            AttributesDescriptor("Punctuation//Parentheses", HglColors.PARENTHESES),
            AttributesDescriptor("Punctuation//Braces", HglColors.BRACES),
            AttributesDescriptor("Punctuation//Brackets", HglColors.BRACKETS),
            AttributesDescriptor("Punctuation//Comma", HglColors.COMMA),
            AttributesDescriptor("Punctuation//Dot", HglColors.DOT),
            AttributesDescriptor("Punctuation//Semicolon", HglColors.SEMICOLON),
            AttributesDescriptor("Declarations//Function", HglColors.FUNCTION_DECLARATION),
            AttributesDescriptor("Declarations//Operator", HglColors.OPERATOR_DECLARATION),
            AttributesDescriptor("Declarations//Struct", HglColors.STRUCT_NAME),
            AttributesDescriptor("Declarations//Test", HglColors.TEST_NAME),
            AttributesDescriptor("Declarations//Parameter", HglColors.PARAMETER),
            AttributesDescriptor("Declarations//Generic parameter", HglColors.TYPE_PARAMETER),
            AttributesDescriptor("Declarations//Local variable", HglColors.LOCAL_VARIABLE),
            AttributesDescriptor("Declarations//State and cache", HglColors.STATE_VARIABLE),
            AttributesDescriptor("Declarations//Struct field", HglColors.FIELD),
            AttributesDescriptor("Declarations//Injectable", HglColors.INJECTABLE),
            AttributesDescriptor("References//Function call", HglColors.FUNCTION_CALL),
            AttributesDescriptor("References//Struct reference", HglColors.STRUCT_REFERENCE),
            AttributesDescriptor("References//Intrinsic", HglColors.INTRINSIC),
            AttributesDescriptor("References//Named argument", HglColors.NAMED_ARGUMENT),
            AttributesDescriptor("References//Module path", HglColors.MODULE_PATH),
            AttributesDescriptor("References//Identifier", HglColors.IDENTIFIER),
            AttributesDescriptor("Embedded C++", HglColors.EMBEDDED_CPP),
            AttributesDescriptor("Bad character", HglColors.BAD_CHARACTER),
        )

        private val TAGS: Map<String, TextAttributesKey> = mapOf(
            "fn" to HglColors.FUNCTION_DECLARATION,
            "op" to HglColors.OPERATOR_DECLARATION,
            "struct" to HglColors.STRUCT_NAME,
            "test" to HglColors.TEST_NAME,
            "param" to HglColors.PARAMETER,
            "tparam" to HglColors.TYPE_PARAMETER,
            "local" to HglColors.LOCAL_VARIABLE,
            "state" to HglColors.STATE_VARIABLE,
            "field" to HglColors.FIELD,
            "inject" to HglColors.INJECTABLE,
            "call" to HglColors.FUNCTION_CALL,
            "sref" to HglColors.STRUCT_REFERENCE,
            "intrinsic" to HglColors.INTRINSIC,
            "narg" to HglColors.NAMED_ARGUMENT,
            "mod" to HglColors.MODULE_PATH,
            "ckw" to HglColors.CONTEXTUAL_KEYWORD,
        )

        private val DEMO = """
            # A helper, an export, state and a test.
            module <mod>examples.prices</mod>

            use <mod>hgraph.analytics</mod>::{<call>rolling_mean</call>}

            export struct <struct>Quote</struct> {
                <field>bid</field>: f64
                <field>ask</field>: f64
                <field>venue</field>: str = null
            }

            operator <op>summarize</op><<tparam>T</tparam>>(<param>window</param>: <ckw>rolling</ckw><<tparam>T</tparam>, 20>) -> <tparam>T</tparam>

            fn <fn>midpoint</fn>(<param>tob</param>: <ckw>atomic</ckw><<ckw>tuple</ckw><f64, f64>>) -> f64 =>
                (<param>tob</param>[0] + <param>tob</param>[1]) / 2.0

            export fn <fn>smooth</fn>(<param>quote</param>: <ckw>atomic</ckw><<sref>Quote</sref>>, const <param>window</param>: duration = 5m) -> f64 {
                <local>let</local> <local>mid</local> = <call>midpoint</call>((<param>quote</param>.<field>bid</field>, <param>quote</param>.<field>ask</field>))
                <call>rolling_mean</call>(<local>mid</local>, <narg>period</narg>: <param>window</param>)
            }

            fn <fn>running_total</fn>(<param>value</param>: f64) -> f64 {
                state <state>total</state>: f64 = 0.0
                inject <inject>out</inject>, <inject>logger</inject>
                start { <inject>logger</inject>.info("starting @2026-09-03T09:30Z") }
                when <intrinsic>modified</intrinsic>(<param>value</param>) && <intrinsic>valid</intrinsic>(<param>value</param>) {
                    <state>total</state> += <param>value</param>
                    <inject>out</inject> = <state>total</state>
                }
            }

            /* Native code stays opaque. */
            native fn <fn>increment</fn>(<param>value</param>: f64) -> f64 {
                cpp(hgraph::Float value) { return value + 1.0; }
            }

            test <test>midpoint_ticks</test> {
                assert eval(<call>midpoint</call>, <narg>tob</narg>: [(1.0, 2.0), _, (2.0, 3.0)]) == [1.5, _, 2.5]
            }
        """.trimIndent()
    }
}
