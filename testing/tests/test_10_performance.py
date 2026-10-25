"""Tick-time cost. Soul Link must cost nothing measurable while it is not preparing or swapping worlds, and
background preparation must not make the server miss ticks.

Soul Link's own per-tick work is timed by the mod itself (``tickCost``); the server's tick time, which
also covers ticking the staged levels, comes from vanilla's ``/tick query`` (last 100 ticks).
"""

from __future__ import annotations

import re
import time

import pytest

from helpers import levels, start_run, wait_true

# The production default, so preparation does its real amount of work.
SOULLINK_CONFIG = {"preloadRadius": 6}
PERCENTILES = re.compile(r"P50: ([\d.]+)ms P95: ([\d.]+)ms P99: ([\d.]+)ms")


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def tick_times(server) -> tuple[float, float, float]:
    """(P50, P95, P99) server tick time in ms over the last 100 ticks."""
    match = PERCENTILES.search(server.rcon("tick query"))
    assert match, "unexpected /tick query output"
    p50, p95, p99 = (float(value) for value in match.groups())
    return p50, p95, p99


def test_idle_soul_link_costs_microseconds_per_tick(server, players):
    start_run(server, players)
    server.wait_prepared()
    time.sleep(20)  # 400 idle ticks with a run in progress and two players linked
    idle = server.status()["tickCost"]["idle"]
    print("Soul Link tick cost while idle:", idle)
    assert idle["samples"] >= 300
    assert idle["meanMicros"] < 50, idle
    assert idle["p99Micros"] < 500, idle


def test_staged_levels_load_without_ticking(server):
    server.rcon("soullink prepare")

    def staged_loaded():
        staged = [level for level in levels(server.status()).values() if level["class"] == "STAGE"]
        return [level for level in staged if level["loadedChunks"] > 0] or None

    for level in wait_true(staged_loaded, 120, "staged chunks to load", interval=0.5):
        assert level["tickingChunks"] == 0, f"{level['key']} block-ticks {level['tickingChunks']} chunks"
    server.wait_prepared()


def test_preparation_does_not_cost_ticks(server, players):
    time.sleep(6)
    idle = tick_times(server)
    server.rcon("soullink prepare")
    # /tick query covers the last 100 ticks (5 s): sample it every 5 s for the whole preparation.
    samples = []
    while server.status()["preparation"].get("active"):
        time.sleep(5)
        samples.append(tick_times(server))
    ready = server.wait_prepared()
    preparing = tuple(max(sample[i] for sample in samples) for i in range(3))
    preparing_cost = server.status()["tickCost"]["preparing"]
    print(f"server tick time ms (P50, P95, P99): idle {idle}, worst 5 s window while preparing {preparing} "
          f"over {len(samples)} windows; preparation took {ready['timingsMillis']}; "
          f"Soul Link's own work while preparing: {preparing_cost}")
    assert preparing[1] < 50.0, "preparation made the server miss ticks (P95 over 50 ms)"
    # Every preparation step does a bounded slice of work per tick.
    worst = {phase: millis for phase, millis in ready["timingsMillis"].items() if phase.startswith("maxTick.")}
    print("worst server-thread step per preparation phase (ms):", worst)
    assert worst and max(worst.values()) < 25, worst
    # Generation is paced, so no tick runs late while chunks generate. A tick can still measure up to the
    # 50 ms deadline: vanilla unloads released chunks (the spawn-search square, then everything while
    # flushing) in the tick's spare time, until the next tick is due. Unpaced generation took 100+ ms.
    for phase in ("spawn_finding", "preloading"):
        assert ready["timingsMillis"][f"maxServerTick.{phase}"] < 60, ready["timingsMillis"]
    for bot in players:
        bot.assert_connected()
