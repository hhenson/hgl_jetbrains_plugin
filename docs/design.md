# HGL JetBrains plugin: design

Status: v0.1, 2026-09-27. This document is the authoritative description of
the plugin's structure. Change it in the same commit as the code it
describes.

## Purpose and scope

One IntelliJ Platform plugin that gives CLion, PyCharm, RustRover and
IntelliJ IDEA language support for `.hgl` modules: syntax highlighting,
brace matching, comments, folding, a structure view, keyword completion,
in-file and cross-module navigation, find usages and rename. It depends only
on `com.intellij.modules.platform` and `com.intellij.modules.lang`, so the
same build installs on every JetBrains IDE of the targeted platform
generation (2026.2, build 262 and later).

The build compiles and tests against CLion. The unified IntelliJ IDEA
distribution (the only IDEA artifact since 2025.3) cannot open a light test
project, because its obfuscated Ultimate licensing startup activity fails
to instantiate under the test framework, so it is not used. Any IDE would
do for compilation since only platform APIs are referenced; the Plugin
Verifier checks the other three. `-PplatformLocalPath=<IDE app>` builds
against an installed IDE instead of downloading one.

Out of scope for v0.1, and deliberately so:

- **Diagnostics.** The plugin reports parse errors only. Type checking,
  phase classification, name-resolution errors and reserved-word checks are
  the compiler's; the plugin never contradicts it with a second opinion.
- **The provisional extensions.** `enum`, `switch` and `str(value)` are
  agreed but "not parsed" in the specification. They are not recognised
  here either; they fail as they fail in the compiler.
- **C++ language injection** into `cpp(...) { ... }` bodies (CLion only).
  A candidate for a later version, see "Future work".

## Sources of truth

| Concern | Authority | Mirrored by |
|---|---|---|
| Lexical rules, literals, reserved words | `hgraph_spec/language/docs/developer-guide/syntax-and-semantics.md`, "Lexical rules" and "Literals"; the keyword table in `hgraph/language/src/syntax/token.cpp` | `src/main/grammar/Hgl.flex` |
| Grammar, including where newlines are admitted | the same guide's EBNF, as implemented by `hgraph/language/src/syntax/token_grammar.cpp` (the lexy grammar) | `src/main/grammar/Hgl.bnf` |
| Contextual keyword decisions and the applied-constructor look-ahead | `token_grammar.cpp`, `encode` and `looks_like_applied_constructor` | `HglParserUtil` |
| Scoping for navigation | the guide's "Scopes and name lookup" | `HglScope` |

Where the guide is ahead of the C++ compiler the guide wins. Today that is
the native declaration family (`native const fn`, body-less native
declarations, and the `native_contract_body` with `inject` items and
`start; when; stop;` hooks). Where the compiler is stricter than the prose
(the exact newline positions), the compiler's grammar wins because it is
what runs.

### Keeping in step

When the language changes, the checklist is:

1. A new or removed reserved word: `Hgl.flex` keyword rules, the `tokens`
   block of `Hgl.bnf`, `HglTokenSets`, and the completion keyword lists.
2. A new contextual keyword: the `tokens` block (so the parser can remap to
   it), the rule that demands it via `<<kw 'word'>>`, and
   `HglTokenSets.CONTEXTUAL_KEYWORDS` so it is coloured.
3. A grammar change: the matching rule in `Hgl.bnf`, then
   `tools/refresh_test_data.sh` to pull the current example modules, then
   the parser tests, which must stay error-free.
4. A new literal shape: the lexer, and `src/test/testData/lexer/literals.hgl`.

## Lexer

`Hgl.flex` is a JFlex lexer built with the IntelliJ skeleton. It follows the
compiler lexer (`lexer.cpp`) with these deliberate differences:

- **Newlines are tokens, one per line break.** The compiler merges a run of
  line terminators into one `Newline`; here every `\n` is a `NEWLINE` and
  comments are separate `LINE_COMMENT` / `BLOCK_COMMENT` tokens, which the
  parser definition declares as comment tokens. The grammar therefore
  writes `NEWLINE+` where the compiler has `newline`, and `NEWLINE*` (the
  private rule `nls`) where it has `newlines`.
- **`cpp` opens a small state machine.** After the `cpp` keyword the lexer
  expects either `include` (emitted as the dedicated `CPP_INCLUDE` token,
  then a `CPP_HEADER`), or an opaque balanced parameter list
  (`CPP_PARAMETER_LIST`) followed by an opaque balanced body (`CPP_BODY`).
  Inside the opaque tokens, C++ line and block comments, string and
  character literals and raw strings (`R"tag(...)tag"`) are skipped so
  their braces do not count, exactly as the compiler does. An unterminated
  parameter list or body is one `BAD_CHARACTER` token to the end of file.
- **`;` is a token.** The compiler lexer reports a semicolon as an error,
  but the guide's `native_contract_body` admits `inject ...;` and
  `start;`. The grammar decides where it is legal; anywhere else the parser
  reports it.
- **Duration and `@` literals are matched by shape, not validated.**
  `1h30m` is one `TEMPORAL_LITERAL`; a digit-and-letter run that is not a
  valid duration (`5min`, `1e5m`, `2x`) is one `BAD_CHARACTER`, as the
  compiler makes it one diagnosed token. `@2026-09-03-1d` lexes as a date
  followed by `-` and a duration, because a date takes exactly three digit
  groups. Calendar validity, unit order and fraction placement are the
  compiler's checks.
- **Strings are lenient.** An unknown escape or an unterminated string is
  still a `STRING_LITERAL` so the editor keeps colouring it.

Contextual keywords (`atomic`, `tuple`, `list`, `set`, `map`, `rolling`,
`ref`, `signal`, `schema`, `unbounded`, `in`, `native`, `throws`, `each`,
`properties`, `delta`) lex as `IDENTIFIER`, as in the compiler.

## Parser

`Hgl.bnf` is a Grammar-Kit grammar, transliterated production by production
from the lexy grammar in `token_grammar.cpp`. The generated parser and PSI
live in `src/main/gen` (not committed; the build regenerates them).

Three mechanisms carry the compiler's context-sensitive decisions:

- **Contextual keywords.** `<<kw 'in'>>` consumes an `IDENTIFIER` whose
  text is `in` and remaps the token to `IN_KW`. `<<typeKw 'list'>>` also
  requires the next token to be `<`, which is the guide's rule that a
  container keyword introduces a type only when directly followed by `<`.
  The remapped token type is what the annotator colours as a keyword.
- **Applied constructors.** `<<appliedConstructorAhead>>` re-implements
  `looks_like_applied_constructor`: from `name<` (or `name::name<`) every
  token up to the matching `>` must be one a generic-argument list can
  contain, a parenthesised group is skipped by balance alone, newlines are
  admitted only after `<` or `,` and before `>` or `,`, and the closing `>`
  must be followed by `(`. The scan is bounded to 512 raw tokens.
- **Size expressions.** `<<sizeExpr>>` parses an expression at additive
  precedence and above by calling the generated Pratt parser with the
  priority of `comparison_expr`, so `list<f64, n>` never reads `n >` as a
  comparison. The priority constant is checked against the generated code
  when the grammar's `expr` alternatives change.

Newlines follow the compiler exactly: a binary, `->` or `=>` operator may
be preceded and followed by newlines (`continued_operator`); lists inside
`()`, `[]`, `<>` and `{}` admit newlines after the opener, around commas
and before the closer; a `requires` clause, a function or struct body, an
`else` arm and an operator's `properties` may start on a new line. A
statement ends at a `NEWLINE` or the block's `}`; a declaration ends at a
`NEWLINE` or the end of file. Postfix forms never cross a newline.

Names admit the reserved words (`use hgraph.time::{...}`), as the
compiler's `raw_name` does; `let state = 1` is therefore a parse success
and a compiler diagnostic, not a parse error. The `name` production is
called `ident` here because Grammar-Kit would otherwise generate a
`getName()` accessor that collides with `PsiNamedElement`.

### Error recovery

The compiler recovers to the next line that starts a declaration or
statement. The plugin does the same with `recoverWhile`: a broken
statement or declaration is reported and skipped to the next `NEWLINE` (or
the closing `}`), so an error stays on its own line and the rest of the
file keeps its structure. `src/test/testData/parser/golden/recovery.hgl`
pins this behaviour.

## PSI and navigation

Every declaration that introduces a name implements `HglNamedElement`
(`PsiNameIdentifierOwner`): functions, native functions, operators, structs,
tests, parameters, generic parameters, `let`/`var`/`state`/`cache`
bindings, `for` bindings, struct fields, injected names, import items and
module aliases. The name is the element's first direct `ident` child.

References:

- `qualified_name` (`x` or `alias::x`, in expression and type position)
  resolves through `HglScope`: enclosing blocks (locals declared before the
  use; state, cache and injected names), the enclosing `for`, anonymous
  function, function, operator or struct (parameters and generics), the
  enclosing test context (helpers and tests), then the module's
  declarations, import items and aliases. The innermost scope that declares
  the name wins, so a `state total` shadows a module-level `fn total`;
  within one scope every declaration of the name is returned, which is how
  operator overloads and `impl fn` candidates are found. An import item
  expands to the declarations of that name in the imported module.
- `alias::x` resolves the alias to its `use ... as alias` and looks `x` up
  in that module.
- A module path in `use` resolves to the file (or part files) declaring it.

Cross-module lookups use `HglModuleIndex`, a file-based index from the
module path (read from the `module` line by a text scan, without parsing)
to the files declaring it.

Unresolved names are not errors: the prelude intrinsics (`modified`,
`valid`, `all_valid`, `last_modified`, `delta`, `scheduled`, `passivate`,
`activate`, `key_set`, `elements`, `values`, `keys`, `len`, `types`,
`type_at`, `fields`, `has_fields`, `field_type`, `schemas`) have no
declaration to find, and the plugin has no type information to decide the
rest. The annotator colours them as intrinsics instead.

## Highlighting

Two layers. `HglSyntaxHighlighter` colours tokens from the lexer alone
(keywords, scalar type keywords, literals, comments, punctuation, embedded
C++, bad characters) so colouring never waits for the parser. `HglAnnotator`
adds what needs the tree: contextual keywords, reserved words used as names,
declaration names by kind, references by what they resolve to, named
argument labels, field accesses and the intrinsics. Every colour is its own
`TextAttributesKey` with a platform default, editable on the HGL colour
settings page.

## Editor features

Brace matching for `()`, `[]`, `{}`; `#` line and `/* */` block comments;
folding of blocks, struct bodies, test contexts, native bodies, import sets,
block comments and C++ bodies; a structure view of the module's structs
(with fields), functions, operators, tests and test contexts; keyword
completion by position, with names in scope supplied by the reference's
variants; find usages and in-place rename for every named element.

## Tests

- `HglLexerTest`: golden token dumps for literals, C++ forms, comments and
  punctuation, and `checkCorrectRestartOnEveryToken` over every example
  module, which the incremental editor highlighter depends on.
- `HglParserTest`: golden PSI dumps for representative modules, including
  the continuation rules and the recovery fixture.
- `HglExamplesParsingTest`: every example module from `hgraph_spec`
  (`language/examples`) and from the C++ compiler (`language/examples` and
  `language/stdlib/hgl`) must parse with no error element. The copies are
  refreshed by `tools/refresh_test_data.sh` and their revisions recorded in
  `src/test/testData/parser/SOURCES.md`.
- `HglPlatformTest`: a headless IDE (`BasePlatformTestCase`) exercising the
  registered extensions end to end: resolution of parameters, locals, state
  and overloads, shadowing, imports and aliases across two module files
  through the index, module-path navigation, rename, find usages,
  completion, error highlighting and the structure view model.

## Future work

- C++ language injection into `CPP_BODY` and `CPP_PARAMETER_LIST` when the
  CIDR language is present (CLion), behind an optional dependency.
- Annotator checks that need no type information: duration unit order,
  fraction placement, duplicate names in a scope, a missing `module` line.
- A TextMate grammar generated from the same token tables for editors
  outside JetBrains.
- Once `enum` and `switch` are implemented in a compiler, the matching
  productions.
