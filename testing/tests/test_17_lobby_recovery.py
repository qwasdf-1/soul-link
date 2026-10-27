"""Lobby perimeter, rescue paths, and the visible world-swap transition."""

from __future__ import annotations

import pytest

from helpers import LOBBY, passes, wait_true


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def test_lobby_rescue_and_run_transition_notices(server, players):
    server.wait_until(lambda state: state["status"] == "RUNNING", 240,
                      "the first run to start automatically")
    server.wait_prepared()
    server.rcon("soullink stop")
    server.wait_until(lambda state: state["status"] == "LOBBY", 10, "the lobby")

    # The two-block invisible wall surrounds the outside edge on all four sides.
    for x, y, z in ((-7, 65, 0), (7, 66, 0), (0, 65, -7), (0, 66, 7)):
        assert passes(server, f"in {LOBBY} if block {x} {y} {z} minecraft:barrier")

    # The five-second periodic check catches a player who bypasses the perimeter.
    server.rcon(f"execute in {LOBBY} run tp Alice 0 -1 0")
    wait_true(lambda: server.player("Alice")["y"] > 64, 8,
              "the periodic lobby fall rescue to return Alice to the platform")

    # Void damage is cancelled and immediately teleports the player back to spawn.
    server.rcon(f"execute in {LOBBY} run tp Alice 0 -20 0")
    server.rcon("damage Alice 1 minecraft:out_of_world")
    rescued = server.player("Alice")
    assert rescued["y"] > 64
    assert rescued["dimension"] == LOBBY
    assert rescued["health"] == 20.0, "lobby void damage should be canceled"

    # Starting a prepared run makes the blocking transition and completion visible to everyone.
    message_counts = [len(bot.state.messages) for bot in players]
    server.rcon("soullink start")
    server.wait_until(lambda state: state["status"] == "RUNNING", 30,
                      "the prepared run to enter play")
    for bot, message_count in zip(players, message_counts):
        bot.wait_for(
            lambda state, start=message_count: any(
                "World swap starting" in message for message in state.messages[start:]
            ),
            10,
            "receive the world-swap notice",
        )
        bot.wait_for(
            lambda state, start=message_count: any(
                "New Soul Link run is ready" in message for message in state.messages[start:]
            ),
            10,
            "receive the run-ready notice",
        )
