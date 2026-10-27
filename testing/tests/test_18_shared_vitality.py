"""The shared-vitality rule can be changed for future runs without affecting the active run."""

from __future__ import annotations

import json

from helpers import start_run, wait_true


def test_shared_vitality_rule_is_persisted_and_snapshotted(server):
    players = [server.bot("Alice"), server.bot("Bob")]
    active = start_run(server, players)
    assert active["rules"]["sharedVitality"]

    output = server.rcon("soullink rules sharedVitality false")
    assert "next run" in output
    status = server.status()
    assert status["rules"]["sharedVitality"]
    assert not status["nextRules"]["sharedVitality"]
    assert status["shared"]["enabled"] is True
    config = json.loads((server.directory / "config" / "soullink.json").read_text())
    assert config["sharedVitality"] is False

    next_run = start_run(server, players, command="soullink reset")
    assert not next_run["rules"]["sharedVitality"]
    assert next_run["nextRules"]["sharedVitality"] is False
    assert next_run["shared"]["enabled"] is False
    assert "health" not in next_run["shared"]
    server.rcon("damage Bob 4 minecraft:generic")
    wait_true(lambda: server.player("Bob")["health"] == 16.0, 10, "Bob to take his own damage")
    assert server.player("Alice")["health"] == 20.0
    alice_food_before = server.player("Alice")["food"]
    server.rcon("effect give Bob minecraft:hunger 5 120")
    wait_true(lambda: server.player("Bob")["food"] < 20, 20, "Bob to lose his own food")
    assert server.player("Alice")["food"] == alice_food_before

    death_message_start = len(players[0].state.messages)
    server.rcon("kill Alice")
    players[0].respawn()
    players[0].wait_for(lambda state: state.health is not None and state.health > 0, 15, "vanilla respawn in an unlinked run")
    assert any("Alice died. The run continues." in message
               for message in players[0].state.messages[death_message_start:])
    assert server.status()["status"] == "RUNNING"

    server.rcon("soullink rules sharedVitality true")
    server.rcon("soullink rules sharedVitality false")
    server.rcon("soullink rules soulLinkHardcore true")
    rules = server.status()
    assert not rules["rules"]["sharedVitality"]
    assert rules["nextRules"]["sharedVitality"] is False
    assert not rules["rules"]["soulLinkHardcore"]
    assert rules["nextRules"]["soulLinkHardcore"] is True

    hardcore_run = start_run(server, players, command="soullink reset")
    assert not hardcore_run["rules"]["sharedVitality"]
    assert hardcore_run["rules"]["soulLinkHardcore"]
    server.rcon("kill Bob")
    wait_true(lambda: server.status()["status"] == "ENDED", 10, "Soul Link Hardcore to end on the first death")
