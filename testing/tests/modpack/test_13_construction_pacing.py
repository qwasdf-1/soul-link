"""C2ME staged-level constructors stay separated during active play."""

from __future__ import annotations

import time

import pytest

pytestmark = pytest.mark.modpack

SOULLINK_CONFIG = {
    "preloadRadius": 2,
    "autoPrepare": False,
    "activeRunConstructionIntervalTicks": 20,
}


def test_staged_constructors_are_spaced_with_reference_modpack(server):
    player = server.bot("Pacer")
    server.wait_until(lambda status: status["status"] == "RUNNING", 400, "the initial run")
    player.assert_connected()
    response = server.rcon("soullink prepare")
    assert "preparing" in response.lower(), response

    construction_ticks = {}
    deadline = time.monotonic() + 240
    while len(construction_ticks) < 3 and time.monotonic() < deadline:
        active = server.status()["preparation"].get("active", {})
        construction_ticks = active.get("constructedAtTicks", {})
        time.sleep(0.1)

    assert len(construction_ticks) == 3, f"only observed {len(construction_ticks)} staged dimensions"
    construction_ticks = sorted(construction_ticks.values())
    gaps = [later - earlier for earlier, later in zip(construction_ticks, construction_ticks[1:])]
    print("reference-pack staged construction ticks:", construction_ticks, "gaps:", gaps)
    assert all(gap >= 20 for gap in gaps), f"staged constructors were too close together: {gaps}"
