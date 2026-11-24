#!/usr/bin/env python3
"""Cheap repository checks that do not need Docker or a JVM.

- every YAML file parses (Compose, Kubernetes, CI workflows)
- every relative Markdown link in the docs and READMEs points at an existing file

Run from the repository root: python scripts/check-docs.py
"""
from __future__ import annotations

import glob
import os
import re
import sys

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

YAML_PATTERNS = [
    "docker-compose*.yml",
    "kubernetes/**/*.yaml",
    "k8s/**/*.yaml",
    ".github/workflows/*.yml",
]

MD_FILES = ["README.md", "CLAUDE.md"] + glob.glob("docs/**/*.md", recursive=True) \
    + glob.glob("services/*/README.md") + glob.glob("functions/*/README.md") \
    + glob.glob("contracts/**/*.md", recursive=True)

# docs/old/ is an archive of the monolith's documentation; its links are historical and not maintained.
SKIP_PREFIXES = ("docs/old/", "docs/old\\")
MD_FILES = [f for f in MD_FILES if not f.replace("\\", "/").startswith("docs/old/")]

LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)")


def yaml_check() -> int:
    failures = 0
    for pattern in YAML_PATTERNS:
        for path in sorted(glob.glob(os.path.join(ROOT, pattern), recursive=True)):
            try:
                with open(path, encoding="utf-8") as handle:
                    list(yaml.safe_load_all(handle))
            except Exception as error:  # noqa: BLE001 - report, do not stop
                failures += 1
                print(f"FAIL yaml  {os.path.relpath(path, ROOT)}: {error}")
    print(f"yaml: {failures} failure(s)")
    return failures


def link_check() -> int:
    failures = 0
    for relative in MD_FILES:
        path = os.path.join(ROOT, relative)
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        for target in LINK.findall(text):
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            cleaned = target.split("#", 1)[0]
            if not cleaned:
                continue
            resolved = os.path.normpath(os.path.join(os.path.dirname(path), cleaned))
            if not os.path.exists(resolved):
                failures += 1
                print(f"FAIL link  {relative} -> {target}")
    print(f"links: {failures} failure(s)")
    return failures


if __name__ == "__main__":
    sys.exit(1 if (yaml_check() + link_check()) else 0)
