"""Operator onboarding, consolidated commands, timer controls and run rules."""

from __future__ import annotations

import time

import pytest

from helpers import start_run, wait_true


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def test_operator_help_controls_and_run_rules(server, players):
    response = server.rcon("soullink prepare 8675309 0")
    assert "wait until it enters play" in response.lower()
    status = server.wait_until(lambda state: state["status"] == "RUNNING", 240,
                               "the automatically started first run")
    server.wait_until(lambda state: state["preparation"].get("active") is not None, 30,
                      "background preparation to start after the first run")
    assert "future run is already being prepared" in server.rcon("soullink prepare random").lower()
    assert "future run is already being prepared" in server.rcon("soullink prepare random 0").lower()
    response = server.rcon("soullink prepare 8675310 0")
    assert "future run is already being prepared" in response.lower()

    help_text = server.rcon("soullink help")
    for command in ("/soullink start", "/soullink reset", "/soullink stop", "/soullink prepare",
                    "/soullink rules", "/soullink pause", "/soullink resume", "/soullink setup"):
        assert command in help_text
    readable_status = server.rcon("soullink status")
    assert "Soul Link status" in readable_status and "{" not in readable_status

    # The former top-level aliases were removed; they must not execute the corresponding actions.
    for legacy in ("start", "reset", "runinfo", "stoprun"):
        assert "Status: RUNNING" not in server.rcon(legacy)

    server.rcon("soullink rules naturalRegeneration false")
    assert server.status()["rules"]["naturalRegeneration"] is False
    server.rcon("soullink rules naturalRegeneration true")
    assert server.status()["rules"]["naturalRegeneration"] is True

    server.rcon("soullink pause")
    paused = server.status()
    assert paused["timerPaused"] is True
    time.sleep(1.2)
    assert server.status()["elapsedTicks"] == paused["elapsedTicks"]
    server.rcon("damage Alice 2 minecraft:generic")
    wait_true(lambda: server.status()["shared"]["health"] < 20.0, 10,
              "shared health to keep responding while the timer is paused")
    server.rcon("soullink resume")
    wait_true(lambda: server.status()["elapsedTicks"] > paused["elapsedTicks"], 5,
              "the run timer to resume")

    server.rcon("soullink rules halfHeart true")
    half_heart = start_run(server, players, command="soullink reset")
    assert half_heart["rules"]["halfHeart"] is True
    assert half_heart["shared"]["health"] == 1.0
    assert all(server.player(bot.name)["health"] == 1.0 for bot in players)

    server.rcon("soullink rules halfHeart false")
    normal_health = start_run(server, players, command="soullink reset")
    assert normal_health["rules"]["halfHeart"] is False
    assert normal_health["shared"]["health"] == 20.0
    assert all(server.player(bot.name)["health"] == 20.0 for bot in players)
