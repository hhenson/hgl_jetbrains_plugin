# HGL for JetBrains IDEs

Language support for [HGL](https://github.com/hhenson/hgraph_spec), the
temporal programming language of the [hgraph](https://github.com/hhenson/hgraph)
project, as one plugin for CLion, PyCharm, RustRover and IntelliJ IDEA.

Features:

- syntax highlighting, with a colour settings page;
- brace matching, `#` line and `/* */` block comments, code folding;
- structure view of a module's structs, functions, operators and tests;
- go to declaration, find usages and rename for functions, structs,
  operators, parameters, locals, state and imports, including
  `use module::{name}` and `alias::name` across modules;
- keyword completion and completion of names in scope;
- parse errors, recovered line by line, from a grammar transliterated from
  the compiler's.

The plugin is syntax-level only. Type checking and the language's
diagnostics remain the compiler's; see [docs/design.md](docs/design.md).

## Install

Build the distribution (below) and install it from disk:
*Settings | Plugins | ⚙ | Install Plugin from Disk...*, choosing
`build/distributions/hgl-jetbrains-plugin-<version>.zip`. Restart the IDE.
Files named `*.hgl` open with HGL support.

The plugin targets platform build 262 (2026.2) and later.

## Build

Requirements: a JDK 21 or newer on `JAVA_HOME` (the JBR bundled inside any
JetBrains IDE works: `<IDE>.app/Contents/jbr/Contents/Home` on macOS) and
network access for the first build, which downloads the IntelliJ Platform.

```sh
./gradlew buildPlugin          # build/distributions/hgl-jetbrains-plugin-<version>.zip
./gradlew test                 # lexer, parser and example-module tests
./gradlew runIde               # a sandboxed IDE with the plugin installed
./gradlew verifyPlugin         # IntelliJ Plugin Verifier against CLion, PyCharm, RustRover
```

The lexer (`src/main/grammar/Hgl.flex`) and grammar (`src/main/grammar/Hgl.bnf`)
are regenerated into `src/main/gen` on every build.

## Relationship to the other repositories

| Repository | Owns |
|---|---|
| [hgraph_spec](https://github.com/hhenson/hgraph_spec) | the language and runtime specification the grammar follows |
| [hgraph](https://github.com/hhenson/hgraph) | the C++ compiler whose lexer and grammar this plugin mirrors |
| [hgraph_std](https://github.com/hhenson/hgraph_std) | the HGL standard library |
| this repository | the JetBrains plugin only |

The parser tests hold every example module of the specification and the
compiler; `tools/refresh_test_data.sh` refreshes them from sibling
checkouts and records the revisions in `src/test/testData/parser/SOURCES.md`.

## License

MIT, see [LICENSE](LICENSE).
