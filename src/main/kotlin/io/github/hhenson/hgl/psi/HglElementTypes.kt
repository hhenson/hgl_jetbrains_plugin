package io.github.hhenson.hgl.psi

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.hhenson.hgl.HglLanguage

class HglTokenType(debugName: String) : IElementType(debugName, HglLanguage) {
    override fun toString(): String = super.toString()
}

class HglElementType(debugName: String) : IElementType(debugName, HglLanguage)

object HglFileElementType : IFileElementType("HGL_FILE", HglLanguage)

/** Token groupings shared by the highlighter, brace matcher, completion and word scanner. */
object HglTokenSets {
    @JvmField
    val HARD_KEYWORDS: TokenSet = TokenSet.create(
        HglTypes.MODULE_KW, HglTypes.PART_KW, HglTypes.USE_KW, HglTypes.AS_KW, HglTypes.EXPORT_KW,
        HglTypes.ABSTRACT_KW, HglTypes.IMPL_KW, HglTypes.INSTANTIATE_KW, HglTypes.OPERATOR_KW,
        HglTypes.ENUM_KW, HglTypes.FN_KW, HglTypes.STRUCT_KW, HglTypes.REQUIRES_KW,
        HglTypes.IS_KW, HglTypes.LET_KW, HglTypes.VAR_KW, HglTypes.STATE_KW, HglTypes.CACHE_KW,
        HglTypes.INJECT_KW, HglTypes.RETURN_KW, HglTypes.IF_KW, HglTypes.ELSE_KW, HglTypes.START_KW,
        HglTypes.WHEN_KW, HglTypes.STOP_KW, HglTypes.FOR_KW, HglTypes.WHILE_KW, HglTypes.YIELD_KW,
        HglTypes.TEST_KW, HglTypes.ASSERT_KW, HglTypes.EVAL_KW, HglTypes.RUN_KW, HglTypes.REF_KW,
    )

    @JvmField
    val LITERAL_KEYWORDS: TokenSet = TokenSet.create(HglTypes.TRUE_KW, HglTypes.FALSE_KW, HglTypes.NULL_KW)

    @JvmField
    val TYPE_KEYWORDS: TokenSet = TokenSet.create(
        HglTypes.BYTES_KW, HglTypes.ANY_KW, HglTypes.DICT_KW, HglTypes.RESULT_KW, HglTypes.ERROR_KW,
        HglTypes.ATOMIC_KW, HglTypes.TUPLE_KW, HglTypes.LIST_KW, HglTypes.SET_KW,
        HglTypes.ENDPOINT_KW, HglTypes.TENOR_KW, HglTypes.ROLLING_WINDOW_KW, HglTypes.SIGNAL_KW, HglTypes.DELTA_KW, HglTypes.BOOL_KW, HglTypes.I64_KW, HglTypes.F64_KW, HglTypes.STR_KW, HglTypes.DATE_KW,
        HglTypes.TIME_KW, HglTypes.DATETIME_KW, HglTypes.DURATION_KW, HglTypes.CIVIL_DATETIME_KW,
        HglTypes.ZONED_DATETIME_KW, HglTypes.ZONED_TIME_KW, HglTypes.TIMEZONE_KW,
    )

    /** Identifiers the parser remaps when the grammar demands the word. */
    @JvmField
    val CONTEXTUAL_KEYWORDS: TokenSet = TokenSet.create(
        HglTypes.CONST_KW, HglTypes.TYPE_KW, HglTypes.SWITCH_KW, HglTypes.CASE_KW,
        HglTypes.DEFAULT_KW, HglTypes.RAISES_KW, HglTypes.IN_KW, HglTypes.NATIVE_KW, HglTypes.EACH_KW, HglTypes.PROPERTIES_KW,
        HglTypes.APPLICATION_KW, HglTypes.WITHOUT_KW, HglTypes.INTEGRATION_TEST_KW,
        HglTypes.TIMEOUT_KW, HglTypes.SUPPRESS_WARNING_KW, HglTypes.UNBOUNDED_KW,
    )

    /** Every token the lexer can emit for a reserved word. */
    @JvmField
    val KEYWORDS: TokenSet = TokenSet.orSet(HARD_KEYWORDS, LITERAL_KEYWORDS, TYPE_KEYWORDS)

    /** Tokens that may spell a `name` (identifier or reserved word). */
    @JvmField
    val NAME_TOKENS: TokenSet = TokenSet.orSet(TokenSet.create(HglTypes.IDENTIFIER), KEYWORDS)

    @JvmField
    val COMMENTS: TokenSet = TokenSet.create(HglTypes.LINE_COMMENT, HglTypes.BLOCK_COMMENT, HglTypes.DOC_COMMENT)

    @JvmField
    val STRINGS: TokenSet = TokenSet.create(HglTypes.STRING_LITERAL)

    @JvmField
    val NUMBERS: TokenSet = TokenSet.create(HglTypes.INT_LITERAL, HglTypes.FLOAT_LITERAL)

    @JvmField
    val OPERATORS: TokenSet = TokenSet.create(
        HglTypes.QUESTION, HglTypes.SPACESHIP, HglTypes.FLOOR_SLASH_EQ, HglTypes.PERCENT_EQ,
        HglTypes.LT, HglTypes.GT, HglTypes.LE, HglTypes.GE, HglTypes.EQEQ, HglTypes.NEQ,
        HglTypes.PLUS, HglTypes.MINUS, HglTypes.STAR, HglTypes.SLASH, HglTypes.FLOOR_SLASH,
        HglTypes.PERCENT, HglTypes.BANG, HglTypes.ANDAND, HglTypes.OROR, HglTypes.EQ,
        HglTypes.PLUS_EQ, HglTypes.MINUS_EQ, HglTypes.STAR_EQ, HglTypes.SLASH_EQ,
        HglTypes.ARROW, HglTypes.FAT_ARROW, HglTypes.COLONCOLON, HglTypes.ELLIPSIS, HglTypes.RANGE, HglTypes.COLON,
    )

}
