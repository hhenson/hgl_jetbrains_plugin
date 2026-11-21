# Editor design

The plugin uses the shared IntelliJ Platform language APIs. One ZIP provides
HGL support in CLion and RustRover. JFlex produces the lexer; Grammar-Kit
produces the parser and PSI. Kotlin implements editor features over that PSI.

## Authority and updates

The submodules under `spec/` pin the reviewed hgl_spec and hgl_core revisions. The specification owns syntax and meaning. The plugin
implements editor behavior and tolerates incomplete source during editing.

`tools/refresh_test_data.sh` extracts complete fenced HGL modules and applications from the
specification, includes language declaration fragments and core’s mapping contract, and copies core HGL sources. Parser tests use
that corpus. Golden tests cover tree structure and recovery. Platform tests
exercise navigation, documentation, completion and the structure view.

Update the pins, refresh the corpus, adjust the lexer/grammar, and run the
checks together. Changes to the pinned sources must expose editor drift in CI.

## Compiler boundary

The editor supplies lexical and syntax highlighting. Semantic diagnostics,
phase checking, overload resolution and runtime conformance belong to the
compiler. The editor reads its structured diagnostics on save. Current
navigation uses the plugin's syntax
scopes and module index. Module parts share declarations and test helpers.
Imports stay file-local. Other modules see exported declarations, including exported operators,
and the public `core` prelude. Applications have their own file scope.

Operator gutters link a declaration to its HGL and native candidates in both
directions (GEN-4). A name index narrows the search to files with an `impl`
header. Any module can contribute overloads by selectively importing the operator
in the file that declares them. Candidates also bind to operators in their own
module or the core prelude. The chooser shows typed signatures and file paths.

## Compiler diagnostics on save

Saving a local HGL file in a trusted project starts `hgl check --json` on
the configured source roots or the saved file. Saves for the same roots
supersede each other and cancel the previous process. Checks run in the
background. The annotator applies errors and warnings to open, saved
documents whose file and document stamps still match the check. A clean
report clears previous findings for those roots.

Version 1 uses zero-based UTF-16 positions and half-open ranges. Tooltips
include the code, rule, notes and related locations. Bundled-core locations
such as `<core>/core/core.hgl` appear as plain text. Exit 2 reports an
invocation failure via stderr, which the IDE shows in a project notification.
Only exits 0 and 1 carry JSON reports. An unsupported report version also
raises a project notification.

## Application and test execution

The gutter marks the `application` keyword or the name of a test.
Each context producer records its declaration's selector and source file
in an IDE Run configuration.

| Declaration | Compiler invocation |
| --- | --- |
| `application demo.backtest` | `hgl run --application demo.backtest --target cpp` |
| `test prices` or `integration_test prices` in module `demo.feed` | `hgl test --test demo.feed::prices --target cpp` |

A module part contributes to its enclosing module. Naming an integration
test opts into that case. The compiler still checks declaration and import
rejection cases in the supplied roots.

Both configurations use the project’s HGL Test Runner settings for the
executable and roots. Empty roots select the declaration's
file. The IDE owns the Run console, stop and rerun actions. The compiler
owns selection and outcomes.

## Grammar and PSI

Newlines are explicit tokens. Whitespace and comments stay in the PSI.
Contextual names are remapped only where the grammar consumes their spelling.
Generic-call look-ahead preserves an application-shaped tree for editor use;
semantic disambiguation belongs to the compiler.

Named declarations implement HglNamedElement. Typed PSI views support folding,
structure, documentation and references. Generated sources live under
`src/main/gen` and are rebuilt from the grammar.

Keyword completion in a named or anonymous function's `=>` body
offers expression starters at operands. A nested block resumes statement
completion. Test bodies also admit `eval`, and integration tests admit `run`.
Names in scope are supplied independently by reference completion.

## Validation

Run the lexer, parser and platform tests against each supported IDE. Build the
distribution once, then pass that ZIP to Plugin Verifier for both products.
These checks validate editor integration. Execution conformance runs through
the compiler and runtime.

CI pins the compiler independently and exercises both IDEs with the native hgraph SDK. Set `HGL_TEST_CLI`, `HGL_HGRAPH_SDK` and `HGL_HGRAPH_DEPS` to run those process tests locally.

## Performance baseline

Use a recursive checkout of hgl-project at `9d6b592` and run:

```sh
./gradlew --max-workers=1 -PhglBenchmarkWorkspace=/path/to/hgl-project \
    test --tests '*HglPerformanceBaselineTest' \
    testRustRover --tests '*HglPerformanceBaselineTest'
```

Each IDE imports the wrapper's 104 tracked HGL files into a fresh headless
project, including the compiler's copy of core. The CLion fixture disables
its background C++ backend, which can outlive a temporary project. Cold import measures VFS
insertion through HGL index readiness. First highlight includes opening
`collection_ticks.hgl`. The warm measurements use the forwarding `add_` call
in `core.hgl`, with overloads in `scalar_operators.hgl`. Completion opens
the IDE lookup. Navigation gathers Go to Declaration candidates before the
chooser opens. Both use 3 warmups and 20 samples (nearest-rank p95).

| Linux, JDK 25 | Cold import | First highlight | Completion p95 | Navigation p95 | After unrelated edit p95 | Indexed file visits per navigation |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| CLion 262.8665.262 | 619 ms | 1,015 ms | 16.64 ms | 0.31 ms | 0.27 ms | 14 |
| RustRover 262.8665.323 | 540 ms | 989 ms | 24.37 ms | 0.23 ms | 0.20 ms | 14 |

Both IDEs found 46 declarations across the two copies of core and visited
14 indexed files before and after an unrelated edit. Renaming one overload
removed it from navigation and exposed its new name in completion. Each run
writes its measurements to the test report as `HGL_BASELINE`. Times are
observations from one run per IDE.

## Releases

A published stable GitHub release triggers Marketplace publishing. Its tag
must match `pluginVersion`, the checkout, and a commit on `main`. Manual
workflow runs validate an existing tag without publishing.

The GitHub release body supplies `RELEASE_NOTES`. JetBrains' Gradle Changelog
renderer converts its Markdown to HTML in the packaged `plugin.xml` change
notes. CI checks formatting and omitted notes by inspecting built ZIPs.

`prepareMarketplaceRelease` runs both IDE suites and verifies the chosen ZIP,
including its signature when author signing is configured. `publishPlugin`
depends on that task and uploads the same bytes. GitHub release assets expose
the installable ZIP directly; CI reports are separate artifacts. Credentials
come from Actions secrets and are not needed by normal builds.
