"""Release guard checks using isolated repositories; no remote publication."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

from check_release import check_release, git


class ReleaseCheckTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.directory.name)
        git("init", "-b", "main")
        git("config", "user.name", "Release test")
        git("config", "user.email", "release@example.invalid")
        Path("gradle.properties").write_text("pluginVersion = 0.2.1\n")
        git("add", ".")
        git("commit", "-m", "fixture")
        self.revision = git("rev-parse", "HEAD")
        git("update-ref", "refs/remotes/origin/main", self.revision)
        git("tag", "v0.2.1")

    def tearDown(self):
        os.chdir(self.previous)
        self.directory.cleanup()

    def test_matching_release(self):
        self.assertEqual(self.revision, check_release("v0.2.1"))

    def test_annotated_tag(self):
        git("tag", "-f", "-a", "v0.2.1", "-m", "release")
        self.assertEqual(self.revision, check_release("v0.2.1"))

    def test_version_mismatch(self):
        with self.assertRaisesRegex(ValueError, "pluginVersion"):
            check_release("v0.2.2")

    def test_invalid_or_prerelease_tag(self):
        for tag in ("main", "v0.2.1-beta", "v00.2.1", "v0.2.1; echo injected"):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "vX.Y.Z"):
                check_release(tag)

    def test_missing_tag_is_not_a_branch(self):
        git("tag", "-d", "v0.2.1")
        git("branch", "v0.2.1")
        with self.assertRaises(subprocess.CalledProcessError):
            check_release("v0.2.1")

    def test_wrong_checkout(self):
        git("commit", "--allow-empty", "-m", "later")
        with self.assertRaisesRegex(ValueError, "checkout"):
            check_release("v0.2.1")

    def test_unmerged_commit(self):
        git("checkout", "-b", "unmerged")
        git("commit", "--allow-empty", "-m", "unmerged")
        git("tag", "-f", "v0.2.1")
        with self.assertRaisesRegex(ValueError, "origin/main"):
            check_release("v0.2.1")


if __name__ == "__main__":
    unittest.main()
