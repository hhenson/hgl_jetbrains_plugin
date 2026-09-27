package io.github.hhenson.hgl.parser;

import com.intellij.lang.PsiBuilder;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import java.util.Map;
import io.github.hhenson.hgl.psi.HglTypes;

/**
 * External rules used by {@code Hgl.bnf}. Each one mirrors a contextual
 * decision the compiler's grammar makes while encoding its token stream
 * ({@code token_grammar.cpp}, {@code encode} and
 * {@code looks_like_applied_constructor}).
 */
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
            HglTypes.IDENTIFIER, HglTypes.COLONCOLON, HglTypes.LT, HglTypes.GT, HglTypes.COMMA,
            HglTypes.NEWLINE, HglTypes.INT_LITERAL, HglTypes.FLOAT_LITERAL, HglTypes.STRING_LITERAL,
            HglTypes.TEMPORAL_LITERAL, HglTypes.PLACEHOLDER, HglTypes.TRUE_KW, HglTypes.FALSE_KW,
            HglTypes.NULL_KW, HglTypes.LPAREN, HglTypes.RPAREN, HglTypes.PLUS, HglTypes.MINUS,
            HglTypes.STAR, HglTypes.SLASH, HglTypes.FLOOR_SLASH, HglTypes.PERCENT,
            HglTypes.BOOL_KW, HglTypes.I64_KW, HglTypes.F64_KW, HglTypes.STR_KW, HglTypes.DATE_KW,
            HglTypes.TIME_KW, HglTypes.DATETIME_KW, HglTypes.DURATION_KW, HglTypes.CIVIL_DATETIME_KW,
            HglTypes.ZONED_DATETIME_KW, HglTypes.ZONED_TIME_KW, HglTypes.TIMEZONE_KW);

    private static final TokenSet TRIVIA = TokenSet.create(
            TokenType.WHITE_SPACE, HglTypes.LINE_COMMENT, HglTypes.BLOCK_COMMENT);

    /** Upper bound on the raw tokens scanned by the applied-constructor look-ahead. */
    private static final int LOOK_AHEAD_LIMIT = 512;

    /** Contextual keyword tokens by name; the grammar passes the name because Grammar-Kit passes a token reference as a parser. */
    private static final Map<String, IElementType> CONTEXTUAL_KEYWORDS = Map.ofEntries(
            Map.entry("IN_KW", HglTypes.IN_KW), Map.entry("NATIVE_KW", HglTypes.NATIVE_KW),
            Map.entry("THROWS_KW", HglTypes.THROWS_KW), Map.entry("EACH_KW", HglTypes.EACH_KW),
            Map.entry("PROPERTIES_KW", HglTypes.PROPERTIES_KW), Map.entry("UNBOUNDED_KW", HglTypes.UNBOUNDED_KW),
            Map.entry("DELTA_KW", HglTypes.DELTA_KW), Map.entry("ATOMIC_KW", HglTypes.ATOMIC_KW),
            Map.entry("TUPLE_KW", HglTypes.TUPLE_KW), Map.entry("LIST_KW", HglTypes.LIST_KW),
            Map.entry("SET_KW", HglTypes.SET_KW), Map.entry("MAP_KW", HglTypes.MAP_KW),
            Map.entry("ROLLING_KW", HglTypes.ROLLING_KW), Map.entry("REF_KW", HglTypes.REF_KW),
            Map.entry("SIGNAL_KW", HglTypes.SIGNAL_KW), Map.entry("SCHEMA_KW", HglTypes.SCHEMA_KW));

    private static IElementType keywordType(String name) {
        IElementType type = CONTEXTUAL_KEYWORDS.get(name);
        if (type == null) throw new IllegalArgumentException("unknown contextual keyword token " + name);
        return type;
    }

    private static boolean isIdentifierWithText(PsiBuilder b, IElementType keyword) {
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
     * the closing {@code >} must be directly followed by {@code (}.
     * Consumes nothing.
     */
    public static boolean appliedConstructorAhead(PsiBuilder b, int level) {
        if (b.getTokenType() != HglTypes.IDENTIFIER) return false;
        RawCursor cursor = new RawCursor(b);
        cursor.next(); // past the identifier
        if (cursor.type() == HglTypes.COLONCOLON) {
            cursor.next();
            if (cursor.type() != HglTypes.IDENTIFIER) return false;
            cursor.next();
        }
        if (cursor.type() != HglTypes.LT) return false;

        int depth = 0;
        int parens = 0;
        IElementType previous = HglTypes.LT;
        for (; !cursor.exhausted(); cursor.next()) {
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
                    return cursor.type() == HglTypes.LPAREN;
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
        private int scanned = 0;

        RawCursor(PsiBuilder builder) {
            this.builder = builder;
        }

        IElementType type() {
            return builder.rawLookup(offset);
        }

        boolean exhausted() {
            return scanned > LOOK_AHEAD_LIMIT || type() == null;
        }

        void next() {
            do {
                offset++;
                scanned++;
            } while (scanned <= LOOK_AHEAD_LIMIT && TRIVIA.contains(builder.rawLookup(offset)));
        }

        /** The first token type after the current run of newlines, without moving. */
        IElementType peekPastNewlines() {
            int probe = offset;
            int steps = 0;
            IElementType kind;
            do {
                probe++;
                steps++;
                kind = builder.rawLookup(probe);
            } while (steps <= LOOK_AHEAD_LIMIT && (kind == HglTypes.NEWLINE || TRIVIA.contains(kind)));
            return kind;
        }
    }
}
