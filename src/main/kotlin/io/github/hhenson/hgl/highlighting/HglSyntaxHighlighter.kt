package io.github.hhenson.hgl.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.hhenson.hgl.lexer.HglLexerAdapter
import io.github.hhenson.hgl.psi.HglTokenSets
import io.github.hhenson.hgl.psi.HglTypes

/** Colour keys. Lexer-driven keys are applied here; the semantic ones by [HglAnnotator]. */
object HglColors {
    @JvmField val KEYWORD: TextAttributesKey = createTextAttributesKey("HGL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val TYPE_KEYWORD: TextAttributesKey = createTextAttributesKey("HGL_TYPE_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val CONTEXTUAL_KEYWORD: TextAttributesKey = createTextAttributesKey("HGL_CONTEXTUAL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val LITERAL_KEYWORD: TextAttributesKey = createTextAttributesKey("HGL_LITERAL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val NUMBER: TextAttributesKey = createTextAttributesKey("HGL_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    @JvmField val TEMPORAL_LITERAL: TextAttributesKey = createTextAttributesKey("HGL_TEMPORAL_LITERAL", DefaultLanguageHighlighterColors.NUMBER)
    @JvmField val STRING: TextAttributesKey = createTextAttributesKey("HGL_STRING", DefaultLanguageHighlighterColors.STRING)
    @JvmField val PLACEHOLDER: TextAttributesKey = createTextAttributesKey("HGL_PLACEHOLDER", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val LINE_COMMENT: TextAttributesKey = createTextAttributesKey("HGL_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    @JvmField val DOC_COMMENT: TextAttributesKey = createTextAttributesKey("HGL_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)
    @JvmField val BLOCK_COMMENT: TextAttributesKey = createTextAttributesKey("HGL_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
    @JvmField val OPERATOR: TextAttributesKey = createTextAttributesKey("HGL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    @JvmField val PARENTHESES: TextAttributesKey = createTextAttributesKey("HGL_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
    @JvmField val BRACES: TextAttributesKey = createTextAttributesKey("HGL_BRACES", DefaultLanguageHighlighterColors.BRACES)
    @JvmField val BRACKETS: TextAttributesKey = createTextAttributesKey("HGL_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    @JvmField val COMMA: TextAttributesKey = createTextAttributesKey("HGL_COMMA", DefaultLanguageHighlighterColors.COMMA)
    @JvmField val DOT: TextAttributesKey = createTextAttributesKey("HGL_DOT", DefaultLanguageHighlighterColors.DOT)
    @JvmField val SEMICOLON: TextAttributesKey = createTextAttributesKey("HGL_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)
    @JvmField val EMBEDDED_CPP: TextAttributesKey = createTextAttributesKey("HGL_EMBEDDED_CPP", DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR)
    @JvmField val BAD_CHARACTER: TextAttributesKey = createTextAttributesKey("HGL_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

    // Semantic keys (annotator).
    @JvmField val FUNCTION_DECLARATION: TextAttributesKey = createTextAttributesKey("HGL_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    @JvmField val FUNCTION_CALL: TextAttributesKey = createTextAttributesKey("HGL_FUNCTION_CALL", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    @JvmField val OPERATOR_DECLARATION: TextAttributesKey = createTextAttributesKey("HGL_OPERATOR_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    @JvmField val STRUCT_NAME: TextAttributesKey = createTextAttributesKey("HGL_STRUCT_NAME", DefaultLanguageHighlighterColors.CLASS_NAME)
    @JvmField val STRUCT_REFERENCE: TextAttributesKey = createTextAttributesKey("HGL_STRUCT_REFERENCE", DefaultLanguageHighlighterColors.CLASS_REFERENCE)
    @JvmField val TYPE_PARAMETER: TextAttributesKey = createTextAttributesKey("HGL_TYPE_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
    @JvmField val PARAMETER: TextAttributesKey = createTextAttributesKey("HGL_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
    @JvmField val LOCAL_VARIABLE: TextAttributesKey = createTextAttributesKey("HGL_LOCAL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    @JvmField val STATE_VARIABLE: TextAttributesKey = createTextAttributesKey("HGL_STATE_VARIABLE", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    @JvmField val FIELD: TextAttributesKey = createTextAttributesKey("HGL_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    @JvmField val INJECTABLE: TextAttributesKey = createTextAttributesKey("HGL_INJECTABLE", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
    @JvmField val INTRINSIC: TextAttributesKey = createTextAttributesKey("HGL_INTRINSIC", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
    @JvmField val NAMED_ARGUMENT: TextAttributesKey = createTextAttributesKey("HGL_NAMED_ARGUMENT", DefaultLanguageHighlighterColors.PARAMETER)
    @JvmField val MODULE_PATH: TextAttributesKey = createTextAttributesKey("HGL_MODULE_PATH", DefaultLanguageHighlighterColors.IDENTIFIER)
    @JvmField val TEST_NAME: TextAttributesKey = createTextAttributesKey("HGL_TEST_NAME", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    @JvmField val IDENTIFIER: TextAttributesKey = createTextAttributesKey("HGL_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
}

class HglSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = HglLexerAdapter()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        val key = when {
            tokenType == null -> return emptyArray()
            HglTokenSets.HARD_KEYWORDS.contains(tokenType) -> HglColors.KEYWORD
            HglTokenSets.TYPE_KEYWORDS.contains(tokenType) -> HglColors.TYPE_KEYWORD
            HglTokenSets.LITERAL_KEYWORDS.contains(tokenType) -> HglColors.LITERAL_KEYWORD
            HglTokenSets.CONTEXTUAL_KEYWORDS.contains(tokenType) -> HglColors.CONTEXTUAL_KEYWORD
            HglTokenSets.NUMBERS.contains(tokenType) -> HglColors.NUMBER
            tokenType == HglTypes.TEMPORAL_LITERAL -> HglColors.TEMPORAL_LITERAL
            tokenType == HglTypes.STRING_LITERAL -> HglColors.STRING
            tokenType == HglTypes.PLACEHOLDER -> HglColors.PLACEHOLDER
            tokenType == HglTypes.LINE_COMMENT -> HglColors.LINE_COMMENT
            tokenType == HglTypes.DOC_COMMENT -> HglColors.DOC_COMMENT
            tokenType == HglTypes.BLOCK_COMMENT -> HglColors.BLOCK_COMMENT
            tokenType == HglTypes.LPAREN || tokenType == HglTypes.RPAREN -> HglColors.PARENTHESES
            tokenType == HglTypes.LBRACE || tokenType == HglTypes.RBRACE -> HglColors.BRACES
            tokenType == HglTypes.LBRACKET || tokenType == HglTypes.RBRACKET -> HglColors.BRACKETS
            tokenType == HglTypes.COMMA -> HglColors.COMMA
            tokenType == HglTypes.DOT -> HglColors.DOT
            tokenType == HglTypes.SEMICOLON -> HglColors.SEMICOLON
            HglTokenSets.OPERATORS.contains(tokenType) -> HglColors.OPERATOR
            HglTokenSets.EMBEDDED_CPP.contains(tokenType) -> HglColors.EMBEDDED_CPP
            tokenType == TokenType.BAD_CHARACTER -> HglColors.BAD_CHARACTER
            else -> return emptyArray()
        }
        return pack(key)
    }
}

class HglSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = HglSyntaxHighlighter()
}
