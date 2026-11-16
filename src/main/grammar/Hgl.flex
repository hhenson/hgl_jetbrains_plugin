package io.github.hhenson.hgl.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static io.github.hhenson.hgl.psi.HglTypes.*;

/* Lexical forms from the pinned source-text.md. */
%%

%{
  public _HglLexer() { this((java.io.Reader) null); }
%}

%public
%class _HglLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode


NEWLINE = \r\n | \r | \n
WHITE_SPACE = [ \t]+
LINE_COMMENT = "#" [^\r\n]*
BLOCK_COMMENT = "/*" ( [^*] | \*+ [^*/] )* \*+ "/"
UNTERMINATED_BLOCK_COMMENT = "/*" ( [^*] | \*+ [^*/] )* \**

IDENTIFIER = [A-Za-z_] [A-Za-z0-9_]*
DIGITS = [0-9] ( _? [0-9] )*
EXPONENT = [eE] [+-]? {DIGITS}
FLOAT_LITERAL = {DIGITS} "." {DIGITS} {EXPONENT}? | {DIGITS} {EXPONENT}
INT_LITERAL = {DIGITS} | 0[xX][0-9a-fA-F](_?[0-9a-fA-F])* | 0[bB][01](_?[01])*

// A number directly followed by a letter is a duration literal candidate.
// The valid shape is checked here; any other digit/letter run (`5min`,
// `1e5m`, `2x`) is one bad token, as in the compiler.
DURATION_PART = {DIGITS} ( "." {DIGITS} )? ( "ms" | "us" | "d" | "h" | "m" | "s" )
DURATION_LITERAL = {DURATION_PART}+
NUMBER_RUN = [0-9] ( [A-Za-z0-9_] | "." [0-9] )*

TEXT_BLOCK = \"\"\" ( [^\"] | \" [^\"] | \"\" [^\"] )* \"\"\"
STRING_LITERAL = \" ( [^\"\\\n] | \\ [^\n] )* \"?

// `@` literals are scanned by shape: `@[zone]`, `@date[Ttime[offset]][[zone]]`,
// or `@time[[zone]]`. Validation is the compiler's.
CLOCK = {DIGITS} ":" {DIGITS} ( ":" {DIGITS} ( "." {DIGITS} )? )?
OFFSET = "Z" | [+-] [0-9] [0-9] ( ":" [0-9] [0-9] )?
ZONE = "[" [^\]\n]* "]"
DATE = {DIGITS} "-" {DIGITS} "-" {DIGITS}
TEMPORAL_AT = "@" ( {ZONE} | {DATE} ( "T" {CLOCK} {OFFSET}? )? {ZONE}? | {CLOCK} {ZONE}? )


%%

<YYINITIAL> {
  "\\" {NEWLINE} { return WHITE_SPACE; }
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
  "struct"                       { return STRUCT_KW; }
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
  "while"                        { return WHILE_KW; }
  "yield"                        { return YIELD_KW; }
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

  "enum" { return ENUM_KW; }
  "bytes" { return BYTES_KW; }
  "any" { return ANY_KW; }
  "atomic" { return ATOMIC_KW; }
  "tuple" { return TUPLE_KW; }
  "list" { return LIST_KW; }
  "set" { return SET_KW; }
  "dict" { return DICT_KW; }
  "rolling" { return ROLLING_KW; }
  "result" { return RESULT_KW; }
  "ref" { return REF_KW; }
  "signal" { return SIGNAL_KW; }
  "delta" { return DELTA_KW; }
  "error" { return ERROR_KW; }
  "_" { return PLACEHOLDER; }
  {IDENTIFIER}                   { return IDENTIFIER; }

  {FLOAT_LITERAL}                { return FLOAT_LITERAL; }
  {INT_LITERAL}                  { return INT_LITERAL; }
  {DURATION_LITERAL}             { return TEMPORAL_LITERAL; }
  {NUMBER_RUN}                   { return BAD_CHARACTER; }
  {TEMPORAL_AT}                  { return TEMPORAL_LITERAL; }
  {TEXT_BLOCK} { return STRING_LITERAL; }
  {STRING_LITERAL}               { return STRING_LITERAL; }

  "?" { return QUESTION; }
  "<=>" { return SPACESHIP; }
  "//=" { return FLOOR_SLASH_EQ; }
  "%=" { return PERCENT_EQ; }
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
  "@"                            { return AT; }

  [^]                            { return BAD_CHARACTER; }
}
