"""Seed coherence and staged-data portability."""

from __future__ import annotations

import json
import time

from helpers import LOBBY, OVERWORLD, levels, start_run, wait_preparation_idle, wait_true

LEVEL_SEED = 4242
SEED = 918273645


def test_prepared_spawn_uses_the_requested_seed(server):
    """A spawn selected during preparation belongs to the requested seed after activation."""
    server.wait_until(lambda status: status["status"] == "RUNNING", 240,
                      "the automatically started first run")
    wait_preparation_idle(server)
    server.rcon(f"soullink prepare {LEVEL_SEED} 1")
    ready = server.wait_until(lambda s: s["preparation"].get("ready", {}).get("seed") == LEVEL_SEED, 240,
                              "the seeded run")["preparation"]["ready"]
    assert ready["seed"] == LEVEL_SEED
    assert all(isinstance(ready["spawn"][axis], int) for axis in ("x", "y", "z"))
    status = start_run(server, [], command="soullink reset")
    assert status["runSeed"] == LEVEL_SEED
    x, _, z = status["spawn"]
    probe = server.rcon_json(f"soullink debug probe minecraft:overworld {x} {z}")
    assert probe["levelSeed"] == LEVEL_SEED


def run_with_seed(server, preload: int, command: str) -> None:
    server.wait_until(lambda status: status["status"] == "RUNNING", 240,
                      "the automatically started first run")
    wait_preparation_idle(server)
    server.rcon(f"soullink prepare {SEED} {preload}")
    server.wait_until(lambda s: s["preparation"].get("ready", {}).get("seed") == SEED
                      and s["preparation"]["ready"]["preloadRadius"] == preload, 240, f"run with preload {preload}")
    start_run(server, [], command=command)


def probes_at_origin(server):
    return {
        dimension: server.rcon_json(f"soullink debug probe {dimension} 0 0")
        for dimension in (OVERWORLD, "minecraft:the_nether", "minecraft:the_end")
    }


def deterministic(probes):
    return {
        dimension: ([(p["x"], p["z"], p["noiseHeight"], p["biome"]) for p in probe["samples"]],
                    probe["structureStarts"])
        for dimension, probe in probes.items()
    }


def decoration(probes):
    return [
        (dimension, p["x"], p["z"], p["surfaceHeight"])
        for dimension, probe in probes.items()
        for p in probe["samples"]
    ] + [(dimension, probe["spawnChunkHash"]) for dimension, probe in probes.items()]


def test_staged_generation_equals_canonical_generation(server):
    """Chunks generated while staged (per-level seed, staging key) are compared with chunks generated
    after activation under the canonical key and global seed.

    Noise terrain, biomes and structure starts are pure functions of the seed and must match exactly.
    Decoration (trees, lakes) is placed across chunk borders in vanilla and depends on generation order;
    a canonical-only control run with a different order shows how much of that is vanilla's own variance.
    """
    run_with_seed(server, 4, "soullink reset")
    staged = probes_at_origin(server)

    run_with_seed(server, 0, "soullink reset")
    canonical = probes_at_origin(server)

    # Control: vanilla only, same seed, generated in a different order (parallel forced batch first).
    run_with_seed(server, 0, "soullink reset")
    server.rcon("forceload add -72 -72 72 72")
    wait_true(lambda: "chunks" in server.rcon("forceload query").lower(), 30, "forceload")
    time.sleep(3)
    control = probes_at_origin(server)
    server.rcon("forceload remove all")

    for probes in (staged, canonical, control):
        for probe in probes.values():
            assert probe["globalSeed"] == probe["overworldSeed"] == probe["netherSeed"] == probe["endSeed"] == SEED
    assert deterministic(staged) == deterministic(canonical) == deterministic(control)
    assert staged[OVERWORLD]["structureStarts"], "expected structure starts near the origin for this seed"

    staged_vs_canonical = [a for a, b in zip(decoration(staged), decoration(canonical)) if a != b]
    staged_vs_control = [a for a, b in zip(decoration(staged), decoration(control)) if a != b]
    vanilla_vs_vanilla = [a for a, b in zip(decoration(canonical), decoration(control)) if a != b]
    # Informational only: vanilla decoration is not reproducible across generation orders, even with the
    # same seed and no Soul Link involvement, so exact decoration equality is not a valid criterion.
    print(f"decoration differences: staged vs sequential {staged_vs_canonical}; staged vs batch {staged_vs_control}; "
          f"vanilla sequential vs batch {vanilla_vs_vanilla}")
    assert f"[{SEED}]" in server.rcon("seed")


def test_changed_data_packs_invalidate_the_prepared_run(server):
    ready = server.wait_prepared()
    pack = server.world / "datapacks" / "soullink_fingerprint_probe"
    (pack / "data").mkdir(parents=True, exist_ok=True)
    (pack / "pack.mcmeta").write_text(json.dumps({
        "pack": {"description": "Soul Link fingerprint probe", "min_format": 107, "max_format": 107}}))
    server.rcon("reload")
    output = server.rcon('datapack enable "file/soullink_fingerprint_probe"')
    assert "nknown" not in output, output

    def replaced():
        status = server.status()
        current = status["preparation"].get("ready")
        return current if current and current["runId"] != ready["runId"] else None

    fresh = wait_true(replaced, 240, "a re-prepared run")
    assert fresh["fingerprint"] != ready["fingerprint"]
    stale = server.stage_dimensions / ready["runId"]
    assert not stale.exists(), "stale staged data is deleted"
    start_run(server, [], command="soullink reset")


def test_dimensions_that_are_not_reset_keep_the_world_seed(server):
    """Runs rebind the server-global seed; the lobby keeps the world's own seed, also after a restart."""
    status = server.status()
    assert status["status"] == "RUNNING" and status["globalSeed"] != LEVEL_SEED
    assert levels(status)[OVERWORLD]["seed"] == status["globalSeed"]
    assert levels(status)[LOBBY]["seed"] == LEVEL_SEED
    server.stop()
    server.start()
    status = server.status()
    assert levels(status)[OVERWORLD]["seed"] == status["globalSeed"] != LEVEL_SEED
    assert levels(status)[LOBBY]["seed"] == LEVEL_SEED
    assert json.loads((server.library_data / "pinned-seed.json").read_text())["seed"] == LEVEL_SEED
