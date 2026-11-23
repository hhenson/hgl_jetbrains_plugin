package io.github.hhenson.hgl.parser;

import com.intellij.lang.PsiBuilder;
import com.intellij.openapi.util.Key;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import java.util.Map;
import io.github.hhenson.hgl.psi.HglTypes;

/** Contextual names and generic look-ahead used by Hgl.bnf. */
@SuppressWarnings("unused")
public final class HglParserUtil extends GeneratedParserUtilBase {

    private HglParserUtil() {
    }

    /**
     * Priority passed to the generated {@code expr(b, l, g)} to parse a size
     * expression: every binary operator at or below comparison precedence is
     * excluded, so the parse stops before a closing {@code >}. The number is
     * the generated priority of {@code comparison_expr}; keep it in step with
     * the order of alternatives in the {@code expr} rule.
     */
    static final int SIZE_EXPRESSION_PRIORITY = 3;

    /** Tokens a generic-argument list may contain (can_occur_in_generic_arguments). */
    private static final TokenSet GENERIC_ARGUMENT_TOKENS = TokenSet.create(
            HglTypes.LBRACKET, HglTypes.RBRACKET, HglTypes.IDENTIFIER, HglTypes.COLONCOLON, HglTypes.DOT, HglTypes.LT, HglTypes.GT, HglTypes.COMMA,
            HglTypes.NEWLINE, HglTypes.INT_LITERAL, HglTypes.FLOAT_LITERAL, HglTypes.STRING_LITERAL,
            HglTypes.TEMPORAL_LITERAL, HglTypes.PLACEHOLDER, HglTypes.TRUE_KW, HglTypes.FALSE_KW,
            HglTypes.NULL_KW, HglTypes.LPAREN, HglTypes.RPAREN, HglTypes.PLUS, HglTypes.MINUS,
            HglTypes.STAR, HglTypes.SLASH, HglTypes.FLOOR_SLASH, HglTypes.PERCENT,
            HglTypes.BOOL_KW, HglTypes.I64_KW, HglTypes.F64_KW, HglTypes.STR_KW, HglTypes.DATE_KW,
            HglTypes.TIME_KW, HglTypes.DATETIME_KW, HglTypes.DURATION_KW, HglTypes.CIVIL_DATETIME_KW,
            HglTypes.ZONED_DATETIME_KW, HglTypes.ZONED_TIME_KW, HglTypes.TIMEZONE_KW, HglTypes.QUESTION, HglTypes.BYTES_KW, HglTypes.ANY_KW,
            HglTypes.DICT_KW, HglTypes.RESULT_KW, HglTypes.ATOMIC_KW, HglTypes.TUPLE_KW,
            HglTypes.LIST_KW, HglTypes.SET_KW, HglTypes.ENDPOINT_KW, HglTypes.TENOR_KW, HglTypes.ROLLING_WINDOW_KW, HglTypes.REF_KW,
            HglTypes.SIGNAL_KW, HglTypes.DELTA_KW, HglTypes.ERROR_KW);

    private static final TokenSet TRIVIA = TokenSet.create(
            TokenType.WHITE_SPACE, HglTypes.LINE_COMMENT, HglTypes.BLOCK_COMMENT, HglTypes.DOC_COMMENT);

    /** Contextual keyword tokens by name; the grammar passes the name because Grammar-Kit passes a token reference as a parser. */
    private static final Map<String, IElementType> CONTEXTUAL_KEYWORDS = Map.ofEntries(
            Map.entry("APPLICATION_KW", HglTypes.APPLICATION_KW), Map.entry("WITHOUT_KW", HglTypes.WITHOUT_KW),
            Map.entry("INTEGRATION_TEST_KW", HglTypes.INTEGRATION_TEST_KW), Map.entry("TIMEOUT_KW", HglTypes.TIMEOUT_KW),
            Map.entry("SUPPRESS_WARNING_KW", HglTypes.SUPPRESS_WARNING_KW),
            Map.entry("CONST_KW", HglTypes.CONST_KW), Map.entry("TYPE_KW", HglTypes.TYPE_KW),
            Map.entry("SWITCH_KW", HglTypes.SWITCH_KW), Map.entry("CASE_KW", HglTypes.CASE_KW),
            Map.entry("DEFAULT_KW", HglTypes.DEFAULT_KW), Map.entry("RAISES_KW", HglTypes.RAISES_KW),
            Map.entry("IN_KW", HglTypes.IN_KW), Map.entry("NATIVE_KW", HglTypes.NATIVE_KW), Map.entry("EACH_KW", HglTypes.EACH_KW),
            Map.entry("PROPERTIES_KW", HglTypes.PROPERTIES_KW), Map.entry("UNBOUNDED_KW", HglTypes.UNBOUNDED_KW),
            Map.entry("DELTA_KW", HglTypes.DELTA_KW), Map.entry("ATOMIC_KW", HglTypes.ATOMIC_KW),
            Map.entry("TUPLE_KW", HglTypes.TUPLE_KW), Map.entry("LIST_KW", HglTypes.LIST_KW),
            Map.entry("SET_KW", HglTypes.SET_KW),
            Map.entry("ROLLING_WINDOW_KW", HglTypes.ROLLING_WINDOW_KW), Map.entry("REF_KW", HglTypes.REF_KW),
            Map.entry("SIGNAL_KW", HglTypes.SIGNAL_KW));

    private static IElementType keywordType(String name) {
        IElementType type = CONTEXTUAL_KEYWORDS.get(name);
        if (type == null) throw new IllegalArgumentException("unknown contextual keyword token " + name);
        return type;
    }

    private static boolean isIdentifierWithText(PsiBuilder b, IElementType keyword) {
        if (b.getTokenType() == keyword) return true;
        if (b.getTokenType() != HglTypes.IDENTIFIER) return false;
        String text = b.getTokenText();
        return text != null && text.contentEquals(keyword.toString());
    }

    private static boolean remapAndAdvance(PsiBuilder b, IElementType keyword) {
        b.remapCurrentToken(keyword);
        b.advanceLexer();
        return true;
    }

    /** Consume the identifier spelled like {@code keyword} and remap it to that token type. */
    public static boolean kw(PsiBuilder b, int level, String keywordToken) {
        IElementType keyword = keywordType(keywordToken);
        if (!isIdentifierWithText(b, keyword)) return false;
        if (keyword == HglTypes.CONST_KW && b.lookAhead(1) != HglTypes.FN_KW &&
                b.lookAhead(1) != HglTypes.IDENTIFIER) return false;
        return remapAndAdvance(b, keyword);
    }

    /** {@link #kw} restricted to an identifier directly followed by {@code <}. */
    public static boolean typeKw(PsiBuilder b, int level, String keywordToken) {
        IElementType keyword = keywordType(keywordToken);
        if (!isIdentifierWithText(b, keyword)) return false;
        if (b.lookAhead(1) != HglTypes.LT) return false;
        return remapAndAdvance(b, keyword);
    }

    /** Alias of {@link #typeKw} for `delta<`, kept separate so the grammar reads as the guide does. */
    public static boolean kwBeforeLt(PsiBuilder b, int level, String keywordToken) {
        return typeKw(b, level, keywordToken);
    }

    /** Predicate: the current token is the identifier spelled like {@code keyword}. Consumes nothing. */
    public static boolean isKw(PsiBuilder b, int level, String keywordToken) {
        return isIdentifierWithText(b, keywordType(keywordToken));
    }

    /** LEX-27: an opening delimiter touches the name or expression before it. */
    public static boolean adjacent(PsiBuilder b, int level) {
        IElementType previous = b.rawLookup(-1);
        return previous != null && previous != HglTypes.NEWLINE &&
                !TRIVIA.contains(previous);
    }

    private static final Key<Boolean> GROUPED = Key.create("hgl.layout.grouped");

    public static boolean inGroup(PsiBuilder b, int level, Parser parser) {
        return withLayout(b, level, parser, true);
    }

    public static boolean inBlock(PsiBuilder b, int level, Parser parser) {
        return withLayout(b, level, parser, false);
    }

    private static boolean withLayout(PsiBuilder b, int level, Parser parser, boolean grouped) {
        Boolean previous = b.getUserData(GROUPED);
        if (Boolean.TRUE.equals(previous) == grouped) return parser.parse(b, level);
        b.putUserData(GROUPED, grouped);
        try {
            return parser.parse(b, level);
        } finally {
            b.putUserData(GROUPED, previous);
        }
    }

    public static boolean bodyBraceAhead(PsiBuilder b, int level) {
        return b.getTokenType() == HglTypes.LBRACE &&
                (Boolean.TRUE.equals(b.getUserData(GROUPED)) || precedingBreaks(b) < 2);
    }

    /** A multiline comment can separate statements even when the lexer has no NEWLINE token there. */
    public static boolean afterCommentBreak(PsiBuilder b, int level) {
        b.getTokenType(); // Skip leading whitespace and comments before examining them.
        return precedingBreaks(b) > 0;
    }

    /** LEX-5/21: count up to two logical breaks. One multiline comment contributes one. */
    private static int precedingBreaks(PsiBuilder b) {
        int breaks = 0;
        for (int i = -1; breaks < 2; i--) {
            IElementType token = b.rawLookup(i);
            if (token == HglTypes.NEWLINE) {
                breaks++;
            } else if (token == HglTypes.BLOCK_COMMENT || token == HglTypes.DOC_COMMENT) {
                CharSequence source = b.getOriginalText();
                int end = b.rawTokenTypeStart(i + 1);
                for (int j = b.rawTokenTypeStart(i); j < end; j++) {
                    if (source.charAt(j) == '\n' || source.charAt(j) == '\r') {
                        breaks++;
                        break;
                    }
                }
            } else if (token == null || !TRIVIA.contains(token)) {
                break;
            }
        }
        return breaks;
    }

    private static final Key<Integer> HEADER_START = Key.create("hgl.header.start");

    public static boolean header(PsiBuilder b, int level, Parser parser) {
        Integer previous = b.getUserData(HEADER_START);
        b.putUserData(HEADER_START, b.rawTokenIndex());
        try {
            return parser.parse(b, level);
        } finally {
            b.putUserData(HEADER_START, previous);
        }
    }

    /** CTRL-9: outside a group, a header's first non-operator brace opens the body. */
    public static boolean braceInsideExpression(PsiBuilder b, int level) {
        Integer start = b.getUserData(HEADER_START);
        if (start == null) return true;
        int depth = 0;
        for (int i = start - b.rawTokenIndex(); i < 0; i++) {
            IElementType token = b.rawLookup(i);
            if (token == HglTypes.LPAREN || token == HglTypes.LBRACKET || token == HglTypes.LBRACE) depth++;
            if (token == HglTypes.RPAREN || token == HglTypes.RBRACKET || token == HglTypes.RBRACE) depth--;
        }
        return depth > 0;
    }

    private static final Key<Boolean> INTEGRATION_BODY = Key.create("hgl.test.body");

    public static boolean integrationBlock(PsiBuilder b, int level, Parser block) {
        Boolean previous = b.getUserData(INTEGRATION_BODY);
        b.putUserData(INTEGRATION_BODY, true);
        try {
            return block.parse(b, level);
        } finally {
            b.putUserData(INTEGRATION_BODY, previous);
        }
    }

    public static boolean textIs(PsiBuilder b, int level, String name) {
        return name.equals(b.getTokenText());
    }

    public static boolean inIntegrationBody(PsiBuilder b, int level) {
        return Boolean.TRUE.equals(b.getUserData(INTEGRATION_BODY));
    }

    /** EXPR-1/10: configured unary operators bind above multiplicative operators. */
    public static boolean unaryExpression(PsiBuilder b, int level) {
        if (b.getTokenType() != HglTypes.MINUS && b.getTokenType() != HglTypes.BANG) return false;
        b.advanceLexer();
        operatorOptions(b, level + 1);
        HglParser.nls(b, level + 1);
        if (!HglParser.expr(b, level + 1, 5)) b.error("operand expected");
        return true;
    }

    /** Keep configured operands outside Grammar-Kit's expression-group expansion. */
    public static boolean operatorOptions(PsiBuilder b, int level) {
        return HglParser.operator_configuration(b, level + 1);
    }

    /** Parse an expression at additive precedence and above (size_expression). */
    public static boolean sizeExpr(PsiBuilder b, int level) {
        return HglParser.expr(b, level + 1, SIZE_EXPRESSION_PRIORITY);
    }

    /**
     * Decide whether {@code name<} (or {@code name::name<}) at the current
     * position opens an applied struct constructor: every token up to the
     * matching {@code >} must be one a generic-argument list can contain, a
     * parenthesised group is skipped by balance alone, a newline is admitted
     * only after {@code <} or {@code ,} or before {@code >} or {@code ,}, and
     * the closing delimiter precedes call arguments or configuration, or ends a callable argument.
     * Consumes nothing.
     */
    public static boolean appliedConstructorAhead(PsiBuilder b, int level) {
        return genericAhead(b, false);
    }

    public static boolean functionReferenceAhead(PsiBuilder b, int level) {
        return genericAhead(b, true);
    }

    private static boolean genericAhead(PsiBuilder b, boolean argumentReference) {
        if (b.getTokenType() != HglTypes.IDENTIFIER) return false;
        RawCursor cursor = new RawCursor(b);
        boolean touchesGeneric = cursor.followedBy(HglTypes.LT);
        cursor.next(); // past the identifier
        if (cursor.type() == HglTypes.COLONCOLON) {
            cursor.next();
            if (cursor.type() != HglTypes.IDENTIFIER) return false;
            touchesGeneric = cursor.followedBy(HglTypes.LT);
            cursor.next();
        }
        if (!touchesGeneric) return false;

        int depth = 0;
        int parens = 0;
        IElementType previous = HglTypes.LT;
        for (; cursor.type() != null; cursor.next()) {
            IElementType kind = cursor.type();
            if (kind == null) return false;
            if (parens > 0) {
                if (kind == HglTypes.LBRACE || kind == HglTypes.RBRACE) return false;
                if (kind == HglTypes.LPAREN) parens++;
                if (kind == HglTypes.RPAREN) parens--;
                continue;
            }
            if (kind == HglTypes.NEWLINE) {
                if (previous == HglTypes.LT || previous == HglTypes.COMMA) continue;
                IElementType next = cursor.peekPastNewlines();
                if (next == HglTypes.GT || next == HglTypes.COMMA) continue;
                return false;
            }
            if (!GENERIC_ARGUMENT_TOKENS.contains(kind)) return false;
            if (kind == HglTypes.LPAREN) {
                parens++;
            } else if (kind == HglTypes.RPAREN) {
                return false;
            } else if (kind == HglTypes.LT) {
                depth++;
            } else if (kind == HglTypes.GT) {
                depth--;
                if (depth == 0) {
                    cursor.next();
                    IElementType next = cursor.type();
                    if (argumentReference) {
                        if (next == HglTypes.NEWLINE) next = cursor.peekPastNewlines();
                        return next == HglTypes.COMMA || next == HglTypes.RPAREN;
                    }
                    return next == HglTypes.LPAREN || next == HglTypes.LBRACE;
                }
            }
            previous = kind;
        }
        return false;
    }

    /** A forward-only cursor over the raw token stream that skips whitespace and comments. */
    private static final class RawCursor {
        private final PsiBuilder builder;
        private int offset = 0;

        RawCursor(PsiBuilder builder) {
            this.builder = builder;
        }

        boolean followedBy(IElementType token) {
            return builder.rawLookup(offset + 1) == token;
        }

        IElementType type() {
            return builder.rawLookup(offset);
        }

        void next() {
            do {
                offset++;
                if ((offset & 255) == 0) com.intellij.openapi.progress.ProgressManager.checkCanceled();
            } while (TRIVIA.contains(builder.rawLookup(offset)));
        }

        /** The first token type after the current run of newlines, without moving. */
        IElementType peekPastNewlines() {
            int probe = offset;
            IElementType kind;
            do {
                probe++;
                kind = builder.rawLookup(probe);
            } while (kind == HglTypes.NEWLINE || TRIVIA.contains(kind));
            return kind;
        }
    }
}
