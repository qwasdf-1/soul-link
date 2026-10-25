"""The End after the dragon on canonical run dimensions: gateways, exit portal and credits.

Everything here is vanilla behaviour that a dimension-copy approach would have to re-implement: the
gateway order is shuffled from the world seed when the End is constructed, the gateway exit is generated
on first use, the exit portal rolls the credits once per player, and the credits return the player to the
Overworld's world spawn. Soul Link only has to supply a correct seed, canonical keys and a fresh
``seenCredits`` per run.
"""

from __future__ import annotations

import json
import math
import time

import pytest

from helpers import END, OVERWORLD, SoulLinkServer, block_pos, passes, start_run, wait_true

RUN_SEED = 987654321


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


@pytest.fixture(scope="module")
def victorious(server, players):
    """A run with a known seed whose dragon Alice has killed, with both players still in it."""
    alice, _ = players
    server.rcon("gamerule natural_health_regeneration false")
    server.rcon(f"soullink prepare {RUN_SEED} 1")
    server.wait_until(lambda s: (s["preparation"].get("ready") or {}).get("seed") == RUN_SEED, 240,
                      "the seeded run to be prepared")
    status = start_run(server, players)
    assert status["runSeed"] == RUN_SEED
    fight_before = end_fight(server)

    x, y, z = block_pos(server.player("Alice"))
    ex, ez = x, z - 6
    server.rcon(f"fill {ex - 1} {y} {ez - 1} {ex + 1} {y + 3} {ez + 1} minecraft:air")
    server.rcon(f"setblock {ex} {y} {ez} minecraft:end_portal")
    mark = alice.dimension_count()
    server.rcon(f"tp Alice {ex + 0.5} {y} {ez + 0.5}")
    alice.wait_for_dimension(END, timeout=30, after=mark)
    # The dragon must not decide the test before the gateway and portal steps run.
    server.rcon("effect give Alice minecraft:resistance 600 4 true")
    wait_true(lambda: passes(server, "in minecraft:the_end if entity @e[type=minecraft:ender_dragon]"), 60,
              "the vanilla dragon fight to start")
    server.rcon("execute in minecraft:the_end run kill @e[type=minecraft:ender_dragon]")
    status = server.wait_until(lambda s: s["status"] == "ENDED", 20, "the run to end")
    assert status["result"] == "VICTORY"
    fight = wait_true(lambda: (f := end_fight(server))["dragonKilled"] and f, 20, "the fight to record the kill")
    return {"before": fight_before, "after": fight}


def end_fight(server) -> dict:
    return json.loads(server.rcon("soullink debug end"))


def gateway_position(index: int) -> tuple[int, int, int]:
    """EnderDragonFight.spawnNewGateway: gateways sit on a radius-96 ring at y=75."""
    angle = 2.0 * (-math.pi + (math.pi / 20) * index)
    return math.floor(96.0 * math.cos(angle)), 75, math.floor(96.0 * math.sin(angle))


@pytest.mark.usefixtures("victorious")
def test_victory_keeps_players_in_the_game(server, players):
    for bot in players:
        assert server.player(bot.name)["gameMode"] == "survival", "a won run is still playable"
        bot.assert_connected()


def test_dragon_death_spawns_the_seeded_gateway_and_exit_portal(server, victorious):
    before, after = victorious["before"], victorious["after"]
    assert len(before["gateways"]) == 20
    # Vanilla spawns the last gateway of the seed-shuffled list.
    assert after["gateways"] == before["gateways"][:-1]
    gx, gy, gz = gateway_position(before["gateways"][-1])
    assert passes(server, f"in minecraft:the_end if block {gx} {gy} {gz} minecraft:end_gateway")
    ex, ey, ez = after["exitPortal"]
    assert any(passes(server, f"in minecraft:the_end if block {ex + dx} {ey + dy} {ez} minecraft:end_portal")
               for dx in (1, 2) for dy in range(-1, 3)), "the exit portal was not activated"


def test_end_gateway_teleports_to_the_outer_islands(server, players, victorious):
    alice = players[0]
    gx, gy, gz = gateway_position(victorious["before"]["gateways"][-1])
    teleports = alice.state.teleports
    server.rcon(f"execute in minecraft:the_end run tp Alice {gx + 0.5} {gy} {gz + 0.5}")
    # The first use of a gateway generates its exit near the outer islands, about 1000 blocks out.
    wait_true(lambda: math.hypot(server.player("Alice")["x"], server.player("Alice")["z"]) > 700, 60,
              "the gateway to send Alice to the outer islands")
    player = server.player("Alice")
    assert player["dimension"] == END
    assert alice.state.teleports > teleports + 1, "the client followed the gateway teleport"
    assert passes(server, "if entity @a[name=Alice,advancements={minecraft:end/enter_end_gateway=true}]")
    alice.assert_connected()


def test_exit_portal_rolls_credits_and_returns_to_the_run_spawn(server, players, victorious):
    alice = players[0]
    status = server.status()
    ex, ey, ez = victorious["after"]["exitPortal"]
    portal = next((ex + dx, ey + dy, ez) for dx in (1, 2) for dy in range(-1, 3)
                  if passes(server, f"in minecraft:the_end if block {ex + dx} {ey + dy} {ez} minecraft:end_portal"))
    assert not server.player("Alice")["seenCredits"]
    # Portal cooldown from the gateway trip must lapse first, as for a real player.
    time.sleep(2)
    mark = alice.dimension_count()
    px, py, pz = portal
    server.rcon(f"execute in minecraft:the_end run tp Alice {px + 0.5} {py} {pz + 0.5}")
    alice.wait_for(lambda s: s.credits == 1, 30, "the end credits")
    # The bot closes the credits like a vanilla client; vanilla then respawns her in the Overworld.
    alice.wait_for_dimension(OVERWORLD, timeout=30, after=mark)
    player = server.player("Alice")
    assert player["dimension"] == OVERWORLD and player["alive"] and player["gameMode"] == "survival"
    assert player["seenCredits"]
    sx, _, sz = status["spawn"]
    assert math.hypot(player["x"] - sx, player["z"] - sz) < 32, "credits return to the run's world spawn"
    alice.assert_connected()
    assert alice.state.play_entered == 1, "the credits respawn is not a reconnect"


@pytest.mark.usefixtures("victorious")
def test_next_run_shows_the_credits_again(server, players):
    start_run(server, players)
    for bot in players:
        assert not server.player(bot.name)["seenCredits"], "a fresh world has never shown its credits"


def test_staged_end_shuffles_gateways_like_vanilla(env, victorious):
    """Control: a server whose initial worlds use the run seed builds the same fight from scratch."""
    install, java, packets = env
    control = SoulLinkServer(install, "test_07_end-control", java, packets, {"preloadRadius": 1}, RUN_SEED)
    control.start()
    try:
        assert end_fight(control)["gateways"] == victorious["before"]["gateways"]
        assert not control.suspicious_lines()
    finally:
        control.stop()
