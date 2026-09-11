#!/usr/bin/env python3
"""Fail the build if any fastlane text exceeds F-Droid's character limits.

F-Droid silently truncates over-long text, and a changelog cut off mid-sentence
on the listing page is the first anyone notices. The limits mirror
fdroidserver's `char_limits` defaults. Lengths count characters, not bytes, so
umlauts do not eat the budget twice.
"""

import sys
from pathlib import Path

LIMITS = {
    "title.txt": 30,
    "short_description.txt": 80,
    "full_description.txt": 4000,
}
CHANGELOG_LIMIT = 500

repo = Path(__file__).resolve().parent.parent
root = repo / "fastlane" / "metadata" / "android"
problems = []


def check(path: Path, limit: int) -> None:
    n = len(path.read_text(encoding="utf-8").strip())
    if n > limit:
        problems.append(f"{path.relative_to(repo)}: {n} characters > {limit}")


for locale_dir in sorted(p for p in root.iterdir() if p.is_dir()):
    for name, limit in LIMITS.items():
        f = locale_dir / name
        if f.is_file():
            check(f, limit)
    for f in sorted((locale_dir / "changelogs").glob("*.txt")):
        check(f, CHANGELOG_LIMIT)

if problems:
    print("fastlane text over F-Droid's limits:", file=sys.stderr)
    for p in problems:
        print(f"  {p}", file=sys.stderr)
    sys.exit(1)

print("fastlane metadata is within F-Droid's character limits")
