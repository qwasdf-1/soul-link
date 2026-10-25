"""A full dragon fight in a run, and a fresh fight in the next run.

The fight is vanilla's: two players in the End, the boss bar, all ten crystals destroyed by a player,
the dragon worn down by player hits over time, the killing blow, the death animation, then the exit
portal, the gateway, the egg and the first-kill experience. Soul Link must declare victory once, at the
killing blow, and stop the run clock there. The next run's End is a new world with a new fight:
full-health dragon, ten crystals, no egg, and first-kill rewards again.

Players stand in barrier cages with full resistance, so the dragon cannot knock them into the void or
end the run by killing them; it still targets, charges and breathes at them as in a real fight.
"""

from __future__ import annotations

import json
import time

import pytest

from helpers import END, block_pos, passes, start_run, wait_true

DRAGON_MAX_HEALTH = 200.0
CRYSTALS = 10
#: Vanilla experience for the first kill of a fight; any later kill of the same fight drops 500.
REPEAT_KILL_EXPERIENCE = 500
#: Cages away from the pillars (radius ~42) and from the podium where the dragon dies, so explosions
#: miss the players and experience orbs are not picked up.
CAGES = {"Alice": (64, 90, 12), "Bob": (64, 90, -12)}


@pytest.fixture(scope="module")
def players(server):
    server.rcon("gamerule natural_health_regeneration false")
    return [server.bot("Alice"), server.bot("Bob")]


def end_fight(server) -> dict:
    return json.loads(server.rcon("soullink debug end"))


def in_end(server, command: str) -> str:
    return server.rcon(f"execute in minecraft:the_end run {command}")


def enter_end_caged(server, bot) -> None:
    x, y, z = block_pos(server.player(bot.name))
    px, pz = x, z - 4
    server.rcon(f"fill {px - 1} {y} {pz - 1} {px + 1} {y + 3} {pz + 1} minecraft:air")
    server.rcon(f"setblock {px} {y} {pz} minecraft:end_portal")
    mark = bot.dimension_count()
    server.rcon(f"tp {bot.name} {px + 0.5} {y} {pz + 0.5}")
    bot.wait_for_dimension(END, timeout=30, after=mark)
    server.rcon(f"effect give {bot.name} minecraft:resistance 1200 4 true")
    cx, cy, cz = CAGES[bot.name]
    in_end(server, f"forceload add {cx} {cz}")
    in_end(server, f"fill {cx - 1} {cy - 1} {cz - 1} {cx + 1} {cy + 2} {cz + 1} minecraft:barrier hollow")
    in_end(server, f"tp {bot.name} {cx + 0.5} {cy} {cz + 0.5}")


def hit_dragon(server, damage: float) -> None:
    in_end(server, f"damage @e[type=minecraft:ender_dragon,limit=1] {damage} minecraft:player_attack by Alice")


def wait_for_dragon(server) -> dict:
    return wait_true(lambda: (f := end_fight(server)).get("dragon") and f["bossBar"]["players"] and f, 60,
                     "the dragon fight to start")


def wait_for_crystals(server) -> None:
    # The fight counts crystals when it scans the arena, every 100 ticks.
    wait_true(lambda: end_fight(server)["aliveCrystals"] == CRYSTALS, 15, "the fight to count every crystal")


def wait_for_death_animation(server) -> dict:
    """After the killing blow the dragon flies to the podium at 1 health, then plays its 200-tick death."""
    return wait_true(lambda: (f := end_fight(server))["dragonKilled"] and f, 90, "the death animation to end")


def kill_with_hits(server, damage: float = 400.0) -> None:
    """Player hits until the killing blow; spaced past vanilla's 10-tick damage cooldown."""
    wait_true(lambda: (hit_dragon(server, damage), end_fight(server).get("dragon", {}).get("dying"))[1]
              or server.status()["status"] == "ENDED", 30, "the killing blow", interval=0.6)


@pytest.fixture(scope="module")
def fight(server, players):
    start_run(server, players)
    for bot in players:
        enter_end_caged(server, bot)
    return wait_for_dragon(server)


def test_fight_starts_with_a_full_dragon_crystals_and_boss_bar(server, players, fight):
    assert fight["dragon"]["health"] == DRAGON_MAX_HEALTH and not fight["dragon"]["dying"]
    wait_for_crystals(server)
    assert not fight["dragonKilled"] and not fight["previouslyKilled"]
    wait_true(lambda: sorted(end_fight(server)["bossBar"]["players"]) == ["Alice", "Bob"], 10,
              "both players on the boss bar")
    for bot in players:
        bot.wait_for(lambda s: any(p == pytest.approx(1.0) for _, p in s.boss_bars.values()), 10,
                     f"{bot.name}'s client to show the dragon's boss bar")


def test_players_destroy_every_crystal(server):
    for _ in range(CRYSTALS):
        in_end(server, "damage @e[type=minecraft:end_crystal,limit=1,sort=random] 1 minecraft:player_attack by Alice")
        time.sleep(0.2)
    wait_true(lambda: end_fight(server)["aliveCrystals"] == 0, 20, "the fight to count no crystals left")
    assert not passes(server, "in minecraft:the_end if entity @e[type=minecraft:end_crystal]")


def test_dragon_is_worn_down_over_time_without_healing(server, players):
    alice = players[0]
    history = [end_fight(server)["dragon"]["health"]]
    # About 11 health per hit on the body (vanilla quarters body damage): roughly 17 hits over 10 s.
    while history[-1] > 30:
        hit_dragon(server, 40)
        time.sleep(0.6)
        history.append(end_fight(server)["dragon"]["health"])
        assert len(history) < 60, f"the dragon is not taking damage: {history}"
    print("dragon health over the fight:", history)
    assert all(later <= earlier for earlier, later in zip(history, history[1:])), \
        f"the dragon healed with no crystals left: {history}"
    assert server.status()["status"] == "RUNNING"
    alice.wait_for(lambda s: any(p < 0.2 for _, p in s.boss_bars.values()), 10,
                   "the client's boss bar to follow the dragon's health")


def test_killing_blow_wins_the_run_once(server, players):
    before = server.status()
    kill_with_hits(server, 100)
    won = server.wait_until(lambda s: s["status"] == "ENDED", 10, "victory at the killing blow")
    assert won["result"] == "VICTORY" and won["victories"] == before["victories"] + 1
    assert passes(server, "if entity @a[name=Alice,advancements={minecraft:end/kill_dragon=true}]")
    # The dragon is still dying; the run clock has already stopped.
    assert "dragon" in end_fight(server)
    wait_for_death_animation(server)
    status = server.status()
    assert status["victories"] == won["victories"], "the end of the animation does not count a second win"
    assert status["elapsedTicks"] == won["elapsedTicks"]
    for bot in players:
        bot.wait_for(lambda s: not s.boss_bars, 10, f"{bot.name}'s boss bar to disappear")
        assert server.player(bot.name)["gameMode"] == "survival"
        bot.assert_connected()


def test_dragon_death_leaves_exit_portal_egg_and_first_kill_experience(server):
    fight = end_fight(server)
    assert "dragon" not in fight
    assert fight["previouslyKilled"] and len(fight["gateways"]) == 19
    ex, ey, ez = fight["exitPortal"]
    assert any(passes(server, f"in minecraft:the_end if block {ex + dx} {ey + dy} {ez} minecraft:end_portal")
               for dx in (1, 2) for dy in range(-1, 3)), "the exit portal was not activated"
    assert "dragonEggY" in fight, "the first kill of a fight places the egg"
    assert fight["experienceOrbValue"] > REPEAT_KILL_EXPERIENCE, fight


def test_next_run_has_a_fresh_dragon_fight(server, players):
    start_run(server, players)
    for bot in players:
        enter_end_caged(server, bot)
    fight = wait_for_dragon(server)
    assert fight["dragon"]["health"] == DRAGON_MAX_HEALTH
    wait_for_crystals(server)
    assert not fight["dragonKilled"] and not fight["previouslyKilled"]
    assert len(fight["gateways"]) == 20 and "dragonEggY" not in fight
    assert fight["experienceOrbs"] == 0

    victories = server.status()["victories"]
    kill_with_hits(server)
    status = server.wait_until(lambda s: s["status"] == "ENDED", 10, "victory in the second run")
    assert status["result"] == "VICTORY" and status["victories"] == victories + 1
    fight = wait_for_death_animation(server)
    assert "dragonEggY" in fight, "a new run's fight is a first kill again"
    assert fight["experienceOrbValue"] > REPEAT_KILL_EXPERIENCE, fight
