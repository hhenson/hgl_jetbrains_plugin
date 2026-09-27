# CLAUDE.md

Working guide for AI sessions on **hgl_jetbrains_plugin**, the JetBrains IDE
plugin for HGL. Read `docs/design.md` first: it is the authoritative
description of the plugin's structure, and a change to the code changes it
in the same commit.

## What this is

One IntelliJ Platform plugin (Kotlin + Grammar-Kit + JFlex) that gives
CLion, PyCharm, RustRover and IntelliJ IDEA syntax highlighting and basic
language support for `.hgl` modules. It depends only on the platform and
`lang` modules, so one build installs on all of them.

The language is owned elsewhere: the specification in `hgraph_spec`
(`language/docs/developer-guide/syntax-and-semantics.md`) and the C++
compiler's lexer and lexy grammar in `hgraph` (`language/src/syntax/`).
This plugin mirrors them; it never invents syntax. When the two disagree,
the guide wins on forms the compiler has not implemented, and the
compiler's grammar wins on the exact newline positions.

## Rules

- **Syntax only.** The plugin reports parse errors and nothing else. Type,
  phase and name-resolution diagnostics are the compiler's; do not add a
  second opinion.
- **Mirror, do not extend.** The provisional `enum` / `switch` / `str()`
  forms are not parsed until an implementation parses them.
- **Every grammar change runs `tools/refresh_test_data.sh`** and keeps
  `HglExamplesParsingTest` green: every example module from the spec and
  the compiler must parse without an error element.
- **Grammar-Kit traps** (each cost a round here): a rule named `name`
  generates `getName()` and collides with `PsiNamedElement` (ours is
  `ident`); an external-rule argument that names a token is passed as a
  `Parser`, so contextual keywords are passed as the token *name string*;
  `'(' expr ')'` in the expression group is auto-pinned after `(`, so groups
  and tuples share one `tuple_or_group_expr` rule; `expr '[' expr ']'` is
  classified as binary and parses the index at index priority, so the
  suffix lives in a private rule.

## Build and test

```sh
export JAVA_HOME=<any JDK 21+; the JBR inside a JetBrains IDE works>
./gradlew test            # lexer goldens, parser goldens, all example modules
./gradlew buildPlugin     # build/distributions/hgl-jetbrains-plugin-<version>.zip
./gradlew verifyPlugin    # Plugin Verifier against CLion, PyCharm, RustRover (downloads them)
./gradlew runIde          # sandboxed IDE with the plugin
```

Golden files (`src/test/testData/**/*.txt`) are written on the first run
when missing and compared afterwards; delete one to regenerate it, then
review the diff. `src/main/gen` is generated and not committed.

The IDE SDK is resolved by the IntelliJ Platform Gradle Plugin from
`platformVersion` in `gradle.properties`; Community-edition artifacts no
longer exist, so it uses `intellijIdea(...)`.
