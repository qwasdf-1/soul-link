"""Chunk IO under load during a reset: old writers drained, no corrupt region/POI/entity data.

The canonical Overworld is given about a thousand forceloaded chunks to generate, block, POI and entity
changes in loaded chunks, and a player loading terrain far away, and a reset is requested while that
work is in flight. The first reset fails on purpose after retirement, so the retired world is reopened
from what retirement wrote; the second succeeds. After each, the server is stopped and every saved
chunk is parsed from disk.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from helpers import LOBBY, start_run, wait_true
from terra_rasa_testkit.anvil import entities_with_tag, read_chunks, verify_dimension

ALLOWED_LOG = ("Injected failure", "Canonical activation failed")
MARKER_TAG = "t09"
#: Marker chunks around the spawn, offset in chunks; preloadRadius 2 keeps them loaded.
MARKER_OFFSETS = [(dx, dz) for dx in (-2, -1, 1, 2) for dz in (-2, -1, 1, 2)]
MARKER_Y = 250


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def overworld(server) -> Path:
    return server.world / "dimensions" / "minecraft" / "overworld"


def marker_positions(spawn: list[int]) -> list[tuple[int, int, int]]:
    cx, cz = spawn[0] >> 4, spawn[2] >> 4
    return [((cx + dx) * 16 + 8, MARKER_Y, (cz + dz) * 16 + 8) for dx, dz in MARKER_OFFSETS]


def load_up(server, spawn: list[int]) -> None:
    """Dirties loaded chunks and queues a large amount of chunk generation and IO."""
    for x, y, z in marker_positions(spawn):
        overworld_command(server, f"setblock {x} {y} {z} minecraft:gold_block")
        overworld_command(server, f"setblock {x} {y + 1} {z} minecraft:bell")  # a POI ("meeting")
        overworld_command(server, f"summon minecraft:armor_stand {x + 0.5} {y + 2} {z + 0.5} "
                                  f"{{NoGravity:1b,Tags:[\"{MARKER_TAG}\"]}}")
    sx, sz = spawn[0], spawn[2]
    # Four 16x16-chunk areas, vanilla's per-command maximum: 1024 chunks queued at once.
    for x0, z0 in ((sx - 256, sz - 256), (sx, sz - 256), (sx - 256, sz), (sx, sz)):
        overworld_command(server, f"forceload add {x0} {z0} {x0 + 255} {z0 + 255}")
    # A spectator still generates the terrain around them, and cannot die of the fall.
    server.rcon("gamemode spectator Bob")
    server.rcon(f"tp Bob {sx + 3000} 200 {sz + 3000}")


def overworld_command(server, command: str) -> str:
    return server.rcon(f"execute in minecraft:overworld run {command}")


def loaded_markers(server) -> int:
    """Entities count for selectors only once their chunk is entity-ticking."""
    match = re.search(r"count: (\d+)", server.rcon(f"execute in minecraft:overworld if entity @e[tag={MARKER_TAG}]"),
                      re.IGNORECASE)
    return int(match.group(1)) if match else 0


def meeting_pois(root: Path) -> set[tuple[int, int, int]]:
    """Positions of every saved "meeting" POI (bells, including any in generated villages)."""
    found = set()
    for region in (root / "poi").glob("r.*.*.mca"):
        for _, _, chunk in read_chunks(region):
            for section in chunk.get("Sections", {}).values():
                found.update(tuple(record["pos"]) for record in section.get("Records", [])
                             if record.get("type") == "minecraft:meeting")
    return found


def assert_intact(server, dimensions=("overworld", "the_nether", "the_end")) -> dict[str, int]:
    chunks = {}
    for name in dimensions:
        report = verify_dimension(server.world / "dimensions" / "minecraft" / name)
        assert not report.problems, f"{name}: {report.problems[:5]}"
        chunks[name] = report.chunks
    return chunks


def test_failed_reset_under_load_reopens_everything_retirement_wrote(server, players):
    status = start_run(server, players)
    spawn = status["spawn"]
    load_up(server, spawn)
    server.wait_prepared()

    server.rcon("soullink debug fail validate")
    output = server.rcon("reset")
    assert "rolled back" in output, output
    status = server.status()
    print("retirement under load, timings:", status["activation"]["lastTimingsMillis"])
    for bot in players:
        bot.assert_connected()
        assert bot.state.dimension == LOBBY

    # Everything placed just before the reset is back, loaded from the files retirement wrote.
    for x, y, z in marker_positions(spawn):
        wait_true(lambda: "passed" in server.rcon(f"execute in minecraft:overworld if block {x} {y} {z} minecraft:gold_block"),
                  60, f"the marker at {x} {z} to load")
    wait_true(lambda: loaded_markers(server) == len(MARKER_OFFSETS), 60, "the marker entities to load")

    server.stop()
    chunks = assert_intact(server, ("overworld",))
    assert chunks["overworld"] > 0
    assert entities_with_tag(overworld(server), MARKER_TAG) == len(MARKER_OFFSETS)
    bells = {(x, y + 1, z) for x, y, z in marker_positions(spawn)}
    assert bells <= meeting_pois(overworld(server)), "the bells' POIs were saved"


def test_reset_under_load_promotes_intact_worlds(server, players):
    if not server.running:
        server.start()
    players[:] = [server.bot("Alice"), server.bot("Bob")]
    # The restored world still has its forceloads: generation resumes at startup.
    status = server.status()
    assert status["status"] == "LOBBY"
    status = start_run(server, players, command="reset")
    print("activation under load, timings:", status["activation"]["lastTimingsMillis"])
    load_up(server, status["spawn"])
    server.wait_prepared()
    status = start_run(server, players, command="reset")
    print("activation under load, timings:", status["activation"]["lastTimingsMillis"])
    for bot in players:
        bot.assert_connected()
        assert bot.state.play_entered == 1, "no reconnects through resets under load"

    server.stop()
    chunks = assert_intact(server)
    assert chunks["overworld"] > 0
    # The promoted world is the new run's, so none of the old run's markers came along.
    assert entities_with_tag(overworld(server), MARKER_TAG) == 0
    server.start()
