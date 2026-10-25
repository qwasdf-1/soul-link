"""Pregeneration with Soul Link's defaults: 500 blocks around the run spawn, 96 in the Nether, 160 around
the End island. The next run pregenerates in the background while players play; /start and /reset never
wait for it, and what a run did not pregenerate yet continues in its live world.
"""

from __future__ import annotations

import re
import time

import pytest

from helpers import OVERWORLD, wait_true

SOULLINK_CONFIG = {"preloadRadius": 6,
                   "pregeneration": {"minecraft:overworld": 500, "minecraft:the_nether": 96, "minecraft:the_end": 160}}
PERCENTILES = re.compile(r"P50: ([\d.]+)ms P95: ([\d.]+)ms P99: ([\d.]+)ms")


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def active(server) -> dict:
    return server.status()["preparation"].get("active") or {}


def live(server) -> dict[str, dict]:
    return {area["dimension"]: area for area in server.status()["pregeneration"]["live"]}


def tick_times(server) -> tuple[float, float, float]:
    p50, p95, p99 = (float(v) for v in PERCENTILES.search(server.rcon("tick query")).groups())
    return p50, p95, p99


def enter(server, players, command: str) -> float:
    """Issues /start or /reset while the next run is pregenerating; returns how long the players waited."""
    wait_true(lambda: active(server).get("state") == "PREGENERATING" and active(server).get("pregenerated", 0) > 100,
              240, "the next run to be pregenerating")
    marks = [bot.dimension_count() for bot in players]
    requested = time.monotonic()
    server.rcon(command)
    for bot, mark in zip(players, marks):
        bot.wait_for_dimension(OVERWORLD, timeout=60, after=mark)
    server.wait_until(lambda s: s["status"] == "RUNNING", 30, "the run")
    return time.monotonic() - requested


def test_start_does_not_wait_for_pregeneration(server, players):
    waited = enter(server, players, "start")
    print(f"/start during pregeneration: players waited {waited:.1f} s")
    assert waited < 20
    area = live(server)[OVERWORLD]
    assert area["done"] < area["total"], "the rest of the run's area continues in the live world"


def test_background_pregeneration_keeps_ticks_in_budget(server, players):
    # The next run prepares and pregenerates while this run's live area is generated too. The first 5 s
    # after /start hold the swap itself, a known one-off pause that /tick query would report as its P99.
    time.sleep(6)
    windows = []
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline and (active(server) or server.status()["pregeneration"]["live"]):
        time.sleep(5)
        windows.append(tick_times(server))
    worst = tuple(max(w[i] for w in windows) for i in range(3))
    print(f"server tick ms (P50, P95, P99), worst 5 s window over {len(windows)} windows of pregeneration: {worst}")
    assert worst[1] < 50, "pregeneration made the server miss ticks"
    for bot in players:
        bot.assert_connected()


def test_reset_does_not_wait_for_pregeneration(server, players):
    waited = enter(server, players, "reset")
    print(f"/reset during pregeneration: players waited {waited:.1f} s")
    assert waited < 20


def test_a_full_default_area(server, players):
    ready = server.wait_prepared(900)
    chunks = sum(task["next"] for task in ready["pregeneration"])
    millis = ready["timingsMillis"]["pregenerating"]
    print(f"default area: {chunks} chunks pregenerated in {millis / 1000:.0f} s with two players online and a run "
          f"in progress ({chunks * 1000 / max(millis, 1):.0f} chunks/s)")
    assert ready["timingsMillis"]["maxServerTick.pregenerating"] < 60
    for bot in players:
        bot.assert_connected()
