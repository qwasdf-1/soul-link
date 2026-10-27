"""Shared potions: effect changes on one participant apply to every participant (a chaos option in Soul Link Speedrun).

The rule is off by default and, like Soul Link Speedrun's chaos settings, a change applies from the next run.
"""

from __future__ import annotations

import json

import pytest

from helpers import start_run, wait_true


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def effects(server, name: str) -> dict[str, dict]:
    return server.player(name)["effectDetails"]


def test_effects_are_private_by_default(server, players):
    server.rcon("gamerule natural_health_regeneration false")
    start_run(server, players)
    server.rcon("effect give Alice minecraft:speed 60 0")
    wait_true(lambda: "minecraft:speed" in effects(server, "Alice"), 10, "Alice's effect")
    assert "minecraft:speed" not in effects(server, "Bob")


def test_rule_change_applies_from_the_next_run(server, players):
    output = server.rcon("soullink rules sharedPotions true")
    assert "next run" in output
    status = server.status()
    assert status["nextRules"]["sharedPotions"] and not status["rules"]["sharedPotions"]
    config = json.loads((server.directory / "config" / "soullink.json").read_text())
    assert config["sharedPotions"] is True, "the rule is persisted to the operator config"
    server.rcon("effect give Alice minecraft:haste 60 0")
    assert "minecraft:haste" not in effects(server, "Bob"), "the running run keeps its rules"

    status = start_run(server, players, command="soullink reset")
    assert status["rules"]["sharedPotions"]


def test_gained_effects_are_shared_with_server_and_client(server, players):
    alice, bob = players
    server.rcon("effect give Alice minecraft:speed 60 1")
    wait_true(lambda: effects(server, "Bob").get("minecraft:speed", {}).get("amplifier") == 1, 10,
              "Bob to share Alice's speed")
    bob.wait_for(lambda s: len(s.effects) == 1, 10, "Bob's client to show the effect")
    # Each client hears about the effect on its own connection, in no particular order.
    alice.wait_for(lambda s: s.effects == bob.state.effects, 10, "both clients to show the same effects")


def test_upgrades_follow_vanilla_merge_rules(server, players):
    server.rcon("effect give Bob minecraft:speed 60 3")
    wait_true(lambda: effects(server, "Alice")["minecraft:speed"]["amplifier"] == 3, 10, "the upgrade to be shared")
    # A weaker effect does not replace a stronger one, for anyone.
    server.rcon("effect give Alice minecraft:speed 60 0")
    assert effects(server, "Alice")["minecraft:speed"]["amplifier"] == 3
    assert effects(server, "Bob")["minecraft:speed"]["amplifier"] == 3


def test_removal_is_shared(server, players):
    _, bob = players
    server.rcon("effect clear Alice minecraft:speed")
    wait_true(lambda: "minecraft:speed" not in effects(server, "Bob"), 10, "Bob to lose the speed")
    bob.wait_for(lambda s: not s.effects, 10, "Bob's client to drop the effect")


def test_negative_and_start_hook_effects_are_shared(server, players):
    server.rcon("effect give Bob minecraft:absorption 30 1")
    # Absorption grants its hearts in vanilla's start hook, which teammates run as well.
    wait_true(lambda: server.player("Alice")["absorption"] > 0, 10, "Alice to gain absorption hearts")
    server.rcon("effect give Alice minecraft:poison 5 0")
    wait_true(lambda: "minecraft:poison" in effects(server, "Bob"), 10, "Bob to be poisoned too")
    server.rcon("effect clear Alice")
    wait_true(lambda: not effects(server, "Bob"), 10, "the clear to be shared")


def test_late_joiner_receives_the_team_effects(server, players):
    server.rcon("effect give Alice minecraft:night_vision 120 0")
    wait_true(lambda: "minecraft:night_vision" in effects(server, "Bob"), 10, "the team effect")
    carol = server.bot("Carol")
    wait_true(lambda: "minecraft:night_vision" in effects(server, "Carol"), 20, "Carol to receive the team effect")
    carol.wait_for(lambda s: len(s.effects) == 1, 10, "Carol's client to show it")
    server.disconnect_bot("Carol")


def test_lobby_players_are_never_linked(server, players):
    server.rcon("soullink stop")
    server.wait_until(lambda s: s["status"] == "LOBBY", 10, "the lobby")
    server.rcon("effect give Alice minecraft:speed 60 0")
    assert "minecraft:speed" not in effects(server, "Bob")
