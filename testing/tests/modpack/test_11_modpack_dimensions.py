"""Extra dimensions under the dimension policy.

Deeper Dark adds a data-pack dimension with its own function-driven portal; the operator opts it into
the run, so it is staged with the run seed and replaced with the vanilla trio. Multiworld creates a
dimension at runtime that the policy does not name: it is EXTERNAL and must survive every reset untouched,
like the persistent lobby.
"""

from __future__ import annotations

import pytest

from helpers import LOBBY, OVERWORLD, levels, start_run, wait_true

pytestmark = pytest.mark.modpack

DEEPER_DARK = "deeper_dark:deeper_dark"
ARENA = "soullinktest:arena"
SOULLINK_CONFIG = {
    "preloadRadius": 1,
    "resettableDimensions": ["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", DEEPER_DARK],
}


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice")]


@pytest.fixture(scope="module")
def arena(server, players):
    # Multiworld only acts on commands from players.
    server.rcon("op Alice")
    players[0].command(f"mw create {ARENA} NORMAL")
    wait_true(lambda: ARENA in levels(server.status()), 60, f"Multiworld to create {ARENA}")
    server.rcon(f"execute in {ARENA} run forceload add 0 0")
    wait_true(lambda: "passed" in server.rcon(f"execute in {ARENA} if loaded 0 0 0"), 60, "the arena chunk to load")
    server.rcon(f"execute in {ARENA} run setblock 0 200 0 minecraft:gold_block")
    return ARENA


def test_policy_classifies_every_dimension(server, arena):
    by_key = levels(server.status())
    assert by_key[DEEPER_DARK]["class"] == "RESETTABLE_CANONICAL"
    assert by_key[LOBBY]["class"] == "PERSISTENT_SERVICE"
    assert by_key[arena]["class"] == "EXTERNAL"


def test_opted_in_dimension_is_replaced_with_the_run_seed(server, players, arena):
    before = levels(server.status())
    status = start_run(server, players, timeout=400)
    after = levels(status)
    replaced = after[DEEPER_DARK]
    assert replaced["identity"] != before[DEEPER_DARK]["identity"], "a new level object under the same key"
    assert replaced["dimension"] == DEEPER_DARK and replaced["seed"] == status["runSeed"]
    assert "seedOverride" not in replaced, "canonical levels carry no staging seed"
    probe = server.rcon_json(f"soullink debug probe {DEEPER_DARK} 0 0")
    assert probe["levelSeed"] == status["runSeed"]
    assert all(p["biome"].startswith("deeper_dark:") for p in probe["samples"]), probe["samples"]
    # Untouched: the persistent lobby and the runtime dimension keep their objects and seeds.
    for key in (LOBBY, arena):
        assert after[key]["identity"] == before[key]["identity"], key
        assert after[key]["seed"] == before[key]["seed"], key


def test_external_dimension_data_survives_resets(server, players, arena):
    arena_identity = levels(server.status())[arena]["identity"]
    start_run(server, players, command="reset", timeout=400)
    assert levels(server.status())[arena]["identity"] == arena_identity
    assert "passed" in server.rcon(f"execute in {arena} if block 0 200 0 minecraft:gold_block")


def test_data_pack_state_is_rebuilt_after_a_reset(server, players):
    """The session reset clears objectives and storage; re-running #minecraft:load sets the pack up again."""
    objectives = server.rcon("scoreboard objectives list")
    assert "deeper_dark.datafixers" in objectives, objectives[:300]
    assert "[in." in objectives, "Incendium's load function ran as well"


def test_data_pack_portal_enters_the_replaced_dimension(server, players):
    alice = players[0]
    server.rcon("item replace entity Alice weapon.mainhand with minecraft:echo_shard")
    server.rcon("execute at Alice run setblock ~ ~-1 ~ minecraft:reinforced_deepslate")
    mark = alice.dimension_count()
    # Deeper Dark's tick function sends a player holding an echo shard on reinforced deepslate inside.
    alice.wait_for_dimension(DEEPER_DARK, timeout=60, after=mark)
    assert server.player("Alice")["dimension"] == DEEPER_DARK
    status = server.status()
    assert levels(status)[DEEPER_DARK]["players"] == 1
    # The run's shared rules apply there: it is a run dimension. Vanilla ignores damage for a moment after
    # a dimension change, so keep hitting until it lands.
    def damaged():
        server.rcon("damage Alice 2 minecraft:generic")
        return server.status()["shared"]["health"] < 20
    wait_true(damaged, 15, "shared health to see the damage", interval=1)
    server.rcon(f"execute in {OVERWORLD} run tp Alice {status['spawn'][0]} {status['spawn'][1]} {status['spawn'][2]}")
    alice.wait_for_dimension(OVERWORLD, timeout=30, after=mark + 1)
