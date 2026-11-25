# Editor design

The plugin uses the shared IntelliJ Platform language APIs. One ZIP provides
HGL support in CLion and RustRover. JFlex produces the lexer; Grammar-Kit
produces the parser and PSI. Kotlin implements editor features over that PSI.

## Authority and updates

The submodules under `spec/` pin the reviewed hgl_spec and hgl_core revisions. The specification owns syntax and meaning. The plugin
implements editor behavior and tolerates incomplete source during editing.

`tools/refresh_test_data.sh` extracts complete fenced HGL modules and applications from the
language and core specifications, includes language declaration fragments and core’s mapping contract, and copies core HGL sources. Parser tests use
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
Each named test owns its declared names. Within test code, navigation to an
operator includes candidates contributed by any test in that module (TST-1).
The compiler selects the overload, including inside functions called by a test.
Imports stay file-local. Other modules see exported declarations, including exported operators,
and the public `core` prelude. Applications have their own file scope.

Navigation, name completion and documentation use the nearest binding (UNIT-10/12).
A local hides an outer name after its initializer. Switch cases share the enclosing
block. A hidden import alias cannot qualify a name. Signature types and ordinary
defaults see the declaration's generics and enclosing scope. A reflected pack and a
generic law default can name their callback (FN-8, GEN-18).

Operator gutters link a declaration to its HGL and native candidates in both
directions (GEN-4). A name index narrows the search to files with an `impl`
header. Any module can contribute overloads by selectively importing the operator
in the file that declares them. Candidates also bind to operators in their own
module or the core prelude. The chooser shows typed signatures and file paths.

## Code documentation

Editor offsets and PSI elements share one documentation target. Hover reads
the written signature and summary. Quick Documentation groups SDOC-3 sections
and renders CommonMark with tables (SDOC-7). Raw HTML is displayed as text.
An `hgl` fence under `Examples:` is labeled as runnable (SDOC-9). An
`hgl no-run` fence or an HGL fence outside that section is labeled as shown.
The same Markdown renderer handles source and exported documentation.
Math and Mermaid fences display their source. A code span with a unique
declaration in the comment’s scope links to it (SDOC-8). Parameter help reads the
matching entry from the owner's `Args:` or `Type Args:` section.

Source comments can link to a public declaration or member through a visible
file import (for example, `prices::Quote.bid`). A parameter that hides the
alias leaves that span as plain code. Exported comments lack import metadata.

A call to an operator shows the public declaration. An implementation shows
its own documentation at its declaration. Other source overloads remain
separate targets. The IDE handles unsaved edits directly. A future tooltip
with inferred types or the selected overload would need a compiler query
mapping a source position to its resolved declaration and instantiated type.

An optional version 1 compiler export supplies documentation for modules
without indexed source. The plugin caches the model against its JSON file.
Canonical names group overloads. Kind and source location distinguish them.
A bundled `<core>/` location is a display name. Exported comments use their
own module for links. The model does not record imports or call-site bindings.

CI regenerates the JSON fixture from the pinned compiler’s documentation and
test-local declarations and runnable examples. A private export qualifies a test-local name
with its owning test. An operator’s candidate list includes implementations
declared at module scope, including those in importing modules. Named
constructor arguments select the struct's field record. The editor recognizes `Options:`, `Rules:` and `Status:` in
both source comments and exported documentation. Parameter descriptions keep
their Markdown after the common continuation indent is removed. A heading beside
the opening comment delimiter is prose. The export records this distinction in
the section’s `recognized` field.

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

## New projects

CLion and RustRover use the same directory project generator. It copies the
bundled module and application into `src/`, creates `build/`, `bin/` and
`docs/`, and opens the application. Tests live alongside the code they
exercise. The project tracks `hgl.json`. Its `build` object holds
`compiler`, `sourceRoots`, `buildDir`, `binDir` and `docsDir`. The IDE
edits this file and preserves unrelated JSON fields. The private workspace
keeps compiler and documentation overrides and the check-on-save switch.

Run and Test pass `buildDir` to the compiler's `--keep` option. The current
compiler places executables in that workspace. `binDir` records the intended
standalone binary destination. Generate HGL Documentation invokes `hgl doc`
for HTML first, then JSON under `docsDir`. The compiler claims an empty
directory for the site and manages subsequent HTML updates, including sites
created with its original marker. If HTML generation
fails, the action stops before writing the IDE’s JSON model.

## Building and indexing generated C++

Build runs `hgl build --target cpp --compile-commands --out <buildDir> -- <roots>` in
the project directory for Release. Debug adds `--debug` and writes to
`<buildDir>/debug`. On success the plugin refreshes the generated files under `buildDir`.
The optional CLion Compilation Database integration links that file to the current
project and refreshes the model when the database changes. Moving the build directory
repoints the database link created by HGL. RustRover reports the generated file path.

## Application and test execution

The gutter marks the `application` keyword or the name of a test.
Each context producer records its declaration's selector and source file
in an IDE Run configuration.

| Declaration | Compiler invocation |
| --- | --- |
| `application demo.backtest` | `hgl run --application demo.backtest --target cpp` |
| `test prices` or `integration_test prices` in module `demo.feed` | `hgl test --test demo.feed::prices --target cpp` |

The HGL Test configuration accepts the CLI’s documentation example identities,
including module and part headers, members and numbered overloads. Each
selector is passed to `--test` as one argument.

The Project and editor-tab menus offer separate ordinary and integration test
actions. Each saves the selected file or recursive folder and its test kind
in a rerunnable configuration. The compiler reads all configured roots for
imports. `--test-path` selects cases by source location when the selection
covers part of the program. A folder above multiple roots selects the roots
it contains. `--integration-only` selects integration tests and their rejection
cases. The IDE displays the compiler's outcomes in the same test tree as a gutter run.

A module part contributes to its enclosing module. Naming an integration
test opts into that case. The compiler still checks declaration and import
rejection cases in the supplied roots.

Diagnostics and both run configurations share one compiler command builder.
It uses the project’s executable setting and the IDE console environment.
On macOS it appends `/opt/homebrew/bin` to the child’s `PATH` when absent,
so the compiler and its build tools are visible to desktop-launched IDEs.
Empty roots select the declaration's file. The IDE owns the Run console,
stop and rerun actions. The compiler
owns selection and outcomes.

HGL tests use the platform test results tree. The converter reads the
compiler's stdout case headers and indented details. Executable and rejection
cases retain their compiler identities. A “not run” outcome appears as
skipped. Compiler invocation failures appear as runner errors. Applications
keep their ordinary Run console.

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
Calls to the core endpoint predicates `modified`, `valid` and `all_valid` use the
contextual keyword colour. Temporal handlers offer all three in completion even
when core sources are absent. A local binding keeps its own colour and completion.

Named argument completion and Go to Declaration use the public signature of an
unambiguous function or operator. At a callback call only names written in the
parameter's function type are visible (FN-17). Completion omits slots already
filled by positional or named arguments.
An operator candidate may add options (GEN-6). Completion shows each label once
and Go to Declaration offers its declaring candidates. Shared labels navigate to
the public signature. Candidate lookup uses the gutter’s implementation index
and the caller’s test scope. The compiler selects candidates by type.
Fixed parameters precede packs (FN-8). Named calls offer declared configuration
parameters in braces, including Core’s `join{separator: "/"}("a", "b")`.
Brace labels navigate to the public declaration. TYPE-18’s `type(e)` is an
expression and accepts one operand. The descriptor spelling `Type` is contextual
in annotations. A bare angle argument follows the declared parameter kind.
When the call supplies the kind, a visible value takes precedence (GEN-14).
The compiler diagnoses reserved type declarations.

## Toolbar targets

The IDE discovers application and module headers in the configured source
roots. With no configured roots it uses indexed HGL sources under the project
directory. Saving a source file updates its header. A path or source-root
change rebuilds the catalog. Generated Run/Debug configurations live in the
IDE workspace. Removing a declaration retires its generated entry.

A module selection passes one `--test-path` per module part. A project
selection passes all source roots without a path filter. Both use the same
runner as file and folder selections, preserving dependency roots and test kind.

## Application and test debugging

The application gutter uses the same configuration and source roots for Run
and Debug. The Debug runner asks `hgl run --debug-build DIR` for its version 1
launch description. The IDE launches that executable under LLDB via
the platform’s experimental Debug Adapter Protocol API, checked against
both IDE versions. The optional HGL debug module declares its platform DAP,
debugger and native debugging dependencies. Its class loader owns the runner,
breakpoints and adapter provider. macOS launches the active Apple toolchain adapter with
`/usr/bin/xcrun lldb-dap`. Linux uses the adapter bundled with the IDE.
The supported build range is limited to the verified
platform branch. Before widening it, run Plugin Verifier and the native
breakpoint-and-resume test in both IDEs. Native line tables map breakpoints and stack
frames to the HGL file. The compiler writes bundled Core sources under
`debug-sources/`. Their `*.hgl.source` file type shares the HGL language
and line breakpoints. Module indexing, test discovery and on-save checks
use the primary `.hgl` file type.

A test configuration passes its Run selectors to `hgl test --debug-build DIR`.
Exit 0 returns the module launches in execution order and lists skipped cases.
A failed compiler-checked rejection returns exit 1 with a plan. The IDE reports
each failed rejection and its diagnostic, then runs the executable cases.
Source or native build errors return empty stdout and diagnostics on stderr.
The build error ends the debug request.

When the compiler supplies `sourceMap`, the IDE passes its build and installed
source roots to LLDB as path pairs for that launch. With the matching
`hgl-debug` package installed, native hgraph stack frames open their C++ source.

For each debug launch the plugin imports the scalar endpoint formatter from the hgraph SDK named by the compiler. The SDK supplies the runtime-specific layouts. Older SDKs retain the native debugger display.

For a usable plan, LLDB starts each executable with the supplied arguments
and environment. Debug launches omit integration test deadlines. The IDE
reads the module’s results file at exit. Terminal outcomes allow the next
launch, including after an assertion failure. A partial record ends the
group. The temporary build lives until the group finishes.

## Validation

Run the lexer, parser and platform tests against each supported IDE. Build the
distribution once, then pass that ZIP to Plugin Verifier for both products.
These checks validate editor integration. Execution conformance runs through
the compiler and runtime.

A separate JVM loads the packaged plugin using each IDE’s boot classpath and
resolves its debugger extensions with their installed plugin class loaders.

CI pins the compiler independently and exercises both IDEs with the native hgraph SDK.
Set `HGL_TEST_CLI`, `HGL_HGRAPH_SDK` and `HGL_HGRAPH_DEPS` to run those process
tests locally. Both IDEs build the starter and verify that its compilation database
includes the module and application. CLion also imports both generated C++ units.
In both IDEs the pinned compiler runs ordinary and integration tests separately
for a single file and a recursive folder with dependencies in another root.
A rejection owned by an integration test appears only in the integration run.
The same pinned compiler supplies the version 1 test launch plan.
Both IDEs check LLDB stops on the HGL assertion line for named, module
and project selections of each test kind. They check Step Over between HGL
assertions, the reported source line after a failed rejection or assertion,
and Continue and Stop across module boundaries.

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
