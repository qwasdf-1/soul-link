"""The reference modpack suite runs on the install built by ./gradlew modpackTest."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

LOCK = Path(__file__).resolve().parents[3] / "modpack" / "reference-pack.lock.json"


def pack() -> dict:
    return json.loads(LOCK.read_text())


@pytest.fixture(scope="session", autouse=True)
def reference_pack_installed(env):
    install = env[0]
    installed = {jar.name for jar in (install / "mods").iterdir()}
    missing = [mod["file"] for mod in pack()["mods"] if mod["file"] not in installed]
    if missing:
        pytest.exit(f"Not a reference-pack install ({install}); run ./gradlew modpackTest. Missing: {missing}", returncode=2)
