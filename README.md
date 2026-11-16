# HGL for JetBrains IDEs

Review HGL code in CLion and RustRover with one plugin. The plugin provides
highlighting, folding, documentation, a structure view, completion and
navigation for `.hgl` files.

## Language sources

[hgl_spec](https://github.com/hhenson/hgl_spec) owns the language rules.
[hgl_core](https://github.com/hhenson/hgl_core) owns core declarations and
conformance fixtures. Both are pinned under `spec/` at revisions selected by
[hgl-compiler](https://github.com/hhenson/hgl-compiler).

Run `tools/refresh_test_data.sh` after changing the pins. The parser corpus
includes complete specification examples and every core HGL file.
[Fixture sources](src/test/testData/parser/SOURCES.md) records their revisions.

## Build and install

Use JDK 25 and clone with `--recurse-submodules`. The build downloads CLion
2026.2. To use an installed IDE, pass `-PplatformLocalPath=/path/to/IDE`.

```sh
./gradlew test testRustRover buildPlugin
./gradlew verifyPlugin
```

Install `build/distributions/hgl-jetbrains-plugin-0.2.0.zip` through
Settings → Plugins → Install Plugin from Disk in either IDE. The plugin
requires platform build 262 or later.

## Marketplace releases

Add `PUBLISH_TOKEN` as a repository Actions secret using a token from
[Marketplace → My Tokens](https://plugins.jetbrains.com/author/me/tokens).
The initial Marketplace listing must already exist.

1. Update `pluginVersion` in `gradle.properties` and merge to `main`.
2. Publish a GitHub release with the matching tag, for example `v0.2.1`.
3. The workflow tests both IDEs, verifies the ZIP, uploads it to Marketplace,
   and attaches the installable ZIP directly to the GitHub release.

GitHub release notes become the plugin's change notes. Drafts and prereleases
are not published to Marketplace. Each upload needs a new version; `0.2.0`
was uploaded manually. JetBrains reviews updates before making them available.

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
