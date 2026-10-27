"""Canonical world lifecycle: lobby, staging, activation and repeated resets.

Two headless clients stay connected for the whole module, so every assertion also proves that
successful resets never disconnect or re-login a player.
"""

from __future__ import annotations

import pytest

from helpers import END, LOBBY, NETHER, OVERWORLD, levels, near_spawn, start_run

RESETTABLE = (OVERWORLD, NETHER, END)


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def test_first_run_starts_automatically_and_lobby_persists(server, players):
    status = server.wait_until(lambda s: s["status"] == "RUNNING" and s["runsStarted"] >= 1, 240,
                               "the first run to start automatically")
    by_key = levels(status)
    assert by_key[LOBBY]["class"] == "PERSISTENT_SERVICE"
    for key in RESETTABLE:
        assert by_key[key]["class"] == "RESETTABLE_CANONICAL"
        assert by_key[key]["dimension"] == key
    for bot in players:
        assert bot.state.dimension == OVERWORLD
        player = server.player(bot.name)
        assert player["dimension"] == OVERWORLD
        assert player["gameMode"] == "survival"
    assert status["activeRunStarted"]


def test_preparation_is_isolated_from_canonical_levels(server, players):
    before_status = server.status()
    before = levels(before_status)
    ready = server.wait_prepared()
    status = server.status()
    after = levels(status)

    assert ready["state"] == "CLOSED_READY"
    assert [d["target"] for d in ready["dimensions"]] == list(RESETTABLE)
    # Staged levels are fully closed and unregistered once the run is ready.
    assert not [key for key, level in after.items() if level["class"] == "STAGE"]
    # Preparation never touched the live canonical levels or the global seed.
    for key in RESETTABLE + (LOBBY,):
        assert after[key]["identity"] == before[key]["identity"]
    assert status["globalSeed"] == before_status["globalSeed"]
    assert ready["seed"] != status["globalSeed"]
    for dimension in ready["dimensions"]:
        namespace, path = dimension["stage"].split(":", 1)
        stage_dir = server.world / "dimensions" / namespace / path
        assert stage_dir.is_dir(), stage_dir
        assert (stage_dir / "data").is_dir()
    stage_overworld = server.world / "dimensions" / "terrarasa" / ready["dimensions"][0]["stage"].split(":", 1)[1]
    assert any((stage_overworld / "region").glob("*.mca")), "staged Overworld chunks were not written"
    assert (server.library_data / "stages" / ready["runId"] / "manifest.json").is_file()


def test_activation_replaces_canonical_levels_without_reconnecting(server, players):
    before = levels(server.status())
    status = start_run(server, players, command="soullink reset")
    after = levels(status)
    seed = status["runSeed"]

    for key in RESETTABLE:
        level = after[key]
        assert level["identity"] != before[key]["identity"], f"{key} was not replaced"
        assert level["dimension"] == key, "true server-side identity"
        assert level["seed"] == seed
        assert "seedOverride" not in level, "canonical levels must use the global seed"
    assert after[LOBBY]["identity"] == before[LOBBY]["identity"], "lobby object survives"
    assert status["overworldSeed"] == status["globalSeed"] == seed
    assert f"[{seed}]" in server.rcon("seed"), "vanilla /seed agrees with the run seed"
    assert status["journal"]["phase"] == "COMMITTED"

    spawn = status["spawn"]
    for bot in players:
        bot.assert_connected()
        assert bot.state.play_entered == 1, "no re-login"
        assert bot.state.reconfigurations == 0
        assert [c.dimension for c in bot.state.dimensions[-2:]] == [LOBBY, OVERWORLD]
        player = server.player(bot.name)
        assert player["dimension"] == OVERWORLD
        assert player["gameMode"] == "survival"
        assert near_spawn(player, spawn)

    timings = status["activation"]["lastTimingsMillis"]
    assert {"retire", "promote", "reconstruct", "validate", "total"} <= timings.keys()
    print(f"activation timings (ms): {timings}")


def test_old_data_is_cleaned_up_after_commit(server, players):
    status = server.status()
    run_id = status["activeRunId"]
    stage_root = server.stage_dimensions / run_id
    quarantine = server.library_data / "quarantine" / f"replaced-by-{run_id}"
    manifest = server.library_data / "stages" / run_id

    def cleaned():
        return not stage_root.exists() and not quarantine.exists() and not manifest.exists()

    from helpers import wait_true
    wait_true(cleaned, 30, "post-commit cleanup")
    for key in RESETTABLE:
        namespace, path = key.split(":")
        # Promoted data (never-visited dimensions only carry data/, e.g. the dragon fight).
        assert (server.world / "dimensions" / namespace / path / "data").is_dir()
    assert any((server.world / "dimensions" / "minecraft" / "overworld" / "region").glob("*.mca"))


def test_repeated_resets_use_fresh_seeds_without_leaking_levels(server, players):
    seeds: set[int] = set()
    identities: set[str] = set()
    totals: list[int] = []
    for _ in range(10):
        status = start_run(server, players, command="soullink reset")
        seeds.add(status["runSeed"])
        identities.add(levels(status)[OVERWORLD]["identity"])
        totals.append(status["activation"]["lastTimingsMillis"]["total"])
        for bot in players:
            bot.assert_connected()
    assert len(seeds) == 10, "every run gets an independent seed"
    assert len(identities) == 10
    for bot in players:
        assert bot.state.play_entered == 1
        assert bot.state.disconnected is False
    gc = server.rcon_json("soullink debug gc")
    # Every retired level must become unreachable; allow the most recent generation to linger briefly.
    assert gc["retiredLevelsReachable"] <= len(RESETTABLE), gc
    print(f"activation totals (ms): {totals}; heap after GC: {gc['usedHeapMiB']} MiB")
