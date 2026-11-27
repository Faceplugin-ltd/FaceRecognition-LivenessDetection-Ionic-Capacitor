#!/usr/bin/env python3
"""Fetch facerecognitionsdk.aar if missing, then run the Ionic Capacitor Android demo.

Mirrors the README manual steps: fetch missing native binaries, then the
standard npm / Capacitor commands for this repo.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
IS_WIN = os.name == "nt"


def die(msg: str) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    raise SystemExit(1)


def which(name: str) -> str | None:
    hits = [shutil.which(name)]
    if IS_WIN:
        hits.extend([shutil.which(name + ".cmd"), shutil.which(name + ".exe")])
    return next((h for h in hits if h), None)


def need(name: str, hint: str) -> str:
    path = which(name)
    if not path:
        die(f"{name} is not on PATH. {hint}")
    return path


def run(args: list[str], cwd: Path | None = None) -> None:
    print("+", " ".join(str(a) for a in args))
    r = subprocess.run(args, cwd=str(cwd or ROOT))
    if r.returncode:
        raise SystemExit(r.returncode)


AAR = ROOT / "example" / "android" / "libfacesdk" / "facerecognitionsdk.aar"


def main() -> None:
    import fp_releases

    npm = need("npm", "Install Node.js from https://nodejs.org/")
    npx = need("npx", "Install Node.js from https://nodejs.org/")
    example = ROOT / "example"
    print(f"{ROOT.name}: android (ionic/capacitor)")
    if not (ROOT / "node_modules").is_dir():
        run([npm, "install"])
    run([npm, "run", "build"])
    if not (example / "node_modules").is_dir():
        run([npm, "install"], cwd=example)
    run([npm, "run", "build"], cwd=example)
    fp_releases.ensure_engine_aar(ROOT, AAR)
    run([npx, "cap", "sync", "android"], cwd=example)
    run([npx, "cap", "run", "android", "--no-sync"], cwd=example)


if __name__ == "__main__":
    main()
