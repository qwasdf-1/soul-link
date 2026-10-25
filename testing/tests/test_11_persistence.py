"""A run survives server shutdown, restart and crashes, and continues where it stopped.

Worlds are canonical, so vanilla persists them and every player's own state. Soul Link persists the rest:
run identity and seed, timer, shared pool, run rules, participants, the result of an ended run and the
prepared next run. The run clock only runs while a participant is online.
"""

from __future__ import annotations

import math
import time

import pytest

from helpers import LOBBY, NETHER, OVERWORLD, levels, start_run, wait_true

SOULLINK_CONFIG = {"preloadRadius": 2, "sharedPotions": True}


@pytest.fixture(scope="module", autouse=True)
def rules(server):
    server.rcon("gamerule natural_health_regeneration false")
    server.rcon("gamerule fall_damage false")


def connect(server):
    """Fresh clients for both players, replacing any still connected."""
    for name in ("Alice", "Bob"):
        server.disconnect_bot(name)
    wait_true(lambda: "There are 0" in server.rcon("list"), 10, "the old sessions to close")
    return [server.bot("Alice"), server.bot("Bob")]


def effects(server, name: str) -> dict:
    return server.player(name)["effectDetails"]


def test_graceful_restart_resumes_the_run_where_it_stopped(server):
    players = connect(server)
    start_run(server, players)
    alice, _ = players
    mark = alice.dimension_count()
    server.rcon("execute in minecraft:the_nether run tp Alice 8.5 128 8.5")
    alice.wait_for_dimension(NETHER, timeout=30, after=mark)
    server.rcon("give Alice minecraft:cobblestone 11")
    server.rcon("damage Bob 6 minecraft:generic")
    server.rcon("effect give Bob minecraft:speed 600 1")
    wait_true(lambda: "minecraft:speed" in effects(server, "Alice"), 10, "the shared effect")
    wait_true(lambda: server.status()["elapsedTicks"] > 60, 30, "the timer to run")
    server.wait_prepared()
    before = server.status()
    alice_before = server.player("Alice")

    server.stop()
    server.start()
    after = server.status()
    assert after["status"] == "RUNNING" and after["activeRunId"] == before["activeRunId"]
    assert after["globalSeed"] == before["runSeed"] and after["rules"]["sharedPotions"]
    assert after["elapsedTicks"] >= before["elapsedTicks"]
    assert after["shared"]["health"] == pytest.approx(14.0)
    # The prepared next run is adopted from disk, not generated again.
    assert after["preparation"]["ready"]["runId"] == before["preparation"]["ready"]["runId"]
    # Nobody is online: the run clock waits for its players.
    time.sleep(3)
    assert server.status()["elapsedTicks"] == after["elapsedTicks"]

    alice, bob = connect(server)
    assert alice.state.dimension == NETHER and bob.state.dimension == OVERWORLD
    player = server.player("Alice")
    assert math.dist((player["x"], player["y"], player["z"]),
                     (alice_before["x"], alice_before["y"], alice_before["z"])) < 1.0
    assert player["inventoryItems"] == 11
    for name in ("Alice", "Bob"):
        assert server.player(name)["health"] == pytest.approx(14.0), name
        assert effects(server, name)["minecraft:speed"]["amplifier"] == 1, name
    wait_true(lambda: server.status()["elapsedTicks"] > after["elapsedTicks"] + 20, 20, "the timer to resume")
    # Shared rules still link the two players after the restart.
    server.rcon("damage Alice 2 minecraft:generic")
    wait_true(lambda: server.player("Bob")["health"] == pytest.approx(12.0), 10, "damage to be shared")


def test_crash_resumes_the_run_from_its_last_save(server):
    start_run(server, connect(server), command="reset")
    status = server.status()
    wait_true(lambda: server.status()["elapsedTicks"] > 20 * 12, 60, "the timer to run")
    killed_at = server.status()["elapsedTicks"]
    server.kill()
    server.start()
    after = server.status()
    assert after["status"] == "RUNNING" and after["activeRunId"] == status["activeRunId"]
    # The run state is saved every 5 s, so a crash loses at most that much of the timer.
    assert killed_at - 20 * 6 <= after["elapsedTicks"] <= killed_at
    alice, bob = connect(server)
    for bot in (alice, bob):
        assert bot.state.dimension == OVERWORLD, "participants come back into the run, not the lobby"
        assert server.player(bot.name)["gameMode"] == "survival"
    assert not [line for line in server.suspicious_lines() if "Soul Link" in line]


def test_restart_during_preparation_starts_it_over(server):
    connect(server)
    server.rcon("soullink prepare")
    wait_true(lambda: server.status()["preparation"].get("active"), 30, "a preparation in progress")
    abandoned = server.status()["preparation"]["active"]["runId"]
    server.stop()
    server.start()
    assert not (server.stage_dimensions / abandoned).exists(), "a half-prepared run is discarded at startup"
    ready = server.wait_prepared()
    assert ready["runId"] != abandoned
    assert server.status()["status"] == "RUNNING"
    assert not [k for k, v in levels(server.status()).items() if v["class"] == "STAGE"]


def test_ended_run_stays_ended_across_a_restart(server):
    alice, bob = connect(server)

    def ended():
        # Vanilla ignores damage until a just-joined client reports it has loaded.
        server.rcon("kill Bob")
        return server.status()["status"] == "ENDED"
    wait_true(ended, 20, "the run to end", interval=1)
    server.stop()
    server.start()
    status = server.status()
    assert status["status"] == "ENDED" and status["result"] == "DEFEAT"
    alice, bob = connect(server)
    assert server.player("Alice")["gameMode"] == "spectator"
    # The next run starts normally from the lobby flow.
    start_run(server, [alice, bob])
    for name in ("Alice", "Bob"):
        assert server.player(name)["gameMode"] == "survival"
    assert server.player("Alice")["dimension"] != LOBBY
