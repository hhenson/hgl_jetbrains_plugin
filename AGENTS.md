# Plugin rules

- Keep documentation and discussion terse.
- Read docs/design.md. Update it with changes to the editor architecture.
- hgl_spec owns language rules. hgl_core owns portable conformance cases.
- Pin both under spec/ to the revisions selected by hgl-compiler.
- Keep open language questions in the owning repository's issues.
- Run tools/refresh_test_data.sh after grammar or pin changes. Review fixture diffs.
- Test editor behavior on both CLion and RustRover and verify the same distribution ZIP.
- Before every push, check the branch's PR state. If merged, start a fresh
  codex/ branch from origin/main and open a new PR for new changes.
- Keep machine paths and credentials outside tracked files and PR metadata.
