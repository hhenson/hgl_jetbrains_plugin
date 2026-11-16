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

[Design](docs/design.md) describes the editor/compiler boundary.
[Project rules](AGENTS.md) apply to development. Licensed under [MIT](LICENSE).
