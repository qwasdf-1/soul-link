"""Vanilla's special spawners on the replaced Overworld.

A new Overworld must get its own set of vanilla's five special spawners (phantoms, patrols, cats,
sieges, wandering traders), tick them, and spawn with them under vanilla's conditions. The wandering
trader's timer is server-global saved data, so a new run restarts it like a new world would. Phantoms
are spawned for real: an insomniac player in the open at night. Patrols, cats and sieges need five
in-game days or a village, so for those the test shows that the new instances tick.
"""

from __future__ import annotations

import json
import time

import pytest

from helpers import OVERWORLD, levels, passes, start_run, wait_true

VANILLA_SPAWNERS = ["PhantomSpawner", "PatrolSpawner", "CatSpawner", "VillageSiege", "WanderingTraderSpawner"]
#: Phantoms only come for players who have not slept for three in-game days.
INSOMNIA_TICKS = 10_000_000


@pytest.fixture(scope="module")
def players(server):
    server.rcon("difficulty hard")
    return [server.bot("Alice"), server.bot("Bob")]


def spawners(server) -> dict:
    return json.loads(server.rcon("soullink debug spawners"))


def by_type(probe: dict) -> dict[str, dict]:
    return {spawner["type"]: spawner for spawner in probe["spawners"]}


def sprint(server, ticks: int) -> None:
    server.rcon(f"tick sprint {ticks}")
    wait_true(lambda: "sprint" not in server.rcon("tick query").lower(), 120, "the sprint to finish", interval=1)


def test_each_run_overworld_ticks_its_own_vanilla_spawners(server, players):
    status = start_run(server, players)
    first = spawners(server)
    assert [s["type"] for s in first["spawners"]] == VANILLA_SPAWNERS
    assert first["level"] == levels(status)[OVERWORLD]["identity"], "the probe reads the live Overworld"
    # A new world's trader timer, then 1,300 ticks of the run.
    assert first["wanderingTrader"] == {"spawnDelay": 24000, "spawnChance": 25}
    sprint(server, 1300)
    later = by_type(spawners(server))
    assert later["WanderingTraderSpawner"]["counters"]["tickDelay"] != by_type(first)["WanderingTraderSpawner"]["counters"]["tickDelay"]
    assert later["PatrolSpawner"]["counters"]["nextTick"] != by_type(first)["PatrolSpawner"]["counters"]["nextTick"]
    assert later["CatSpawner"]["counters"]["nextTick"] != by_type(first)["CatSpawner"]["counters"]["nextTick"]
    assert spawners(server)["wanderingTrader"]["spawnDelay"] == 24000 - 1200, "the trader timer advanced"

    status = start_run(server, players, command="soullink reset")
    second = spawners(server)
    assert second["level"] == levels(status)[OVERWORLD]["identity"] != first["level"]
    assert [s["type"] for s in second["spawners"]] == VANILLA_SPAWNERS
    assert not {s["identity"] for s in first["spawners"]} & {s["identity"] for s in second["spawners"]}, \
        "a new world gets new spawner instances, with fresh countdowns"
    assert second["wanderingTrader"] == {"spawnDelay": 24000, "spawnChance": 25}, "the trader timer starts over"


def set_insomnia(server, bot) -> None:
    """Edits the player's saved statistics while they are offline, as a server admin could."""
    server.disconnect_bot(bot.name)
    wait_true(lambda: bot.name not in server.rcon("list"), 10, f"{bot.name} to leave")
    path = server.world / "players" / "stats" / f"{bot.uuid}.json"
    stats = json.loads(path.read_text())
    stats.setdefault("stats", {}).setdefault("minecraft:custom", {})["minecraft:time_since_rest"] = INSOMNIA_TICKS
    path.write_text(json.dumps(stats))


def test_phantoms_spawn_over_an_insomniac_player(server, players):
    alice = players[0]
    set_insomnia(server, alice)
    players[0] = alice = server.bot("Alice")
    assert server.player("Alice")["timeSinceRest"] >= INSOMNIA_TICKS
    status = server.status()
    sx, _, sz = status["spawn"]
    # In the open at night, high enough to see the sky; creative, so the phantoms cannot end the run.
    server.rcon("gamemode creative Alice")
    server.rcon("gamemode spectator Bob")
    server.rcon(f"execute in minecraft:overworld run tp Alice {sx + 0.5} 150 {sz + 0.5}")
    server.rcon("gamerule advance_time false")
    server.rcon("time set midnight")
    assert not passes(server, "in minecraft:overworld if entity @e[type=minecraft:phantom]")
    # The spawner tries every 60 to 120 seconds; each try succeeds three times in four on hard.
    server.rcon("tick sprint 24000")
    try:
        wait_true(lambda: passes(server, "at Alice if entity @e[type=minecraft:phantom,distance=..48]"), 120,
                  "phantoms to spawn over Alice", interval=1)
    finally:
        server.rcon("tick sprint stop")
        server.rcon("gamerule advance_time true")
    alice.assert_connected()


def test_a_new_run_resets_insomnia(server, players):
    start_run(server, players, command="soullink reset")
    for bot in players:
        player = server.player(bot.name)
        assert player["timeSinceRest"] < 20 * 60, "statistics start over with the world"
        assert player["gameMode"] == "survival"
    time.sleep(1)
    assert not passes(server, "in minecraft:overworld if entity @e[type=minecraft:phantom]")
