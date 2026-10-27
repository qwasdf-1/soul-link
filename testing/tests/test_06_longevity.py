"""Longevity: many reset cycles with two connected players and no progressive degradation.

Opt-in because it is long: ./gradlew integrationTest -Ppytest.args="-m slow"
Cycle count: SOULLINK_LONGEVITY_CYCLES (default 100).
"""

from __future__ import annotations

import os

import pytest

from helpers import levels, start_run, OVERWORLD

CYCLES = int(os.environ.get("SOULLINK_LONGEVITY_CYCLES", "100"))
SOULLINK_CONFIG = {"preloadRadius": 1}


@pytest.mark.slow
def test_many_reset_cycles_without_degradation(server):
    players = [server.bot("Alice"), server.bot("Bob")]
    heap: list[int] = []
    totals: list[int] = []
    seeds: set[int] = set()
    for cycle in range(CYCLES):
        status = start_run(server, players, command="soullink reset")
        seeds.add(status["runSeed"])
        totals.append(status["activation"]["lastTimingsMillis"]["total"])
        assert levels(status)[OVERWORLD]["seed"] == status["runSeed"]
        for bot in players:
            bot.assert_connected()
        if cycle % 10 == 9:
            gc = server.rcon_json("soullink debug gc")
            heap.append(gc["usedHeapMiB"])
            assert gc["retiredLevelsReachable"] <= 3, f"retired levels leak at cycle {cycle}: {gc}"
    for bot in players:
        assert bot.state.play_entered == 1, "no reconnect over the whole run"
    assert len(seeds) == CYCLES
    world_dirs = sorted(p.name for p in (server.stage_dimensions).glob("*")) \
        if (server.stage_dimensions).exists() else []
    assert len(world_dirs) <= 1, f"stale staged data accumulated: {world_dirs}"
    quarantine = server.library_data / "quarantine"
    assert not quarantine.exists() or not any(quarantine.iterdir())
    first, last = sum(totals[:10]) / 10, sum(totals[-10:]) / 10
    print(f"{CYCLES} cycles; mean activation first10 {first:.0f} ms, last10 {last:.0f} ms; heap after GC {heap}")
    assert last < first * 3 + 1000, "activation latency degraded"
    if len(heap) >= 2:
        assert heap[-1] < heap[0] * 2 + 256, f"heap grew: {heap}"
