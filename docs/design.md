# Editor design

The plugin uses the shared IntelliJ Platform language APIs. One ZIP provides
HGL support in CLion and RustRover. JFlex produces the lexer; Grammar-Kit
produces the parser and PSI. Kotlin implements editor features over that PSI.

## Authority and updates

The submodules under `spec/` pin hgl_spec and hgl_core to the compiler's
selected revisions. The specification owns syntax and meaning. The plugin
implements editor behavior and tolerates incomplete source during editing.

`tools/refresh_test_data.sh` extracts complete fenced HGL modules from the
specification and copies core declarations and fixtures. Parser tests use
that corpus. Golden tests cover tree structure and recovery. Platform tests
exercise navigation, documentation, completion and the structure view.

Update the pins, refresh the corpus, adjust the lexer/grammar, and run the
checks together. Changes to the pinned sources must expose editor drift in CI.

## Compiler boundary

The editor supplies lexical and syntax highlighting. Semantic diagnostics,
phase checking, overload resolution and runtime conformance belong to the
compiler. A future language server can expose the compiler's structured
results and resolved names. Current navigation uses the plugin's syntax
scopes and module index.

## Grammar and PSI

Newlines are explicit tokens. Whitespace and comments stay in the PSI.
Contextual names are remapped only where the grammar consumes their spelling.
Generic-call look-ahead preserves an application-shaped tree for editor use;
semantic disambiguation belongs to the compiler.

Named declarations implement HglNamedElement. Typed PSI views support folding,
structure, documentation and references. Generated sources live under
`src/main/gen` and are rebuilt from the grammar.

## Validation

Run the lexer, parser and platform tests against each supported IDE. Build the
distribution once, then pass that ZIP to Plugin Verifier for both products.
These checks validate editor integration. Execution conformance runs through
the compiler and runtime.

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
