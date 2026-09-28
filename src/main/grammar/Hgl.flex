package io.github.hhenson.hgl.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static io.github.hhenson.hgl.psi.HglTypes.*;

/*
 * HGL lexer. Mirrors `language/src/syntax/lexer.cpp` in the hgraph C++
 * compiler and "Lexical rules" / "Literals" in the hgraph_spec developer
 * guide (syntax-and-semantics.md). See docs/design.md, "Lexer".
 *
 * Deliberate differences from the compiler lexer:
 *  - a run of line terminators is NOT merged into one token; each `\n` is a
 *    NEWLINE and the grammar uses NEWLINE+ / NEWLINE* where the compiler's
 *    `newline` / `newlines` productions appear;
 *  - comments are tokens (LINE_COMMENT, BLOCK_COMMENT) rather than trivia
 *    records, so the parser skips them as comment tokens;
 *  - `include` after `cpp` is the dedicated CPP_INCLUDE token; the compiler
 *    lexes it as an identifier and its grammar re-encodes it contextually;
 *  - `;` is a SEMICOLON token everywhere. The compiler lexer reports it as
 *    an error, but the spec's native_contract_body admits it, so the grammar
 *    decides where it is legal.
 */
%%

%{
  /** Start offset of the opaque C++ token being scanned. */
  private int cppStart = 0;
  /** Nesting depth of the delimiter pair being balanced inside a C++ token. */
  private int cppDepth = 0;
  /** Delimiter of the C++ raw string literal being skipped: "tag" for R"tag(...)tag". */
  private String rawDelimiter = "";
  /** The C++ state to return to when the raw string ends. */
  private int rawReturnState = 0;

  public _HglLexer() {
    this((java.io.Reader) null);
  }

  /** Finish an opaque C++ token that began at {@link #cppStart}. */
  private IElementType cppToken(IElementType type) {
    zzStartRead = cppStart;
    cppDepth = 0;
    return type;
  }

  private void beginRawString(int returnState) {
    CharSequence text = yytext();               // R"delim(
    rawDelimiter = text.subSequence(2, text.length() - 1).toString();
    rawReturnState = returnState;
    yybegin(IN_CPP_RAW);
  }

  private void endRawStringIfMatched() {
    CharSequence text = yytext();               // )delim"
    String delimiter = text.subSequence(1, text.length() - 1).toString();
    if (delimiter.equals(rawDelimiter)) {
      yybegin(rawReturnState);
    } else {
      yypushback(yylength() - 1);
    }
  }
%}

%public
%class _HglLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode

%xstate IN_CPP_HEAD
%xstate IN_CPP_INCLUDE_HEADER
%xstate IN_CPP_PARAMS
%xstate IN_CPP_BETWEEN
%xstate IN_CPP_BODY
%xstate IN_CPP_RAW

NEWLINE = \n
WHITE_SPACE = [ \t\r\f]+
LINE_COMMENT = "#" [^\n]*
BLOCK_COMMENT = "/*" ( [^*] | \*+ [^*/] )* \*+ "/"
UNTERMINATED_BLOCK_COMMENT = "/*" ( [^*] | \*+ [^*/] )* \**

IDENTIFIER = [A-Za-z_] [A-Za-z0-9_]*
DIGITS = [0-9]+
EXPONENT = [eE] [+-]? {DIGITS}
FLOAT_LITERAL = {DIGITS} "." {DIGITS} {EXPONENT}? | {DIGITS} {EXPONENT}
INT_LITERAL = {DIGITS}

// A number directly followed by a letter is a duration literal candidate.
// The valid shape is checked here; any other digit/letter run (`5min`,
// `1e5m`, `2x`) is one bad token, as in the compiler.
DURATION_PART = {DIGITS} ( "." {DIGITS} )? ( "ms" | "us" | "d" | "h" | "m" | "s" )
DURATION_LITERAL = {DURATION_PART}+
NUMBER_RUN = [0-9] ( [A-Za-z0-9_] | "." [0-9] )*

STRING_LITERAL = \" ( [^\"\\\n] | \\ [^\n] )* \"?

// `@` literals are scanned by shape: `@[zone]`, `@date[Ttime[offset]][[zone]]`,
// or `@time[[zone]]`. Validation is the compiler's.
CLOCK = {DIGITS} ":" {DIGITS} ( ":" {DIGITS} ( "." {DIGITS} )? )?
OFFSET = "Z" | [+-] [0-9] [0-9] ( ":" [0-9] [0-9] )?
ZONE = "[" [^\]\n]* "]"
DATE = {DIGITS} "-" {DIGITS} "-" {DIGITS}
TEMPORAL_AT = "@" ( {ZONE} | {DATE} ( "T" {CLOCK} {OFFSET}? )? {ZONE}? | {CLOCK} {ZONE}? )

CPP_HEADER = "<" [^>\n]+ ">" | \" [^\"\n]+ \"
CPP_LINE_COMMENT = "//" [^\n]*
CPP_BLOCK_COMMENT = "/*" ( [^*] | \*+ [^*/] )* \*+ "/"
CPP_STRING = \" ( [^\"\\\n] | \\ [^\n] )* \"?
CPP_CHAR = "'" ( [^'\\\n] | \\ [^\n] )* "'"?
CPP_RAW_OPEN = "R\"" [^ ()\\\t\r\n\"]{0,16} "("

%%

<YYINITIAL> {
  {WHITE_SPACE}                  { return WHITE_SPACE; }
  {NEWLINE}                      { return NEWLINE; }
  {LINE_COMMENT}                 { return LINE_COMMENT; }
  {BLOCK_COMMENT}                { return yytext().toString().startsWith("/**") && yylength() >= 5 ? DOC_COMMENT : BLOCK_COMMENT; }
  {UNTERMINATED_BLOCK_COMMENT}   { return BLOCK_COMMENT; }

  // Hard reserved words: the keyword table of the compiler's token.cpp.
  "module"                       { return MODULE_KW; }
  "part"                         { return PART_KW; }
  "use"                          { return USE_KW; }
  "as"                           { return AS_KW; }
  "export"                       { return EXPORT_KW; }
  "abstract"                     { return ABSTRACT_KW; }
  "impl"                         { return IMPL_KW; }
  "instantiate"                  { return INSTANTIATE_KW; }
  "operator"                     { return OPERATOR_KW; }
  "fn"                           { return FN_KW; }
  "cpp"                          { yybegin(IN_CPP_HEAD); return CPP_KW; }
  "struct"                       { return STRUCT_KW; }
  "const"                        { return CONST_KW; }
  "requires"                     { return REQUIRES_KW; }
  "is"                           { return IS_KW; }
  "let"                          { return LET_KW; }
  "var"                          { return VAR_KW; }
  "state"                        { return STATE_KW; }
  "cache"                        { return CACHE_KW; }
  "inject"                       { return INJECT_KW; }
  "return"                       { return RETURN_KW; }
  "if"                           { return IF_KW; }
  "else"                         { return ELSE_KW; }
  "start"                        { return START_KW; }
  "when"                         { return WHEN_KW; }
  "stop"                         { return STOP_KW; }
  "for"                          { return FOR_KW; }
  "test"                         { return TEST_KW; }
  "assert"                       { return ASSERT_KW; }
  "eval"                         { return EVAL_KW; }
  "true"                         { return TRUE_KW; }
  "false"                        { return FALSE_KW; }
  "null"                         { return NULL_KW; }
  "bool"                         { return BOOL_KW; }
  "i64"                          { return I64_KW; }
  "f64"                          { return F64_KW; }
  "str"                          { return STR_KW; }
  "date"                         { return DATE_KW; }
  "time"                         { return TIME_KW; }
  "datetime"                     { return DATETIME_KW; }
  "duration"                     { return DURATION_KW; }
  "civil_datetime"               { return CIVIL_DATETIME_KW; }
  "zoned_datetime"               { return ZONED_DATETIME_KW; }
  "zoned_time"                   { return ZONED_TIME_KW; }
  "timezone"                     { return TIMEZONE_KW; }

  "_"                            { return PLACEHOLDER; }
  {IDENTIFIER}                   { return IDENTIFIER; }

  {FLOAT_LITERAL}                { return FLOAT_LITERAL; }
  {INT_LITERAL}                  { return INT_LITERAL; }
  {DURATION_LITERAL}             { return TEMPORAL_LITERAL; }
  {NUMBER_RUN}                   { return BAD_CHARACTER; }
  {TEMPORAL_AT}                  { return TEMPORAL_LITERAL; }
  {STRING_LITERAL}               { return STRING_LITERAL; }

  "::"                           { return COLONCOLON; }
  ":"                            { return COLON; }
  "->"                           { return ARROW; }
  "-="                           { return MINUS_EQ; }
  "-"                            { return MINUS; }
  "=>"                           { return FAT_ARROW; }
  "=="                           { return EQEQ; }
  "="                            { return EQ; }
  "+="                           { return PLUS_EQ; }
  "+"                            { return PLUS; }
  "*="                           { return STAR_EQ; }
  "*"                            { return STAR; }
  "//"                           { return FLOOR_SLASH; }
  "/="                           { return SLASH_EQ; }
  "/"                            { return SLASH; }
  "!="                           { return NEQ; }
  "!"                            { return BANG; }
  "<="                           { return LE; }
  "<"                            { return LT; }
  ">="                           { return GE; }
  ">"                            { return GT; }
  "&&"                           { return ANDAND; }
  "||"                           { return OROR; }
  "("                            { return LPAREN; }
  ")"                            { return RPAREN; }
  "{"                            { return LBRACE; }
  "}"                            { return RBRACE; }
  "["                            { return LBRACKET; }
  "]"                            { return RBRACKET; }
  ","                            { return COMMA; }
  "..."                          { return ELLIPSIS; }
  "."                            { return DOT; }
  "%"                            { return PERCENT; }
  ";"                            { return SEMICOLON; }
  "@"                            { return BAD_CHARACTER; }

  [^]                            { return BAD_CHARACTER; }
}

// After `cpp`: either `include <header>` / `include "header"`, or an opaque
// balanced `( ... )` parameter list followed by an opaque `{ ... }` body.
// Line breaks are ordinary whitespace here, as in the compiler.
<IN_CPP_HEAD> {
  {WHITE_SPACE} | {NEWLINE}      { return WHITE_SPACE; }
  "include"                      { yybegin(IN_CPP_INCLUDE_HEADER); return CPP_INCLUDE; }
  "("                            { cppStart = zzStartRead; cppDepth = 1; yybegin(IN_CPP_PARAMS); }
  [^]                            { yypushback(1); yybegin(YYINITIAL); }
}

<IN_CPP_INCLUDE_HEADER> {
  {WHITE_SPACE} | {NEWLINE}      { return WHITE_SPACE; }
  {CPP_HEADER}                   { yybegin(YYINITIAL); return CPP_HEADER; }
  [^]                            { yypushback(1); yybegin(YYINITIAL); }
}

<IN_CPP_PARAMS> {
  {CPP_LINE_COMMENT}             { }
  {CPP_BLOCK_COMMENT}            { }
  {CPP_RAW_OPEN}                 { beginRawString(IN_CPP_PARAMS); }
  {CPP_STRING}                   { }
  {CPP_CHAR}                     { }
  "("                            { cppDepth++; }
  ")"                            { if (--cppDepth == 0) { yybegin(IN_CPP_BETWEEN); return cppToken(CPP_PARAMETER_LIST); } }
  [^]                            { }
  <<EOF>>                        { yybegin(YYINITIAL); return cppToken(BAD_CHARACTER); }
}

<IN_CPP_BETWEEN> {
  {WHITE_SPACE} | {NEWLINE}      { return WHITE_SPACE; }
  "{"                            { cppStart = zzStartRead; cppDepth = 1; yybegin(IN_CPP_BODY); }
  [^]                            { yypushback(1); yybegin(YYINITIAL); }
}

<IN_CPP_BODY> {
  {CPP_LINE_COMMENT}             { }
  {CPP_BLOCK_COMMENT}            { }
  {CPP_RAW_OPEN}                 { beginRawString(IN_CPP_BODY); }
  {CPP_STRING}                   { }
  {CPP_CHAR}                     { }
  "{"                            { cppDepth++; }
  "}"                            { if (--cppDepth == 0) { yybegin(YYINITIAL); return cppToken(CPP_BODY); } }
  [^]                            { }
  <<EOF>>                        { yybegin(YYINITIAL); return cppToken(BAD_CHARACTER); }
}

<IN_CPP_RAW> {
  ")" [^\"\n]{0,16} \"           { endRawStringIfMatched(); }
  [^]                            { }
  <<EOF>>                        { yybegin(YYINITIAL); return cppToken(BAD_CHARACTER); }
}
