"""Failure containment and crash recovery.

In-process failures are injected at every activation phase and must roll back to the previous run with
players still connected. Crashes are real: the JVM halts mid-protocol and the next start must reconcile
the canonical directories and the seed from the journal.
"""

from __future__ import annotations

import json

import pytest

from helpers import LOBBY, OVERWORLD, levels, near_spawn, start_run

# Exit status of a simulated crash (FaultInjection.HALT_STATUS).
HALT_STATUS = 86

# Injected failures are logged at ERROR level on purpose.
ALLOWED_LOG = ("Injected failure", "Canonical activation failed", "Run preparation", "Fault injection")


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def journal(server) -> dict:
    return json.loads((server.library_data / "active-journal.json").read_text())


def canonical_dirs_present(server) -> bool:
    return all((server.world / "dimensions" / "minecraft" / name / "data").is_dir()
               for name in ("overworld", "the_nether", "the_end"))


@pytest.mark.parametrize("point", ["retire", "promote", "reconstruct", "validate"])
def test_injected_failure_rolls_back_in_process(server, players, point):
    if server.status()["status"] != "RUNNING":
        start_run(server, players)
    before = server.status()
    previous_seed = before["globalSeed"]
    ready = server.wait_prepared()
    marks = [bot.dimension_count() for bot in players]

    server.rcon(f"soullink debug fail {point}")
    output = server.rcon("reset")
    assert "rolled back" in output, output

    status = server.status()
    assert status["journal"]["phase"] == "ROLLED_BACK"
    assert status["globalSeed"] == previous_seed and status["overworldSeed"] == previous_seed
    assert status["activeRunId"] == before["activeRunId"]
    assert status["status"] == "LOBBY"
    by_key = levels(status)
    for key in ("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"):
        assert by_key[key]["dimension"] == key and by_key[key]["seed"] == previous_seed
    assert not [k for k, v in by_key.items() if v["class"] == "STAGE"]
    # The prepared run is intact and still adoptable.
    assert status["preparation"]["ready"]["runId"] == ready["runId"]
    assert canonical_dirs_present(server)
    assert not (server.library_data / "quarantine" / f"replaced-by-{ready['runId']}").exists()
    for bot, mark in zip(players, marks):
        bot.assert_connected()
        assert bot.state.play_entered == 1
        assert LOBBY in [c.dimension for c in bot.state.dimensions[mark:]] or bot.state.dimension == LOBBY

    # The same prepared run activates cleanly afterwards.
    status = start_run(server, players)
    assert status["activeRunId"] == ready["runId"]


def crash_during_activation(server, point: str) -> tuple[dict, dict]:
    server.start()
    first = start_run(server, [])
    ready = server.wait_prepared()
    server.rcon(f"soullink debug fail {point}")
    try:
        server.rcon("reset")
    except Exception:  # noqa: BLE001 - the JVM halts while the command runs
        pass
    assert server.wait_exit(60) == HALT_STATUS, "the server should have halted at the fault point"
    return first, ready


def test_crash_mid_promotion_rolls_back_at_startup(fresh_server):
    first, ready = crash_during_activation(fresh_server, "halt-after-first-move")
    assert journal(fresh_server)["phase"] == "PROMOTING"

    fresh_server.start()
    status = fresh_server.status()
    assert status["journal"]["phase"] == "ROLLED_BACK"
    assert status["globalSeed"] == first["runSeed"]
    assert levels(status)[OVERWORLD]["seed"] == first["runSeed"]
    assert status["activeRunId"] == first["activeRunId"]
    assert canonical_dirs_present(fresh_server)
    # Rollback restored the staged directory, so the prepared run is adopted again.
    assert status["preparation"]["ready"]["runId"] == ready["runId"]
    status = start_run(fresh_server, [])
    assert status["activeRunId"] == ready["runId"]
    assert not fresh_server.suspicious_lines(allow=ALLOWED_LOG)


def test_crash_after_promotion_rolls_forward_at_startup(fresh_server):
    first, ready = crash_during_activation(fresh_server, "halt-after-promote")
    assert journal(fresh_server)["phase"] == "PROMOTED"

    fresh_server.start()
    status = fresh_server.status()
    assert status["journal"]["phase"] == "COMMITTED"
    assert status["globalSeed"] == ready["seed"], "the journal re-asserts the promoted run's seed"
    assert levels(status)[OVERWORLD]["seed"] == ready["seed"]
    # The committed run was never entered; /start enters it without another activation.
    assert status["activeRunId"] == ready["runId"] and not status["activeRunStarted"]
    alice = fresh_server.bot("Alice")
    assert alice.state.dimension == LOBBY
    fresh_server.rcon("start")
    alice.wait_for_dimension(OVERWORLD, timeout=30)
    status = fresh_server.status()
    assert status["status"] == "RUNNING" and status["activation"]["activations"] == 0
    player = fresh_server.player("Alice")
    spawn = status["spawn"]
    assert near_spawn(player, spawn)
    assert not fresh_server.suspicious_lines(allow=ALLOWED_LOG)


def test_crash_after_commit_adopts_the_committed_run(fresh_server):
    first, ready = crash_during_activation(fresh_server, "halt-after-commit")
    assert journal(fresh_server)["phase"] == "COMMITTED"

    fresh_server.start()
    status = fresh_server.status()
    assert status["globalSeed"] == ready["seed"]
    assert status["activeRunId"] == ready["runId"] and not status["activeRunStarted"]
    assert not (fresh_server.library_data / "quarantine" / f"replaced-by-{ready['runId']}").exists()
    assert not fresh_server.suspicious_lines(allow=ALLOWED_LOG)


def test_graceful_restart_resumes_the_running_run(fresh_server):
    fresh_server.start()
    alice = fresh_server.bot("Alice")
    status = start_run(fresh_server, [alice])
    fresh_server.rcon("give Alice minecraft:cobblestone 7")
    fresh_server.wait_until(lambda s: s["elapsedTicks"] > 40, 30, "the timer to advance")

    fresh_server.restart()
    after = fresh_server.status()
    assert after["status"] == "RUNNING"
    assert after["activeRunId"] == status["activeRunId"]
    assert after["globalSeed"] == status["runSeed"]
    assert after["elapsedTicks"] > 40, "the timer resumes instead of restarting"
    alice = fresh_server.bot("Alice")
    assert alice.state.dimension == OVERWORLD
    assert fresh_server.player("Alice")["inventoryItems"] == 7
    assert not fresh_server.suspicious_lines()
