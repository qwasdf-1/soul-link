"""Shared scenario steps for the real-server suite."""

from __future__ import annotations

import math
import time
from typing import Any

from terra_rasa_testkit.bot import Bot
from terra_rasa_testkit.server import ServerProcess


class SoulLinkServer(ServerProcess):
    """A test server driven through Soul Link's commands and configured by config/soullink.json."""

    CONFIG_FILE = "soullink.json"
    STATUS_COMMAND = "soullink status"
    PLAYER_COMMAND = "soullink debug player {name}"
    #: Pregeneration adds about a minute to every preparation; modules that test it opt in.
    CONFIG_DEFAULTS = {"pregeneration": {}}

OVERWORLD = "minecraft:overworld"
NETHER = "minecraft:the_nether"
END = "minecraft:the_end"
LOBBY = "soullink:lobby"


def levels(status: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {level["key"]: level for level in status["levels"]}


def start_run(server: ServerProcess, bots: list[Bot], command: str = "start", timeout: float = 240.0) -> dict[str, Any]:
    """Waits for a prepared run, starts it and waits until every bot is respawned into the new Overworld."""
    ready = server.wait_prepared(timeout)
    marks = [bot.dimension_count() for bot in bots]
    output = server.rcon(command)
    assert "failed" not in output.lower(), output
    for bot, mark in zip(bots, marks):
        bot.wait_for_dimension(OVERWORLD, timeout=60, after=mark)
    status = server.wait_until(lambda s: s["status"] == "RUNNING" and s["activeRunId"] == ready["runId"], 30,
                               "the prepared run to be running")
    assert status["globalSeed"] == ready["seed"]
    return status


def passes(server: ServerProcess, execute_condition: str) -> bool:
    """Evaluates a vanilla ``execute if ...`` condition over RCON."""
    output = server.rcon(f"execute {execute_condition}")
    return "Test passed" in output


def wait_true(predicate, timeout: float, what: str, interval: float = 0.25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    raise AssertionError(f"timed out waiting for {what}")


def block_pos(player: dict[str, Any]) -> tuple[int, int, int]:
    return math.floor(player["x"]), math.floor(player["y"]), math.floor(player["z"])


#: Vanilla places a new player on a collision-free spot within the respawn_radius game rule (default 10).
RESPAWN_RADIUS = 10


def near_spawn(player: dict[str, Any], spawn: list[int]) -> bool:
    return math.hypot(player["x"] - (spawn[0] + 0.5), player["z"] - (spawn[2] + 0.5)) <= RESPAWN_RADIUS * math.sqrt(2) + 1
