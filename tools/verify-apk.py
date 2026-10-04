#!/usr/bin/env python3
"""Lumen2D — APK content verification.

Checks that a built Lumen2D Studio APK is a well-formed, signed, installable Android
package that actually contains the engine's content: the asset library with its
sidecars, the provenance index the editor reads, and the three sample games.

The SDK tools (aapt2, apksigner) are optional inputs: when their output is passed in
the manifest/badging and signature checks run too, otherwise they are reported as
skipped so the same script can be used on a machine without an Android SDK.

Usage:
  tools/verify-apk.py --apk app-android/build/outputs/apk/debug/app-android-debug.apk \
      --expect debug --content-root . \
      --badging /tmp/badging.txt --files /tmp/apkfiles.txt --signer /tmp/signer.txt \
      --report build/apk-report.md

Exit code is 0 when every check passes, 1 otherwise.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

STORED = zipfile.ZIP_STORED
UNCOMPRESSED_EXTENSIONS = ("wav", "png", "json", "lumen", "tmx", "tsx", "fnt")

REQUIRED_SAMPLES = ("hello-lumen2d", "pixel-platformer", "neon-shooter")
REQUIRED_PACKS = ("base", "kenney")
SIDECAR_SUFFIX = ".meta.json"


class Report:
    def __init__(self) -> None:
        self.checks: list[dict] = []
        self.sections: list[tuple[str, list[tuple[str, str]]]] = []

    def check(self, cid: str, ok: bool, detail: str = "") -> bool:
        self.checks.append({"id": cid, "ok": bool(ok), "detail": detail})
        return bool(ok)

    def section(self, title: str, rows: list[tuple[str, str]]) -> None:
        self.sections.append((title, rows))

    @property
    def failures(self) -> list[dict]:
        return [c for c in self.checks if not c["ok"]]

    @property
    def skips(self) -> list[str]:
        return [c["id"] for c in self.checks if c["id"].startswith("skip.")]


def human_bytes(n: int) -> str:
    if n < 1024:
        return f"{n} B"
    if n < 1024 * 1024:
        return f"{n / 1024:.1f} kB"
    return f"{n / (1024 * 1024):.2f} MB"


def sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_text(path: str | None) -> str:
    if path and os.path.isfile(path):
        try:
            with open(path, "r", errors="replace") as handle:
                return handle.read()
        except OSError:
            return ""
    return ""


def parse_badging(text: str) -> dict:
    info: dict = {"permissions": [], "features": []}
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            for key, value in re.findall(r"(\w+)='([^']*)'", line):
                info[key] = value
        elif line.startswith("sdkVersion:"):
            info["minSdk"] = line.split(":", 1)[1].strip().strip("'")
        elif line.startswith("targetSdkVersion:"):
            info["targetSdk"] = line.split(":", 1)[1].strip().strip("'")
        elif line.startswith("launchable-activity:"):
            match = re.search(r"name='([^']*)'", line)
            if match:
                info["launchable"] = match.group(1)
        elif line.startswith("application-label:"):
            info["label"] = line.split(":", 1)[1].strip().strip("'")
        elif line.startswith("uses-permission:"):
            match = re.search(r"name='([^']*)'", line)
            if match:
                info["permissions"].append(match.group(1))
        elif line.startswith("uses-feature"):
            match = re.search(r"name='([^']*)'", line)
            if match:
                info["features"].append((match.group(1), "required='true'" in line))
    return info


def parse_signer(text: str) -> dict:
    info: dict = {"schemes": {}, "certs": []}
    for line in text.splitlines():
        line = line.strip()
        scheme = re.match(r"Verified using (v\d) scheme[^:]*:\s*(true|false)", line)
        if scheme:
            info["schemes"][scheme.group(1)] = scheme.group(2) == "true"
        cert = re.match(r"Signer #\d+ certificate DN:\s*(.+)", line)
        if cert:
            info["certs"].append(cert.group(1).strip())
        if line.startswith("Number of signers:"):
            info["signers"] = line.split(":", 1)[1].strip()
    return info


# --------------------------------------------------------------------------- content

def content_root_sets(root: str) -> dict[str, set[str]]:
    """Files the repository expects to be staged into the APK, as APK entry names."""
    sets: dict[str, set[str]] = {"packs": set(), "sources": set(), "samples": set()}
    mapping = {
        "packs": os.path.join(root, "assets-library", "packs"),
        "sources": os.path.join(root, "assets-library", "sources"),
        "samples": os.path.join(root, "sample-games"),
    }
    for key, base in mapping.items():
        if not os.path.isdir(base):
            continue
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in filenames:
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, base).replace(os.sep, "/")
                sets[key].add(f"assets/{key}/{rel}")
    return sets


def check_pack(zf: zipfile.ZipFile, report: Report, pack_id: str) -> dict:
    manifest_name = f"assets/packs/{pack_id}/pack.json"
    facts = {"id": pack_id, "assets": 0, "sidecars": 0}
    try:
        manifest = json.loads(zf.read(manifest_name))
    except KeyError:
        report.check(f"pack.{pack_id}.manifest", False, f"{manifest_name} is missing")
        return facts
    except json.JSONDecodeError as exc:
        report.check(f"pack.{pack_id}.manifest", False, f"{manifest_name} is not valid JSON: {exc}")
        return facts

    report.check(
        f"pack.{pack_id}.manifest",
        manifest.get("id") == pack_id and bool(manifest.get("files")),
        f"id={manifest.get('id')} files={len(manifest.get('files', []))} license={manifest.get('license')}",
    )

    entries = set(zf.namelist())
    listed = list(manifest.get("files", []))
    facts["assets"] = len(listed)

    missing = [f for f in listed if f"assets/packs/{pack_id}/{f}" not in entries]
    report.check(f"pack.{pack_id}.listed", not missing, "every listed asset is packaged" if not missing else f"missing: {missing[:5]}")

    sidecars = [f for f in listed if not f.endswith((".json", ".md"))]
    missing_sidecars = [f for f in sidecars if f"assets/packs/{pack_id}/{f}{SIDECAR_SUFFIX}" not in entries]
    facts["sidecars"] = len(sidecars) - len(missing_sidecars)
    report.check(
        f"pack.{pack_id}.sidecars",
        not missing_sidecars,
        f"{facts['sidecars']}/{len(sidecars)} assets carry a *.meta.json sidecar"
        if not missing_sidecars
        else f"missing sidecars: {missing_sidecars[:5]}",
    )

    # Every real asset on disk must be listed in the manifest: an unlisted file would be
    # invisible to the asset browser, and a listed-but-absent one breaks `lib://` lookups.
    prefix = f"assets/packs/{pack_id}/"
    on_apk = {
        name[len(prefix):]
        for name in entries
        if name.startswith(prefix)
        and not name.endswith(SIDECAR_SUFFIX)
        and name.rsplit("/", 1)[-1] not in ("pack.json", "ATTRIBUTION.md", "README.md")
    }
    unlisted = sorted(on_apk - set(listed))
    report.check(
        f"pack.{pack_id}.complete",
        not unlisted,
        f"{len(listed)} assets, all listed" if not unlisted else f"unlisted files in the APK: {unlisted[:5]}",
    )

    # Sidecars must be real metadata, not empty placeholders.
    bad_sidecar = None
    for asset in sidecars[:40]:
        try:
            meta = json.loads(zf.read(f"assets/packs/{pack_id}/{asset}{SIDECAR_SUFFIX}"))
        except (KeyError, json.JSONDecodeError) as exc:
            bad_sidecar = f"{asset}: {exc}"
            break
        if not meta.get("license") or not meta.get("displayName"):
            bad_sidecar = f"{asset}: missing displayName/license"
            break
    report.check(
        f"pack.{pack_id}.metadata",
        bad_sidecar is None,
        "sidecars parse and name their licence" if bad_sidecar is None else bad_sidecar,
    )
    return facts


def check_source_index(zf: zipfile.ZipFile, report: Report, pack_id: str) -> dict:
    name = f"assets/sources/{pack_id}/index.json"
    facts = {"id": pack_id, "files": 0}
    try:
        index = json.loads(zf.read(name))
    except KeyError:
        report.check(f"sources.{pack_id}", False, f"{name} is missing")
        return facts
    except json.JSONDecodeError as exc:
        report.check(f"sources.{pack_id}", False, f"{name} is not valid JSON: {exc}")
        return facts

    files = index.get("files", [])
    facts["files"] = len(files)
    ok = index.get("format") == "lumen2d.asset-sources" and bool(files)
    report.check(
        f"sources.{pack_id}",
        ok,
        f"format={index.get('format')} provenance records={len(files)} license={index.get('license')}",
    )

    missing = [f.get("path") for f in files]
    entries = set(zf.namelist())
    absent = [p for p in missing if p and f"assets/packs/{pack_id}/{p}" not in entries]
    report.check(
        f"sources.{pack_id}.resolves",
        not absent,
        "every provenance record points at a packaged asset" if not absent else f"absent: {absent[:5]}",
    )

    # The provenance index must describe exactly the assets the pack manifest publishes.
    try:
        manifest = json.loads(zf.read(f"assets/packs/{pack_id}/pack.json"))
        published = set(manifest.get("files", []))
    except (KeyError, json.JSONDecodeError):
        published = set()
    recorded = {f.get("path") for f in files}
    report.check(
        f"sources.{pack_id}.matches-pack",
        published == recorded,
        f"{len(recorded)} records == {len(published)} published assets"
        if published == recorded
        else f"only in index: {sorted(recorded - published)[:4]} | only in pack: {sorted(published - recorded)[:4]}",
    )
    return facts


def check_samples(zf: zipfile.ZipFile, report: Report) -> dict:
    facts = {"projects": 0, "files": 0}
    names = zf.namelist()
    for sample in REQUIRED_SAMPLES:
        prefix = f"assets/samples/{sample}/"
        files = [n for n in names if n.startswith(prefix)]
        facts["files"] += len(files)
        if not files:
            report.check(f"sample.{sample}", False, "no files packaged")
            continue
        facts["projects"] += 1

        try:
            project = json.loads(zf.read(prefix + "project.lumen"))
        except KeyError:
            report.check(f"sample.{sample}", False, "project.lumen is missing")
            continue
        except json.JSONDecodeError as exc:
            report.check(f"sample.{sample}", False, f"project.lumen is not valid JSON: {exc}")
            continue

        config = project.get("config", {})
        start = config.get("gameplay", {}).get("startScene", "")
        start_ok = bool(start) and (prefix + start) in names
        scenes = [n for n in files if n.endswith(".scene.json")]
        scripts = [n for n in files if n.endswith(".lumen")]
        input_map = prefix + "input_map.json"
        input_ok = input_map in names and json.loads(zf.read(input_map)).get("format") == "lumen2d.input_map"
        scene_ok = all(
            json.loads(zf.read(s)).get("format") == "lumen2d.scene" for s in scenes
        ) if scenes else False

        report.check(
            f"sample.{sample}",
            bool(scenes) and bool(scripts) and input_ok and start_ok and scene_ok,
            f"title={config.get('title')!r} start={start} scenes={len(scenes)} scripts={len(scripts)} "
            f"input_map={'ok' if input_ok else 'bad'}",
        )
    return facts


# ------------------------------------------------------------------------------ main

def main() -> int:
    parser = argparse.ArgumentParser(description="Verify a Lumen2D Studio APK.")
    parser.add_argument("--apk", required=True)
    parser.add_argument("--expect", choices=("debug", "release", "any"), default="any")
    parser.add_argument("--content-root", default=None, help="repository root; enables exact asset comparison")
    parser.add_argument("--badging", default=None, help="aapt2 dump badging output")
    parser.add_argument("--files", default=None, help="aapt2 dump files output")
    parser.add_argument("--signer", default=None, help="apksigner verify output")
    parser.add_argument("--report", default=None, help="write a markdown report here")
    parser.add_argument("--json", dest="json_out", default=None, help="write machine-readable results here")
    args = parser.parse_args()

    report = Report()
    badging_text = read_text(args.badging)
    signer_text = read_text(args.signer)
    files_text = read_text(args.files)

    if not report.check("apk.exists", os.path.isfile(args.apk), args.apk):
        finish(report, args, 1, None, None)
        return 1

    size = os.path.getsize(args.apk)
    digest = sha256(args.apk)
    report.check("apk.size", size > 100_000, human_bytes(size))
    report.check(
        "apk.expected-kind",
        args.expect == "any" or args.expect in os.path.basename(args.apk).lower(),
        f"expect={args.expect} name={os.path.basename(args.apk)}",
    )

    try:
        zf = zipfile.ZipFile(args.apk)
    except zipfile.BadZipFile as exc:
        report.check("apk.zip", False, f"not a zip: {exc}")
        finish(report, args, 1, None, None)
        return 1

    with zf:
        names = zf.namelist()
        report.check("apk.zip", True, f"{len(names)} entries")
        corrupt = zf.testzip()
        report.check("apk.crc", corrupt is None, "all entries pass CRC" if corrupt is None else f"corrupt: {corrupt}")

        for required in ("AndroidManifest.xml", "classes.dex", "resources.arsc"):
            report.check(f"apk.{required}", required in names, "present" if required in names else "missing")

        dex_files = [n for n in names if re.fullmatch(r"classes\d*\.dex", n)]
        dex_bytes = sum(zf.getinfo(n).file_size for n in dex_files)
        first = zf.read(dex_files[0])[:8] if dex_files else b""
        report.check(
            "apk.dex",
            dex_files and first.startswith(b"dex\n") and dex_bytes > 200_000,
            f"{len(dex_files)} dex file(s), {human_bytes(dex_bytes)}, magic={first[:7]!r}",
        )

        # --------------------------------------------------------------- engine content
        pack_facts = [check_pack(zf, report, p) for p in REQUIRED_PACKS]
        source_facts = [check_source_index(zf, report, p) for p in REQUIRED_PACKS]
        sample_facts = check_samples(zf, report)

        asset_names = [n for n in names if n.startswith("assets/")]
        report.check("assets.present", len(asset_names) >= 50, f"{len(asset_names)} packaged asset files")

        stored = []
        deflated = []
        for name in asset_names:
            if not name.lower().endswith(tuple("." + ext for ext in UNCOMPRESSED_EXTENSIONS)):
                continue
            (stored if zf.getinfo(name).compress_type == STORED else deflated).append(name)
        report.check(
            "assets.uncompressed",
            not deflated,
            f"{len(stored)} media/json assets are stored uncompressed (noCompress)"
            if not deflated
            else f"{len(deflated)} media assets are deflated, e.g. {deflated[:3]}",
        )

        if args.content_root:
            expected = content_root_sets(args.content_root)
            for key, want in expected.items():
                have = {n for n in names if n.startswith(f"assets/{key}/")}
                missing = sorted(want - have)
                extra = sorted(have - want)
                report.check(
                    f"content.{key}.exact",
                    not missing and not extra,
                    f"{len(have)} files match the repository"
                    if not missing and not extra
                    else f"missing={missing[:4]} extra={extra[:4]}",
                )

        if args.expect != "release":
            blob = b"".join(zf.read(n) for n in dex_files)
            for klass in (b"dev/lumen2d/core/game/Game", b"dev/lumen2d/studio/MainActivity", b"dev/lumen2d/android/AndroidPlatform"):
                report.check(f"dex.{klass.decode()}", klass in blob, "class present in dex" if klass in blob else "class not found")
        else:
            report.check("release.minified", dex_bytes < 8_000_000, f"release dex is {human_bytes(dex_bytes)} (R8 shrunk)")

        # ------------------------------------------------------------------- manifest
        if badging_text:
            info = parse_badging(badging_text)
            package = info.get("name", "")
            expected_packages = ["dev.lumen2d.studio.debug", "dev.lumen2d.studio"] if args.expect == "any" else (
                ["dev.lumen2d.studio.debug"] if args.expect == "debug" else ["dev.lumen2d.studio"]
            )
            report.check("manifest.package", package in expected_packages, f"package={package}")
            report.check("manifest.minSdk", info.get("minSdk") == "24", f"sdkVersion={info.get('minSdk')} (minSdk 24)")
            report.check("manifest.targetSdk", info.get("targetSdk") == "36", f"targetSdkVersion={info.get('targetSdk')}")
            report.check("manifest.versionName", info.get("versionName") == "1.0.0", f"versionName={info.get('versionName')}")
            report.check(
                "manifest.launcher",
                bool(info.get("launchable", "").endswith("MainActivity")),
                f"launchable-activity={info.get('launchable')}",
            )
            report.check("manifest.label", bool(info.get("label")), f"application-label={info.get('label')}")
            report.check(
                "manifest.vibrate",
                "android.permission.VIBRATE" in info.get("permissions", []),
                f"permissions={info.get('permissions')}",
            )
            touch = [req for name, req in info.get("features", []) if name.endswith("touchscreen")]
            report.check("manifest.touchscreen", not touch or touch == [False], f"touchscreen required={touch}")
        else:
            report.check("skip.badging", True, "aapt2 not available — manifest checks skipped")
            info = {}

        if files_text:
            listed_assets = [n for n in names if n in files_text]
            report.check(
                "apk.files-index",
                "AndroidManifest.xml" in files_text and "assets/packs/base/pack.json" in files_text,
                f"aapt2 indexes {len(listed_assets)} packaged files",
            )
        else:
            report.check("skip.files", True, "aapt2 dump files not available")

        if signer_text:
            signer = parse_signer(signer_text)
            strong = signer["schemes"].get("v2") or signer["schemes"].get("v3")
            report.check(
                "apk.signed",
                bool(strong),
                f"schemes={signer['schemes']} signers={signer.get('signers', '?')}",
            )
            report.check("apk.certificate", bool(signer["certs"]), f"DN={signer['certs'][0] if signer['certs'] else 'none'}")
        else:
            report.check("skip.apksigner", True, "apksigner not available — signature checks skipped")
            signer = {}

    # ---------------------------------------------------------------- report assembly
    rows = [
        ("APK", os.path.basename(args.apk)),
        ("Size", human_bytes(size)),
        ("SHA-256", digest),
        ("Package", info.get("name", "—")),
        ("Version", info.get("versionName", "—")),
        ("SDK", f"min {info.get('minSdk', '—')} / target {info.get('targetSdk', '—')}"),
        ("Signature", ", ".join(f"{k}={'yes' if v else 'no'}" for k, v in signer.get("schemes", {}).items()) or "—"),
        ("Certificate", (signer.get("certs") or ["—"])[0]),
        ("Asset packs", ", ".join(f"{p['id']}: {p['assets']} assets / {p['sidecars']} sidecars" for p in pack_facts)),
        ("Provenance", ", ".join(f"{s['id']}: {s['files']} records" for s in source_facts)),
        ("Sample games", f"{sample_facts['projects']} projects / {sample_facts['files']} files"),
    ]
    report.section("Package", rows)
    finish(report, args, 0 if not report.failures else 1, digest, info)
    return 0 if not report.failures else 1


def finish(report: Report, args, code: int | None, digest: str | None, info: dict) -> None:
    failures = report.failures
    ok_count = len([c for c in report.checks if c["ok"] and not c["id"].startswith("skip.")])
    skipped = len(report.skips)

    print("Lumen2D APK verification")
    print("=" * 62)
    for section, rows in report.sections:
        print(f"{section}:")
        for key, value in rows:
            print(f"  {key:<14} {value}")
        print()
    for check in report.checks:
        if check["id"].startswith("skip."):
            continue
        print(f"  [{'ok' if check['ok'] else 'FAIL'}] {check['id']:<34} {check['detail']}")
    if skipped:
        print(f"  ({skipped} check(s) skipped: no SDK tool output supplied)")
    print("=" * 62)
    print(f"{len(report.checks) - skipped} checks: {ok_count} passed, {len(failures)} failed, {skipped} skipped")

    def write(path: str, body: str) -> None:
        parent = os.path.dirname(os.path.abspath(path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(path, "w") as handle:
            handle.write(body)

    if args.json_out:
        with open(args.json_out, "w") as handle:
            json.dump({"checks": report.checks, "failures": failures, "sha256": digest}, handle, indent=2)

    if args.report:
        lines = ["## Lumen2D APK verification", "", f"`{os.path.basename(args.apk)}` — {len(report.checks) - skipped} checks, {len(failures)} failures", ""]
        for section, rows in report.sections:
            lines += [f"### {section}", "", "| field | value |", "| --- | --- |"]
            lines += [f"| {k} | `{v}` |" for k, v in rows]
            lines.append("")
        lines += ["### Checks", "", "| check | result | detail |", "| --- | --- | --- |"]
        for check in report.checks:
            mark = "⏭ skipped" if check["id"].startswith("skip.") else ("✅" if check["ok"] else "❌")
            lines.append(f"| `{check['id']}` | {mark} | {check['detail']} |")
        body = "\n".join(lines) + "\n"
        write(args.report, body)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a") as handle:
                handle.write(body)

    if code:
        for check in failures[:10]:
            print(f"::error title=APK check {check['id']}::{check['detail']}")
        print(f"::error title=APK verification::FAILED — {len(failures)} of {len(report.checks) - skipped} checks failed")
    else:
        signed = "signed, " if not report.skips or "skip.apksigner" not in report.skips else ""
        print(
            f"::notice title=APK verified::{os.path.basename(args.apk)} is {signed}well-formed and ships "
            f"the asset library with provenance and the sample games ({len(report.checks) - skipped} checks)"
        )


if __name__ == "__main__":
    sys.exit(main())
