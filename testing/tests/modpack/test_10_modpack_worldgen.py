"""Modded worldgen on canonical run dimensions.

The reference pack replaces Overworld terrain and biomes (Tectonic, Terralith), Nether biomes (Incendium),
End terrain (Nullscape) and adds structures (Structory). A run prepared with a seed must generate exactly
what the same pack generates natively for that seed: a control server started with that seed is the
reference, and only seed-determined layers are compared (see test_04 for why decoration is not).
"""

from __future__ import annotations

import pytest

from helpers import SoulLinkServer, start_run

pytestmark = pytest.mark.modpack

SOULLINK_CONFIG = {"preloadRadius": 2}
SEED = 515151


def probe(server, dimension: str, x: int, z: int) -> dict:
    return server.rcon_json(f"soullink debug probe {dimension} {x} {z}")


def layers(sample: dict) -> tuple:
    return [(p["x"], p["z"], p["noiseHeight"], p["biome"]) for p in sample["samples"]], sample["structureStarts"]


def probes(server, spawn: tuple[int, int]) -> dict[str, dict]:
    return {
        "minecraft:overworld": probe(server, "minecraft:overworld", *spawn),
        "minecraft:the_nether": probe(server, "minecraft:the_nether", 0, 0),
        "minecraft:the_end": probe(server, "minecraft:the_end", 0, 0),
    }


@pytest.fixture(scope="module")
def control(env):
    """The pack's own generation for SEED, through vanilla's startup path (no Soul Link activation)."""
    install, java, packets = env
    server = SoulLinkServer(install, "test_10_modpack_worldgen-control", java, packets, {"autoPrepare": False}, SEED)
    server.start(timeout=400)
    try:
        startup = server.status()["startupSpawn"]
        spawn = (startup["x"], startup["z"])
        result = {"spawn": (startup["x"], startup["y"], startup["z"]), "probes": probes(server, spawn)}
        assert not server.suspicious_lines()
        return result
    finally:
        server.stop()


#: One biome each pack adds, per run dimension. Many Terralith areas reuse vanilla biome ids, so sampling a
#: few points near spawn proves nothing; /locate searches the biome source without generating chunks.
MODDED_BIOMES = {
    "minecraft:overworld": "terralith:cave/deep_caves",
    "minecraft:the_nether": "incendium:weeping_valley",
    "minecraft:the_end": "nullscape:shadowlands",
}


def test_modded_worldgen_is_live_in_the_run(server):
    status = start_run(server, [], timeout=400)
    spawn = status["spawn"]
    for dimension, sample in probes(server, (spawn[0], spawn[2])).items():
        assert sample["levelSeed"] == status["runSeed"], dimension
    for dimension, biome in MODDED_BIOMES.items():
        output = server.rcon(f"execute in {dimension} positioned 0 64 0 run locate biome {biome}")
        assert "nearest" in output.lower(), f"{dimension}: {output}"


def test_staged_spawn_matches_the_packs_native_spawn(server, control):
    server.rcon(f"soullink prepare {SEED} 2")
    ready = server.wait_until(lambda s: (s["preparation"].get("ready") or {}).get("seed") == SEED, 400,
                              "the seeded run")["preparation"]["ready"]
    assert (ready["spawn"]["x"], ready["spawn"]["y"], ready["spawn"]["z"]) == control["spawn"]


def test_run_generation_equals_native_generation(server, control):
    status = start_run(server, [], command="reset", timeout=400)
    assert status["runSeed"] == SEED
    x, _, z = status["spawn"]
    run = probes(server, (x, z))
    for dimension, reference in control["probes"].items():
        # Noise height, biome and structure starts at and around the preloaded border: identical whether a
        # chunk was generated while staged, after activation, or natively by the control server.
        assert layers(run[dimension]) == layers(reference), dimension
