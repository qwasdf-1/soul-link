"""Active-run staged dimensions are constructed at the configured interval."""

from __future__ import annotations

import time

SOULLINK_CONFIG = {
    "preloadRadius": 2,
    "autoPrepare": False,
    "activeRunConstructionIntervalTicks": 20,
}


def test_future_dimensions_are_spaced_during_a_run(server):
    player = server.bot("Pacer")
    server.wait_until(lambda status: status["status"] == "RUNNING", 240, "the initial run")
    player.assert_connected()
    response = server.rcon("soullink prepare")
    assert "preparing" in response.lower(), response

    construction_ticks = {}
    deadline = time.monotonic() + 180
    while len(construction_ticks) < 3 and time.monotonic() < deadline:
        status = server.status()
        active = status["preparation"].get("active", {})
        construction_ticks = active.get("constructedAtTicks", {})
        time.sleep(0.1)

    assert len(construction_ticks) == 3, f"only observed {len(construction_ticks)} staged dimensions"
    construction_ticks = sorted(construction_ticks.values())
    gaps = [later - earlier for earlier, later in zip(construction_ticks, construction_ticks[1:])]
    assert all(gap >= 20 for gap in gaps), f"staged constructors were too close together: {gaps}"
    # This focused check ends after construction; spawn/preload completion has separate coverage.
