# HGL for JetBrains IDEs

Review HGL code in CLion and RustRover with one plugin. The plugin provides
highlighting, folding, documentation, a structure view, completion and
navigation for `.hgl` files. Run a named test or application from its gutter icon.
Click **Implemented by** beside an operator to browse overloads by signature,
including those added by other modules that import it. Click **Implements**
beside an HGL or native implementation to jump back to its operator.
When calling a callback, complete a named argument or use Go to Declaration
on its label to jump to the parameter in the callback type.

## Language sources

[hgl_spec](https://github.com/hhenson/hgl_spec) owns the language rules.
[hgl_core](https://github.com/hhenson/hgl_core) owns core declarations and
conformance fixtures. Their merged revisions are pinned under `spec/`. CI pins the compiler separately for diagnostics and execution tests.

Run `tools/refresh_test_data.sh` after changing the pins. The parser corpus
includes complete modules and applications from both specifications, language
declaration examples, core mapping declarations and every core HGL file.
[Fixture sources](src/test/testData/parser/SOURCES.md) records their revisions.

## Build and install

Use JDK 25 and clone with `--recurse-submodules`. The build downloads CLion
2026.3.0.1 and tests the same plugin in RustRover 2026.3.1. To use an installed IDE, pass `-PplatformLocalPath=/path/to/IDE`.

```sh
./gradlew test testRustRover buildPlugin
./gradlew verifyPlugin
```

Install `build/distributions/hgl-jetbrains-plugin-0.10.0.zip` through
Settings → Plugins → Install Plugin from Disk in either IDE. The plugin
supports the 2026.3 platform from build 263.7307.257 through 263.*.

## Create an HGL project

Choose **New Project → HGL**, select a directory, and click **Create**.
Run `application hello.main` from the gutter in `src/main.hgl` to publish
`"Hello, world!"` once. Run the named test `says_hello` beside `greeting`
in `src/hello.hgl`.

**Settings → Build, Execution, Deployment → HGL** edits the `build` section
of `hgl.json`. Commit this file to share the compiler command, source roots,
and output paths. Paths are relative to the project or absolute. A workspace
compiler override is private to your IDE. Existing projects retain their
runner settings until they save shared build settings.

| Directory | Purpose |
| --- | --- |
| `src/` | Application, module, and inline tests |
| `build/` | Compiler workspace for Build, Run and Test |
| `bin/` | Destination reserved for standalone binaries |
| `docs/` | Generated HTML site and IDE documentation model |

Choose **Build → Build HGL Project → Release** or **Debug**. Both modes are
also on the Build HGL toolbar button. Release writes to the configured build
directory. Debug writes to its `debug/` subdirectory and includes native
debug symbols. Both require a
compiler with `hgl build --compile-commands`. CLion loads the generated C++
and its compiler flags into the open project. Build again after changing the
HGL sources to refresh the C++ model. RustRover reports the location of
`compile_commands.json` in the configured build directory.

Click **Generate HGL Documentation** on the main toolbar or in **Build**.
Choose **Open documentation** in the completion notification to read the site.
For an older, unmarked site, set **Documentation directory** to a fresh
directory before generating. Subsequent runs update that site in place.
The equivalent compiler commands for the starter are:

```sh
hgl doc src --out docs
hgl doc src --format json --out docs
```

## Code documentation

Documentation comments start folded. Expand `/**...*/` to edit the comment.
Hover over a declaration or a resolved name to see its written signature and
the summary from its `/** ... */` comment. **View → Quick Documentation**
shows the full comment, including HGL sections such as `Args:`, `Returns:`
and `Examples:`. Hover over a parameter, a named call argument, or a generic
to see its own entry from `Args:` or `Type Args:`. The IDE renders CommonMark
and tables. Quick Documentation labels HGL fences under `Examples:` as
**Runnable example**. It labels `hgl no-run` fences and HGL fences elsewhere
as **Shown example**. Fenced formulas and diagrams display their source.

To run one documentation example, paste its printed name, such as
`demo::Box.value#example-1`, into an **HGL Test** run configuration with
the project’s source roots.

For modules outside the project, use a version 1 `documentation.json` export.
New projects read it from the configured documentation directory. Use **Documentation model override** in the HGL build settings for another file. Generate it with the compiler’s JSON format:

```sh
hgl doc path/to/library --format json --out .
```

Hover reads the export for selectively imported names, module aliases and the
core prelude. Project source takes precedence, including unsaved edits.

## Compiler diagnostics on save

Save an HGL file to see compiler errors and warnings in the editor. Hover
over an underline for its code, message and related locations. In
**Settings → Build, Execution, Deployment → HGL**, you can override
the compiler path. Set source roots for multi-file programs.
**Check HGL sources on save** is enabled by default in trusted
projects.

## Run an HGL application or test

Open the **Run/Debug** dropdown. **HGL Applications** lists each application by
name. **HGL Tests** offers these selections for both Run and Debug.

| Selection | Cases |
| --- | --- |
| `prices — Tests` | Ordinary tests in every part of module `prices` |
| `prices — Integration Tests` | Integration tests in every part of `prices` |
| **All HGL Tests** | Ordinary tests across the project’s source roots |
| **All HGL Integration Tests** | Integration tests across the same roots |

Right-click an HGL file or folder in the **Project** window, or right-click an
HGL editor tab. Choose **Run HGL Tests** for ordinary tests or **Run HGL
Integration Tests** for integration tests. A folder includes its subfolders.
Both actions open **Test Results**. Rerun keeps the selected path and test kind.

A selection within a source root requires `hgl test --test-path`.
Integration-only runs require `hgl test --integration-only`.

Click the gutter triangle beside an `application` header, a named `test`,
or an `integration_test`. For `application demo.backtest`, the IDE runs
`hgl run --application demo.backtest` with the configured roots. The Run
window shows the output and exit status. Tests open the JetBrains test results
tree. Select a failed or skipped case for the compiler's reason. The root
retains the full output. Rerun repeats the same selection.

To debug, set a line breakpoint on an executable HGL statement and choose
**Debug** from the application gutter. The Debug window opens the HGL source
at the breakpoint. Resume to see timestamped publications in its console.
Application debugging requires `hgl run --debug-build`. Test debugging requires
`hgl test --debug-build`. Use **Debug** on a named test or on a suite
configuration. A selection spanning modules opens one LLDB session per module,
in order. Continue after a breakpoint to proceed. Stop cancels the remaining
modules. Integration test timeouts are suspended while debugging. A failed
compiler-checked rejection appears with its diagnostic. Other executable tests
still run.

Step Over follows HGL assertions. Step Into can open bundled Core HGL from the
debug build. The IDE highlights its `*.hgl.source` files. Breakpoints in those
copies apply to that debug session. Function ends map to their HGL closing lines.

On macOS the plugin
uses Apple's LLDB adapter from the active developer tools. Run
`xcrun --find lldb-dap` to check its location. On Linux it uses the IDE's
bundled LLDB adapter.

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
