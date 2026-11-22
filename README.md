# HGL for JetBrains IDEs

Review HGL code in CLion and RustRover with one plugin. The plugin provides
highlighting, folding, documentation, a structure view, completion and
navigation for `.hgl` files. Run a named test or application from its gutter icon.
Click **Implemented by** beside an operator to browse overloads by signature,
including those added by other modules that import it. Click **Implements**
beside an HGL or native implementation to jump back to its operator.

## Language sources

[hgl_spec](https://github.com/hhenson/hgl_spec) owns the language rules.
[hgl_core](https://github.com/hhenson/hgl_core) owns core declarations and
conformance fixtures. Their merged revisions are pinned under `spec/`. CI pins the compiler separately for diagnostics and execution tests.

Run `tools/refresh_test_data.sh` after changing the pins. The parser corpus
includes specification modules, applications, declaration examples, core mapping declarations and every core HGL file.
[Fixture sources](src/test/testData/parser/SOURCES.md) records their revisions.

## Build and install

Use JDK 25 and clone with `--recurse-submodules`. The build downloads CLion
2026.2. To use an installed IDE, pass `-PplatformLocalPath=/path/to/IDE`.

```sh
./gradlew test testRustRover buildPlugin
./gradlew verifyPlugin
```

Install `build/distributions/hgl-jetbrains-plugin-0.7.0.zip` through
Settings → Plugins → Install Plugin from Disk in either IDE. The plugin
requires platform build 262 or later.

## Code documentation

Hover over a declaration or a resolved name to see its written signature and
the summary from its `/** ... */` comment. **View → Quick Documentation**
shows the full comment, including HGL sections such as `Args:`, `Returns:`
and `Examples:`. Hover over a parameter, a named call argument, or a generic
to see its own entry from `Args:` or `Type Args:`. The IDE reads project source directly.

## Compiler diagnostics on save

Save an HGL file to see compiler errors and warnings in the editor. Hover
over an underline for its code, message and related locations. In
**Settings → Languages & Frameworks → HGL Test Runner**, you can override
the compiler path. Set source roots for multi-file programs.
**Check HGL sources on save** is enabled by default in trusted
projects.

## Run an HGL application or test

Click the gutter triangle beside an `application` header, a named `test`,
or an `integration_test`. For `application demo.backtest`, the IDE runs
`hgl run --application demo.backtest` with the configured roots. The Run
window shows the output and exit status. Rerun repeats the same selection.

On Apple Silicon, install the compiler with `brew install hhenson/hgraph/hgl`.
Both IDEs look for `hgl` on their console `PATH`, then in `/opt/homebrew/bin`
on macOS. This also lets the C++ runner find Homebrew’s CMake and Ninja.
The formula bundles the native SDK. The default source root is the selected file.
For a multi-file program, enter its source roots, one per line. For the core
conformance suite in `hgl-project`, use `hgl_core/hgl` and
`hgl_core/tests/conformance`. Paths are relative to the project or absolute.

## Marketplace releases

Add `PUBLISH_TOKEN` as a repository Actions secret using a token from
[Marketplace → My Tokens](https://plugins.jetbrains.com/author/me/tokens).
The initial Marketplace listing must already exist.

1. Update `pluginVersion` in `gradle.properties` and merge to `main`.
2. Publish a GitHub release with the matching tag, for example `v0.3.0`.
3. The workflow tests both IDEs, verifies the ZIP, uploads it to Marketplace,
   and attaches the installable ZIP directly to the GitHub release.

Write Markdown in the GitHub release description before publishing. The build
converts headings, lists, emphasis, links and code to HTML change notes for
Marketplace and the IDE. Later edits to the release description do not change
an uploaded ZIP. Local builds can supply Markdown through `RELEASE_NOTES`;
unset or blank notes are omitted.

Drafts and prereleases are not published to Marketplace. Each upload needs a
new version; `0.2.0` was uploaded manually. JetBrains reviews updates before
making them available.

For a dry run, use **Actions → Marketplace release → Run workflow** with an
existing version tag on `main`. This runs the checks and produces the ZIP
without publishing. Locally, run `./gradlew prepareMarketplaceRelease`.
The resulting ZIP is under `build/marketplace/`; CI stores reports separately.

Author signing is optional. To enable it, add both `CERTIFICATE_CHAIN` and
`PRIVATE_KEY` as Actions secrets, plus `PRIVATE_KEY_PASSWORD` if the key is
encrypted. A partial signing configuration fails the build. The signed ZIP
is signature-checked and compatibility-checked before publication.
See [JetBrains signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).

[Design](docs/design.md) describes the editor/compiler boundary.
[Project rules](AGENTS.md) apply to development. Licensed under [MIT](LICENSE).
