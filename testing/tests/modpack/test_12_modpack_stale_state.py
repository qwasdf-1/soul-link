"""Mods that cache levels across a swap: an explicit incompatibility or a safe adapter, never silent success.

Without its adapter, Chunky keeps the retired Overworld in its selection: ``/chunky start`` after a reset
reports a finished task while generating nothing in the live world. The adapter cancels tasks in retiring
levels and rebinds the selection. The pack's performance mods (Lithium, C2ME, ScalableLux, VMP, Krypton,
FerriteCore) and spark must survive repeated swaps with clients connected and release every retired level.
"""

from __future__ import annotations

import json
import time

import pytest

from helpers import OVERWORLD, start_run, wait_true

pytestmark = pytest.mark.modpack

SOULLINK_CONFIG = {"preloadRadius": 1}
#: Far from the spawn, so only a Chunky task can have generated it.
TASK_CENTER = (3000, 3000)
TASK_REGION = f"r.{TASK_CENTER[0] >> 9}.{TASK_CENTER[1] >> 9}.mca"


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def overworld_regions(server) -> set[str]:
    return {p.name for p in (server.world / "dimensions" / "minecraft" / "overworld" / "region").glob("*.mca")}


def chunky_idle(server) -> bool:
    return "No tasks running" in server.rcon("chunky progress")


def test_running_chunky_task_is_cancelled_before_its_world_is_retired(server, players):
    start_run(server, players, timeout=400)
    server.rcon("chunky world minecraft:overworld")
    server.rcon("chunky center 0 0")
    server.rcon("chunky radius 2000")
    assert "Task started" in server.rcon("chunky start")
    time.sleep(3)
    assert not chunky_idle(server)
    start_run(server, players, command="soullink reset", timeout=400)
    assert any("Cancelled the Chunky task in minecraft:overworld" in line for line in server.lines)
    assert chunky_idle(server)


def test_chunky_after_a_reset_generates_in_the_live_world(server, players):
    assert TASK_REGION not in overworld_regions(server)
    server.rcon(f"chunky center {TASK_CENTER[0]} {TASK_CENTER[1]}")
    server.rcon("chunky radius 64")
    assert f"Task started in {OVERWORLD}" in server.rcon("chunky start")
    wait_true(lambda: chunky_idle(server), 120, "the Chunky task to finish")
    server.rcon("save-all flush")
    wait_true(lambda: TASK_REGION in overworld_regions(server), 30,
              "the task's chunks in the live canonical Overworld")


def test_repeated_swaps_release_every_retired_level(server, players):
    # The reference pack includes WTHIT and Bad Packets, so their server hooks are present through each
    # transition. These protocol-only bots do not send WTHIT's client tooltip request; a playtest with a
    # WTHIT-enabled client is still needed to reproduce that exact packet path.
    timings = []
    for _ in range(4):
        status = start_run(server, players, command="soullink reset", timeout=400)
        timings.append(status["activation"]["lastTimingsMillis"])
    print("activation timings with the reference pack:", timings)
    for bot in players:
        bot.assert_connected()
        assert bot.state.play_entered == 1, "no reconnects"

    def released():
        gc = json.loads(server.rcon("soullink debug gc"))
        return gc["retiredLevelsReachable"] == 0 and gc
    # spark holds the last level in its rolling statistics for a few seconds; nothing may hold it longer.
    wait_true(released, 90, "every retired level to be collectable", interval=10)
