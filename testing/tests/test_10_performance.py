"""Tick-time cost. Soul Link must cost nothing measurable while it is not preparing or swapping worlds, and
background preparation must not make the server miss ticks.

Soul Link's own per-tick work is timed by the mod itself (``tickCost``); the server's tick time, which
also covers ticking the staged levels, comes from vanilla's ``/tick query`` (last 100 ticks). The game-loop
thread's own CPU time over each phase is measured with mc-lab and reported, not budgeted: it depends on
the host. Comparing one build's cost with another's is the paired lab measurement in
``test_90_lab_build_comparison.py``.
"""

from __future__ import annotations

import time

import pytest

from helpers import levels, start_run, wait_true
from mc_lab.ticks import parse_tick_query, worst_window

# The production default, so preparation does its real amount of work.
SOULLINK_CONFIG = {"preloadRadius": 6}
TICKS_PER_SECOND = 20


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def tick_times(server) -> dict[str, float]:
    """Server tick time over the last 100 ticks: average, P50, P95, P99 (ms) and the sample size."""
    return parse_tick_query(server.rcon("tick query"))


def cpu_ms_per_tick(window: dict) -> float:
    """The server thread's CPU per tick over a window, at the 20-per-second target rate."""
    return window["cpu_ns"] / 1e6 / (window["wall_ns"] / 1e9 * TICKS_PER_SECOND)


def test_idle_soul_link_costs_microseconds_per_tick(server, players, server_thread):
    start_run(server, players)
    server.wait_prepared()
    # 400 idle ticks with a run in progress and two players linked.
    window = server_thread(server).measure(lambda: time.sleep(20))
    idle = server.status()["tickCost"]["idle"]
    print("Soul Link tick cost while idle:", idle)
    print(f"server thread while idle: {cpu_ms_per_tick(window):.3f} ms CPU per tick", window)
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


def test_preparation_does_not_cost_ticks(server, players, server_thread):
    start_run(server, players)
    server.wait_until(
        lambda status: status["status"] == "RUNNING"
        and status["preparation"].get("active") is None
        and status["preparation"].get("readyCount", 0) >= 1,
        360,
        "the live run and its future-run pool to settle",
    )
    thread = server_thread(server)
    idle_cpu = thread.measure(lambda: time.sleep(6))
    idle = tick_times(server)
    # /tick query covers the last 100 ticks (5 s): sample it every 5 s for the whole preparation.
    samples = []
    with thread.window() as preparing_cpu:
        response = server.rcon("soullink prepare")
        assert "preparing a run" in response.lower(), response
        while server.status()["preparation"].get("active"):
            time.sleep(5)
            samples.append(tick_times(server))
    ready = server.wait_prepared()
    preparing = worst_window(samples)
    preparing_cost = server.status()["tickCost"]["preparing"]
    print(f"server tick time: idle {idle}, worst 5 s window while preparing {preparing} "
          f"over {len(samples)} windows; preparation took {ready['timingsMillis']}; "
          f"Soul Link's own work while preparing: {preparing_cost}")
    print(f"server thread CPU per tick: idle {cpu_ms_per_tick(idle_cpu):.3f} ms, preparing "
          f"{cpu_ms_per_tick(preparing_cpu.result):.3f} ms ({preparing_cpu.result['cpu_ns'] / 1e9:.2f} s "
          f"over the preparation)", idle_cpu, preparing_cpu.result)
    assert preparing["p95_ms"] < 50.0, "preparation made the server miss ticks (P95 over 50 ms)"
    # Every preparation step does a bounded slice of work per tick.
    worst = {phase: millis for phase, millis in ready["timingsMillis"].items() if phase.startswith("maxTick.")}
    print("worst server-thread step per preparation phase (ms):", worst)
    assert worst and max(worst.values()) < 25, worst
    # The full tick includes vanilla entity and chunk work outside the preparation slice. Keep sustained
    # tick time under the 50 ms budget above, and catch a severe isolated hitch without failing on a single
    # noisy tick from that surrounding work.
    for phase in ("spawn_finding", "preloading"):
        assert ready["timingsMillis"][f"maxServerTick.{phase}"] < 80, ready["timingsMillis"]
    for bot in players:
        bot.assert_connected()
