"""Check the release-notes pipeline in built ZIPs; never publish."""
from io import BytesIO
import os
from pathlib import Path
import subprocess
import unittest
import xml.etree.ElementTree as ET
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parent.parent
NOTES = """## Changes

- **Formatting** with *emphasis* and `list<T>`.
- [Source](https://github.com/hhenson/hgl_jetbrains_plugin)

1. Open a file.
2. Review it.

```hgl
value < limit && label == "a&b"
```

RustRover & CLion — HGL.
"""


class ReleaseNotesTest(unittest.TestCase):
    def build_notes(self, markdown):
        env = os.environ.copy()
        env.pop("RELEASE_NOTES", None)
        if markdown is not None:
            env["RELEASE_NOTES"] = markdown
        result = subprocess.run(
            ["./gradlew", "--console=plain", "buildPlugin"],
            cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, timeout=600,
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        properties = dict(
            (part.strip() for part in line.split("=", 1))
            for line in (ROOT / "gradle.properties").read_text().splitlines()
            if "=" in line and not line.lstrip().startswith("#")
        )
        version = properties["pluginVersion"]
        archive = ROOT / "build/distributions" / f"hgl-jetbrains-plugin-{version}.zip"
        with ZipFile(archive) as distribution:
            jars = [name for name in distribution.namelist()
                    if name.endswith(f"/lib/hgl-jetbrains-plugin-{version}.jar")]
            self.assertEqual(len(jars), 1, distribution.namelist())
            with ZipFile(BytesIO(distribution.read(jars[0]))) as plugin:
                descriptor = ET.fromstring(plugin.read("META-INF/plugin.xml"))
        return descriptor.findtext("change-notes", default="").strip()

    def test_markdown_and_cleared_notes_in_distribution(self):
        html = self.build_notes(NOTES)
        # Parse the decoded CDATA: escaped HTML would leave these elements absent.
        document = ET.fromstring(f"<notes>{html}</notes>")
        self.assertEqual(document.findtext("h2"), "Changes")
        self.assertEqual(len(document.findall("ul/li")), 2)
        self.assertEqual(len(document.findall("ol/li")), 2)
        self.assertEqual(document.findtext(".//strong"), "Formatting")
        self.assertEqual(document.findtext(".//em"), "emphasis")
        self.assertEqual(document.findtext(".//li/code"), "list<T>")
        link = document.find(".//a")
        self.assertIsNotNone(link)
        self.assertEqual(link.text, "Source")
        self.assertEqual(link.get("href"), "https://github.com/hhenson/hgl_jetbrains_plugin")
        self.assertEqual(document.findtext("pre/code").strip(),
                         'value < limit && label == "a&b"')
        self.assertIn("RustRover & CLion — HGL.", "".join(document.itertext()))
        # Rebuild without cleaning to catch stale notes in incremental builds.
        for blank in ("", " \n\t", None):
            with self.subTest(notes=blank):
                self.assertEqual(self.build_notes(blank), "")


if __name__ == "__main__":
    unittest.main()
