#!/usr/bin/env python3
"""Reject releases with a mismatched version, checkout, or source branch."""
from pathlib import Path
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(["git", *args], text=True, stderr=subprocess.PIPE).strip()


def check_release(tag):
    if not re.fullmatch(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", tag):
        raise ValueError("Release tag must be vX.Y.Z (stable versions only).")
    versions = re.findall(r"^pluginVersion\s*=\s*(\S+)\s*$", Path("gradle.properties").read_text(), re.M)
    if versions != [tag[1:]]:
        raise ValueError("Release tag must match pluginVersion in gradle.properties.")
    revision = git("rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}")
    if revision != git("rev-parse", "HEAD"):
        raise ValueError("The checkout does not match the release tag.")
    try:
        git("merge-base", "--is-ancestor", revision, "refs/remotes/origin/main")
    except subprocess.CalledProcessError as error:
        raise ValueError("The tagged commit must be on origin/main.") from error
    return revision


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("Usage: check_release.py vX.Y.Z")
        print(f"Validated {sys.argv[1]} at {check_release(sys.argv[1])}")
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f"Release check failed: {error}")
