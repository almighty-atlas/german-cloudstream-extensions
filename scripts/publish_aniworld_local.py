#!/usr/bin/env python3
"""Validate locally and publish just AniWorld to the existing CloudStream builds branch.

No GitHub Actions are started. This command requires local Android SDK, JDK 17 and
Git push credentials. Run from checked-out, clean, up-to-date main with --publish.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = "almighty-atlas/german-cloudstream-extensions"
PLUGIN_NAME = "AniWorld"
PLUGINS = {"AniWorld", "Filmo", "SerienStream"}


def run(*args: str, cwd: Path = ROOT, env: dict | None = None) -> None:
    print("+", " ".join(args), flush=True)
    subprocess.run(args, cwd=cwd, env=env, check=True)


def output(*args: str, cwd: Path = ROOT) -> str:
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("ABBRUCH: " + message)


def one_aniworld(items: list[dict]) -> dict:
    matches = [item for item in items if item.get("internalName") == PLUGIN_NAME]
    require(len(matches) == 1, "plugins.json muss genau einen AniWorld-Eintrag enthalten")
    return matches[0]


def assert_test_reports() -> None:
    for name in PLUGINS:
        report_dir = ROOT / "smoke" / name / "build/test-results/test"
        reports = list(report_dir.glob("TEST-*.xml"))
        require(bool(reports), f"JVM-Testbericht fehlt: {name}")
        totals = [0, 0, 0]
        for file in reports:
            suite = ET.parse(file).getroot()
            for idx, key in enumerate(("tests", "failures", "errors")):
                totals[idx] += int(suite.attrib.get(key, "0"))
        require(totals[0] > 0 and totals[1] == 0 and totals[2] == 0,
                f"JVM-Tests fehlgeschlagen oder leer: {name}: {totals}")
        print(f"{name}: {totals[0]} JVM-Tests, 0 Fehler")


def build_and_verify() -> tuple[Path, dict, int]:
    config = (ROOT / "AniWorld/build.gradle.kts").read_text()
    match = re.search(r"(?m)^version\s*=\s*(\d+)\s*$", config)
    require(match is not None, "AniWorld-Version in Gradle nicht gefunden")
    source_version = int(match.group(1))
    require(source_version > 15, "AniWorld-Version muss für das Update größer als 15 sein")

    env = dict(os.environ, GITHUB_REPOSITORY=REPO)
    require(bool(env.get("JAVA_HOME")), "JAVA_HOME für JDK 17 setzen")
    require(bool(env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")),
            "ANDROID_HOME/ANDROID_SDK_ROOT setzen")
    run("python3", "scripts/prepare_cloudstream_gradle.py", env=env)
    run("./gradlew", "-p", "smoke", ":AniWorld:test", ":Filmo:test",
        ":SerienStream:test", "--no-daemon", "--rerun-tasks", env=env)
    assert_test_reports()
    run("./gradlew", ":AniWorld:clean", ":Filmo:clean", ":SerienStream:clean",
        "assembleDebug", "make", "makePluginsJson", "testDebugUnitTest",
        "--no-daemon", "--rerun-tasks", env=env)

    binary = ROOT / "AniWorld/build/AniWorld.cs3"
    require(binary.is_file() and binary.stat().st_size > 0, "AniWorld.cs3 fehlt oder ist leer")
    with zipfile.ZipFile(binary) as archive:
        require(archive.testzip() is None, "CS3 ZIP-Integritätsprüfung fehlgeschlagen")
        names = set(archive.namelist())
        require({"manifest.json", "classes.dex"}.issubset(names),
                "CS3 muss manifest.json und classes.dex enthalten")
        manifest = json.loads(archive.read("manifest.json"))
        require(archive.read("classes.dex").startswith(b"dex\n"), "Ungültiger DEX-Header")
    require(int(manifest.get("version", -1)) == source_version,
            "Version in CS3-Manifest stimmt nicht mit Gradle überein")

    generated = json.loads((ROOT / "build/plugins.json").read_text())
    require(isinstance(generated, list) and
            {item.get("internalName") for item in generated} == PLUGINS,
            "Generiertes plugins.json enthält nicht genau die drei erwarteten Provider")
    info = one_aniworld(generated)
    require(int(info.get("version", -1)) == source_version,
            "Generierte Plugin-Version stimmt nicht überein")
    expected_url = f"https://raw.githubusercontent.com/{REPO}/builds/AniWorld.cs3"
    require(info.get("url") == expected_url,
            "Plugin-URL zeigt nicht auf den bestehenden builds-Branch")
    require(int(info.get("fileSize", -1)) == binary.stat().st_size,
            "Generierte Plugin-Größe stimmt nicht mit CS3 überein")
    print(f"AniWorld v{source_version}: {binary.stat().st_size} Bytes, "
          f"SHA256 {hashlib.sha256(binary.read_bytes()).hexdigest()}")
    return binary, info, source_version


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true",
                        help="Nach allen lokalen Prüfungen AniWorld auf den builds-Branch pushen")
    args = parser.parse_args()

    require(output("git", "branch", "--show-current") == "main",
            "Bitte den gemergten Stand auf main auschecken")
    require(not output("git", "status", "--porcelain"),
            "Lokale Änderungen vorhanden; Veröffentlichung nur aus sauberem Arbeitsbaum")
    run("git", "fetch", "origin", "main", "builds")
    require(output("git", "rev-parse", "HEAD") == output("git", "rev-parse", "origin/main"),
            "main ist nicht identisch mit origin/main")

    binary, info, new_version = build_and_verify()
    if not args.publish:
        print("Alle Prüfungen bestanden; nichts veröffentlicht. Für das Update --publish benutzen.")
        return

    with tempfile.TemporaryDirectory(prefix="aniworld-publish-") as temp:
        worktree = Path(temp) / "builds"
        run("git", "worktree", "add", "--detach", str(worktree), "origin/builds")
        try:
            manifest_path = worktree / "plugins.json"
            published = json.loads(manifest_path.read_text())
            require(isinstance(published, list) and
                    {item.get("internalName") for item in published} == PLUGINS,
                    "Veröffentlichtes plugins.json hat unerwartete Provider")
            old_version = int(one_aniworld(published).get("version", -1))
            require(new_version > old_version,
                    f"Release wäre kein Update: veröffentlicht {old_version}, neu {new_version}")
            revised = [info if item.get("internalName") == PLUGIN_NAME else item
                       for item in published]
            shutil.copyfile(binary, worktree / "AniWorld.cs3")
            manifest_path.write_text(json.dumps(revised, ensure_ascii=False, indent=4) + "\n")
            run("git", "add", "AniWorld.cs3", "plugins.json", cwd=worktree)
            changed = output("git", "diff", "--cached", "--name-only", cwd=worktree).splitlines()
            require(set(changed) == {"AniWorld.cs3", "plugins.json"},
                    f"Unerwartete Änderungen auf builds: {changed}")
            run("git", "-c", "user.name=Local CloudStream Publisher",
                "-c", "user.email=local-publisher@users.noreply.github.com",
                "-c", "commit.gpgsign=false", "commit", "-m",
                f"Publish AniWorld v{new_version} (local verified) [skip ci]", cwd=worktree)
            run("git", "push", "origin", "HEAD:refs/heads/builds", cwd=worktree)
            print(f"AniWorld v{new_version} veröffentlicht – TV kann jetzt Plugin aktualisieren.")
        finally:
            run("git", "worktree", "remove", "--force", str(worktree))


if __name__ == "__main__":
    main()
