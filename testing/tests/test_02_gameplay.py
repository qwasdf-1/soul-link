"""Soul Link gameplay on canonical dimensions.

Portals and dimension advancements are exercised through plain vanilla mechanics: because the run levels
really are minecraft:overworld / the_nether / the_end, no portal or advancement shims are involved.
"""

from __future__ import annotations

import time

import pytest

from helpers import END, NETHER, OVERWORLD, block_pos, passes, start_run, wait_true


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


@pytest.fixture(scope="module")
def running(server, players):
    # Game rules are operator configuration and survive resets; disabling regeneration makes the
    # shared-health arithmetic exact.
    server.rcon("gamerule natural_health_regeneration false")
    return start_run(server, players)


def health(server, name: str) -> float:
    return server.player(name)["health"]


def test_damage_to_one_player_is_shared(server, players, running):
    alice, bob = players
    server.rcon("damage Alice 6 minecraft:generic")
    wait_true(lambda: abs(health(server, "Bob") - 14.0) < 0.01, 10, "Bob to share Alice's damage")
    assert abs(health(server, "Alice") - 14.0) < 0.01
    bob.wait_for(lambda s: s.health is not None and abs(s.health - 14.0) < 0.01, 10, "receive the shared health")
    status = server.status()
    assert abs(status["shared"]["health"] - 14.0) < 0.01


def test_healing_is_shared_once(server, players, running):
    server.rcon("effect give Bob minecraft:instant_health 1 0")
    wait_true(lambda: abs(health(server, "Alice") - 18.0) < 0.01, 10, "Alice to share Bob's healing")
    assert abs(health(server, "Bob") - 18.0) < 0.01


def test_hunger_is_shared(server, players, running):
    server.rcon("effect give Alice minecraft:hunger 5 120")
    wait_true(lambda: server.player("Bob")["food"] < 20, 20, "Bob to share Alice's hunger")
    server.rcon("effect clear Alice")
    wait_true(lambda: server.player("Alice")["food"] == server.player("Bob")["food"], 10, "food to converge")


def test_nether_portal_uses_canonical_nether(server, players, running):
    alice = players[0]
    x, y, z = block_pos(server.player("Alice"))
    px, py, pz = x + 4, y + 1, z
    # Test scaffolding: an obsidian frame (bots cannot build). Lighting it with fire lets vanilla's own
    # PortalShape logic create the portal, as flint and steel would.
    server.rcon(f"fill {px - 1} {py - 1} {pz - 1} {px + 2} {py + 3} {pz + 1} minecraft:air")
    server.rcon(f"fill {px - 1} {py - 1} {pz} {px + 2} {py + 3} {pz} minecraft:obsidian")
    server.rcon(f"fill {px} {py} {pz} {px + 1} {py + 2} {pz} minecraft:air")
    server.rcon(f"setblock {px} {py} {pz} minecraft:fire")
    assert passes(server, f"if block {px + 1} {py + 2} {pz} minecraft:nether_portal"), "vanilla did not light the portal"
    mark = alice.dimension_count()
    server.rcon(f"tp Alice {px + 0.5} {py} {pz + 0.5}")
    alice.wait_for_dimension(NETHER, timeout=40, after=mark)
    alice.assert_connected()
    player = server.player("Alice")
    assert player["dimension"] == NETHER
    # Vanilla coordinate scaling (8:1) applies because the source really is the Overworld.
    assert abs(player["x"] - px / 8) < 16 and abs(player["z"] - pz / 8) < 16
    assert passes(server, "if entity @a[name=Alice,advancements={minecraft:story/enter_the_nether=true}]")
    # She arrives inside the portal that vanilla's PortalForcer built in the canonical Nether.
    assert passes(server, "as Alice at @s if block ~ ~ ~ minecraft:nether_portal")
    server.rcon(f"execute in minecraft:overworld run tp Alice {x + 0.5} {y} {z + 0.5}")
    alice.wait_for_dimension(OVERWORLD, timeout=20, after=mark + 1)
    server.rcon(f"fill {px - 1} {py - 1} {pz} {px + 2} {py + 3} {pz} minecraft:air")


def test_end_portal_dragon_kill_is_victory(server, players, running):
    alice, bob = players
    x, y, z = block_pos(server.player("Alice"))
    ex, ez = x, z - 6
    server.rcon(f"fill {ex - 1} {y} {ez - 1} {ex + 1} {y + 3} {ez + 1} minecraft:air")
    server.rcon(f"setblock {ex} {y} {ez} minecraft:end_portal")
    # Vanilla refreshes an active portal cooldown while an entity stays inside a portal; let the cooldown
    # from the Nether trip expire before stepping in, as a real player would.
    time.sleep(2)
    mark = alice.dimension_count()
    server.rcon(f"tp Alice {ex + 0.5} {y} {ez + 0.5}")
    alice.wait_for_dimension(END, timeout=30, after=mark)
    assert passes(server, "if entity @a[name=Alice,advancements={minecraft:story/enter_the_end=true}]")
    # Vanilla's own fight initialization summons the dragon once a player is in the arena.
    wait_true(lambda: passes(server, "in minecraft:the_end if entity @e[type=minecraft:ender_dragon]"), 60,
              "the vanilla dragon fight to start")
    server.rcon("execute in minecraft:the_end run kill @e[type=minecraft:ender_dragon]")
    status = server.wait_until(lambda s: s["status"] == "ENDED", 20, "the run to end")
    assert status["result"] == "VICTORY"
    for bot in players:
        bot.wait_for_message("dragon is dead", timeout=10)
        # Winners keep playing (exit portal, credits); only a defeat turns participants into spectators.
        assert server.player(bot.name)["gameMode"] == "survival"
        bot.assert_connected()


def test_any_death_ends_the_run_for_everyone(server, players):
    alice, bob = players
    start_run(server, players)
    server.rcon("kill Bob")
    status = server.wait_until(lambda s: s["status"] == "ENDED", 10, "the run to fail")
    assert status["result"] == "DEFEAT"
    assert "Bob died" in status["endReason"]
    alice.wait_for_message("Bob died", timeout=10)
    assert server.player("Alice")["gameMode"] == "spectator"
    bob.wait_for(lambda s: s.health is not None and s.health <= 0, 10, "see the death screen")

    # The next run picks everyone up, including the dead player still on the death screen.
    start_run(server, players)
    for bot in players:
        player = server.player(bot.name)
        assert player["alive"] and player["gameMode"] == "survival" and player["health"] == 20.0
        bot.assert_connected()


def test_status_command_for_players(server, players):
    output = server.rcon("runinfo")
    assert "Status: RUNNING" in output
    players[0].command("runinfo")
    players[0].wait_for_message("Status: RUNNING", timeout=10)
