#!/usr/bin/env python3
"""Finds new IntelliJ Platform and Gitea releases the build doesn't cover yet.

    scripts/check-version-updates.py            # report only
    scripts/check-version-updates.py --write    # also update the files

IntelliJ (platforms/<line>/platform.properties, docs/platform-versions.md):
  - a newer build of the line a profile compiles against: the newest
    release, or the newest EAP/RC while the line has no release yet;
  - a released or EAP line inside a profile's range that verifyLines lacks;
  - a line newer than every profile's untilBuild: the newest profile's range
    is extended to it, or with --new-profile a new profile is created from
    the newest one (for when the extended range fails verifyPlugin).

Gitea (.github/gitea-versions.txt): the newest patch of every tested release
series, and the newest release when its series is newer than all tested ones.

Prints a Markdown summary, used as the pull request body. Exits 0 with or
without updates; "No updates." means there is nothing to do.
"""

import argparse
import json
import re
import shutil
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLATFORMS = ROOT / "platforms"
GITEA_VERSIONS = ROOT / ".github" / "gitea-versions.txt"

JETBRAINS_RELEASES = "https://data.services.jetbrains.com/products/releases?code=IIU&type=release,eap,rc"
GITEA_DOWNLOADS = "https://dl.gitea.com/gitea/"


def fetch(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "gitea-plugin-version-check"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode()


def version_key(version: str) -> tuple:
    return tuple(int(part) for part in re.findall(r"\d+", version))


# --- IntelliJ Platform ---------------------------------------------------------


class Profile:
    """One platforms/<line>/platform.properties."""

    def __init__(self, directory: Path):
        self.directory = directory
        self.file = directory / "platform.properties"
        self.text = self.file.read_text()

    @property
    def name(self) -> str:
        return self.directory.name

    def get(self, key: str) -> str:
        match = re.search(rf"^{key}\s*=\s*(.*)$", self.text, re.M)
        if not match:
            raise SystemExit(f"{self.file} lacks {key}")
        return match.group(1).strip()

    def set(self, key: str, value: str) -> None:
        self.text = re.sub(rf"^({key}\s*=\s*).*$", lambda m: m.group(1) + value, self.text, count=1, flags=re.M)

    @property
    def since(self) -> int:
        return int(self.get("sinceBuild"))

    @property
    def until(self) -> int:
        return int(self.get("untilBuild").split(".")[0])

    @property
    def verify_lines(self) -> list[int]:
        return [int(line) for line in self.get("verifyLines").split(",") if line.strip()]

    def save(self) -> None:
        self.file.write_text(self.text)


def ide_releases() -> dict[int, list[dict]]:
    """IntelliJ IDEA Ultimate builds by line (252, 253, ...), newest first."""
    releases = json.loads(fetch(JETBRAINS_RELEASES))["IIU"]
    by_line: dict[int, list[dict]] = {}
    for release in releases:
        line = int(release["build"].split(".")[0])
        by_line.setdefault(line, []).append(release)
    for builds in by_line.values():
        builds.sort(key=lambda r: version_key(r["build"]), reverse=True)
    return by_line


def newest_build(builds: list[dict]) -> tuple[str, str]:
    """The notation intellijIdea() takes for a line's newest build: the
    version for a release, the build number for an EAP or RC, and its type."""
    released = [r for r in builds if r["type"] == "release"]
    if released:
        newest = max(released, key=lambda r: version_key(r["version"]))
        return newest["version"], "release"
    return builds[0]["build"], builds[0]["type"].upper()


def check_platforms(write: bool, new_profile: bool) -> list[str]:
    profiles = sorted((Profile(d) for d in PLATFORMS.iterdir() if (d / "platform.properties").exists()),
                      key=lambda p: p.since)
    by_line = ide_releases()
    changes: list[str] = []

    newest_line = max(by_line)
    newest_profile = profiles[-1]
    if newest_line > newest_profile.until:
        new_lines = [line for line in sorted(by_line) if line > newest_profile.until]
        if new_lines and new_profile:
            line = new_lines[0]
            target = PLATFORMS / str(line)
            notation, kind = newest_build(by_line[line])
            changes.append(f"- New profile `platforms/{line}` for IDE line {line} ({kind} `{notation}`), "
                           f"copied from `platforms/{newest_profile.name}`.")
            if write:
                shutil.copytree(newest_profile.directory, target)
                profile = Profile(target)
                profile.set("platformVersion", notation)
                profile.set("sinceBuild", str(line))
                profile.set("untilBuild", f"{line}.*")
                profile.set("verifyLines", str(line))
                profile.save()
                profiles.append(profile)
        elif new_lines:
            line = new_lines[-1]
            changes.append(f"- `platforms/{newest_profile.name}`: untilBuild `{newest_profile.get('untilBuild')}` → "
                           f"`{line}.*` for IDE line(s) {', '.join(map(str, new_lines))}.")
            newest_profile.set("untilBuild", f"{line}.*")

    for profile in profiles:
        current = profile.get("platformVersion")
        builds = by_line.get(profile.since)
        if builds:
            notation, kind = newest_build(builds)
            if notation != current and version_key(notation) != version_key(current):
                changes.append(f"- `platforms/{profile.name}`: platformVersion `{current}` → `{notation}` ({kind}).")
                profile.set("platformVersion", notation)

        wanted = [line for line in sorted(by_line) if profile.since <= line <= profile.until]
        if wanted != profile.verify_lines:
            changes.append(f"- `platforms/{profile.name}`: verifyLines `{profile.get('verifyLines')}` → "
                           f"`{','.join(map(str, wanted))}`.")
            profile.set("verifyLines", ",".join(map(str, wanted)))

        if write:
            profile.save()
    return changes


# --- Gitea ---------------------------------------------------------------------


def gitea_releases() -> list[str]:
    """Final Gitea releases (no -rc, -dev or nightly), newest first."""
    index = fetch(GITEA_DOWNLOADS)
    versions = set(re.findall(r'href="/gitea/(\d+\.\d+\.\d+)/"', index))
    return sorted(versions, key=version_key, reverse=True)


def series(version: str) -> str:
    return ".".join(version.split(".")[:2])


def check_gitea(write: bool) -> list[str]:
    lines = GITEA_VERSIONS.read_text().splitlines()
    tested = [line.strip() for line in lines if line.strip() and not line.startswith("#")]
    releases = gitea_releases()
    newest_by_series: dict[str, str] = {}
    for release in releases:
        newest_by_series.setdefault(series(release), release)

    changes: list[str] = []
    updated = []
    for version in tested:
        newest = newest_by_series.get(series(version), version)
        if newest != version:
            changes.append(f"- Gitea {series(version)}: `{version}` → `{newest}`.")
        updated.append(newest)
    # Only the newest of several new series: the tests then cover the
    # oldest supported and the newest release, and the series in between
    # that were tested while current.
    newest = releases[0]
    if version_key(series(newest)) > version_key(series(max(tested, key=version_key))):
        changes.append(f"- New Gitea release series {series(newest)}: `{newest}`.")
        updated.append(newest)

    if write and changes:
        header = [line for line in lines if line.startswith("#") or not line.strip()]
        GITEA_VERSIONS.write_text("\n".join(header + updated) + "\n")
    return changes


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true", help="update the files, not only report")
    parser.add_argument("--new-profile", action="store_true",
                        help="give a new IDE line its own profile instead of extending the newest one")
    args = parser.parse_args()

    platform_changes = check_platforms(args.write, args.new_profile)
    gitea_changes = check_gitea(args.write)
    if not platform_changes and not gitea_changes:
        print("No updates.")
        return
    if platform_changes:
        print("### IntelliJ Platform\n")
        print("\n".join(platform_changes) + "\n")
    if gitea_changes:
        print("### Gitea\n")
        print("\n".join(gitea_changes) + "\n")


if __name__ == "__main__":
    sys.exit(main())
