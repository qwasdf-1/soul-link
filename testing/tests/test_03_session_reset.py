"""Run-scoped state is reset on live objects between runs.

State is dirtied and inspected with vanilla commands, so the assertions see what the game itself sees,
not Soul Link's view of it.
"""

from __future__ import annotations

import re

import pytest

from helpers import LOBBY, OVERWORLD, passes, start_run


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def test_server_and_player_state_is_reset_between_runs(server, players):
    start_run(server, players)
    for command in (
        "scoreboard objectives add kills dummy",
        "scoreboard objectives setdisplay sidebar kills",
        "scoreboard players set Alice kills 5",
        "team add red",
        "team join red Alice",
        "data modify storage soullink:test value set value 42",
        'bossbar add soullink:test "Leftover"',
        "bossbar set soullink:test players @a",
        "tag Alice add marked",
        "give Alice minecraft:diamond 5",
        "item replace entity Alice enderchest.0 with minecraft:emerald 3",
        "xp add Alice 30 levels",
        "effect give Alice minecraft:speed 600 1",
        "advancement grant Alice only minecraft:adventure/root",
        "spawnpoint Alice ~ ~ ~",
        "weather thunder",
        "time set 18000",
    ):
        server.rcon(command)
    dirty = server.player("Alice")
    assert dirty["inventoryItems"] == 5 and dirty["xpLevel"] == 30 and dirty["tags"] == ["marked"]
    assert passes(server, "if entity @a[name=Alice,advancements={minecraft:adventure/root=true}]")

    status = start_run(server, players, command="soullink reset")

    assert "There are no objectives" in server.rcon("scoreboard objectives list")
    assert "There are no teams" in server.rcon("team list")
    assert "There are no custom bossbars" in server.rcon("bossbar list")
    assert "{}" in server.rcon("data get storage soullink:test")
    clock = server.rcon("time query time")
    assert int(re.findall(r"-?\d+", clock)[-1]) < 400, clock

    alice = server.player("Alice")
    assert alice["dimension"] == OVERWORLD
    assert alice["inventoryItems"] == 0
    assert alice["enderChestEmpty"]
    assert alice["xpLevel"] == 0
    assert alice["effects"] == 0
    assert alice["tags"] == []
    assert alice["health"] == 20.0 and alice["food"] == 20
    assert not passes(server, "if entity @a[name=Alice,advancements={minecraft:adventure/root=true}]")
    # Respawn points from the previous run must not survive (no tie to the old world).
    server.rcon("kill Alice")
    server.wait_until(lambda s: s["status"] == "ENDED", 10, "the run to end")
    players[0].respawn()
    players[0].wait_for(lambda s: s.respawns >= 2 and (s.health or 0) > 0, 15, "respawn")
    respawned = server.player("Alice")
    assert respawned["dimension"] == OVERWORLD
    spawn = status["spawn"]
    assert abs(respawned["x"] - spawn[0]) < 12 and abs(respawned["z"] - spawn[2]) < 12, "world spawn, not the old bed"


def test_late_joiner_is_synced_into_the_running_run(server, players):
    start_run(server, players)
    server.rcon("gamerule natural_health_regeneration false")
    server.rcon("damage Alice 5 minecraft:generic")
    server.wait_until(lambda s: abs(s["shared"]["health"] - 15.0) < 0.01, 10, "shared damage")
    carol = server.bot("Carol")
    carol.wait_for_dimension(OVERWORLD, timeout=30)
    status = server.wait_until(lambda s: any(p for p in s["participants"]) and len(s["participants"]) == 3, 10,
                               "Carol to join the run")
    player = server.player("Carol")
    assert player["gameMode"] == "survival"
    assert abs(player["health"] - 15.0) < 0.01, "late joiners share the current soul"
    assert status["status"] == "RUNNING"
    server.rcon("gamerule natural_health_regeneration true")


def test_reconnecting_participant_keeps_their_run_state(server, players):
    server.rcon("give Carol minecraft:bread 3")
    server.disconnect_bot("Carol")
    returning = server.bot("Carol")
    assert returning.state.dimensions[0].dimension == OVERWORLD
    assert server.player("Carol")["inventoryItems"] == 3


def test_offline_player_is_reset_when_returning_after_a_new_run(server, players):
    server.disconnect_bot("Carol")
    start_run(server, players, command="soullink reset")
    returning = server.bot("Carol")
    returning.wait_for_dimension(OVERWORLD, timeout=30)
    player = server.player("Carol")
    assert player["inventoryItems"] == 0, "stale inventory from an old run must not survive"
    assert player["health"] == 20.0


def test_soullink_stop_returns_everyone_to_the_lobby(server, players):
    marks = [bot.dimension_count() for bot in players]
    server.rcon("soullink stop")
    for bot, mark in zip(players, marks):
        bot.wait_for_dimension(LOBBY, timeout=20, after=mark)
        assert server.player(bot.name)["gameMode"] == "adventure"
    assert server.status()["status"] == "LOBBY"
    # The lobby protects waiting players.
    server.rcon("damage Alice 10 minecraft:generic")
    assert server.player("Alice")["health"] == 20.0
