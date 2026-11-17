"""Paired build-against-build cost (an mc-lab measurement: runs only with ``--lab``).

Compares the Soul Link jar this build installed (``candidate``) with another build's jar (``baseline``,
from ``MC_LAB_BASELINE_JAR``) on the same install. ``scripts/lab-compare.sh <ref>`` builds a baseline from
a git ref and runs this; the manual "Lab" workflow does the same in CI.

Every boot is a fresh server with two linked players and a run in progress, measured in two phases:

* idle: the game-loop thread's CPU per tick over ``IDLE_SECONDS`` once preparation has settled, and
  Soul Link's own self-timed cost per tick (``tickCost``, its rolling window of the last 1200 ticks
  without a preparation, read straight after the idle window). The self-timed cost is far less noisy
  but sees only the work Soul Link times, not cost it adds to vanilla code paths;
* preparation: the thread's CPU over one whole ``soullink prepare`` of a fixed seed and radius.

Both arms run from their own copy of the install's mods folder, differing only in the Soul Link jar.
The lab configuration (``mc_lab.profile``) comes from the environment and is part of the design:
``MC_LAB_WORLD`` (``real`` by default, or ``isolated``: no mob spawning, time and weather frozen),
``MC_LAB_GAMERULES`` for single variables, and ``MC_LAB_CPUS`` / ``MC_LAB_HEAP`` to constrain the server.
With CPUs pinned, the server thread's wait for a CPU is the contention under study: it no longer fails a
window's quality verdict and the summary reports each arm's median wait fraction per phase.

Boots run in ABBA/BAAB blocks and adjacent boots pair, so slow drift on the host cancels within a pair.
Each session first runs ``WARMUP_BOOTS`` unmeasured boots of the next arm: the first boot on a cold host
idled about a third slower than the rest in the first smoke run. Warm-up boots are recorded, never paired.
With the same jar in both arms the run is an A/A calibration: its spread is what this host cannot resolve.
The report states each metric's median paired change, its exact interval and the achieved coverage. A
regression or an inconclusive interval is a result, not a failure: the test fails only when the
measurement itself breaks. The report is rewritten after every boot, and a rerun with the same design
and jars resumes from the boots already recorded.
"""

from __future__ import annotations

import json
import os
import statistics
from pathlib import Path
import time

import pytest

import mc_lab
from helpers import SoulLinkServer, start_run
from mc_lab.arms import describe_jar, mods_variant
from mc_lab.design import abba_blocks, adjacent_pairs, boot_sequence
from mc_lab.host import is_noisy, load_average_1m, snapshot_host
from mc_lab.profile import LabConfig, allowed_cpus, apply_gamerules, parse_cpus
from mc_lab.stats import paired_percent_changes, summarize_paired_changes
from mc_lab.ticks import parse_tick_query

pytestmark = pytest.mark.lab

BASELINE, CANDIDATE = "baseline", "candidate"
#: Three blocks are twelve boots and six pairs: the fewest that reach 95% coverage (96.9%).
BLOCKS = int(os.environ.get("MC_LAB_BLOCKS", "3"))
IDLE_SECONDS = 30
PRELOAD_RADIUS = 6
PREPARE_SEED = 20261116
LEVEL_SEED = 4242
SOULLINK_CONFIG = {"preloadRadius": PRELOAD_RADIUS}
JAR_GLOB = "soullink-*.jar"
TICKS_PER_SECOND = 20
METRICS = ("idle_cpu_ms_per_tick", "soul_link_idle_micros_per_tick", "preparation_cpu_seconds")
WARMUP_BOOTS = 1


def cpu_ms_per_tick(window: dict) -> float:
    return window["cpu_ns"] / 1e6 / (window["wall_ns"] / 1e9 * TICKS_PER_SECOND)


def jvm_heap(pid: int) -> str | None:
    """The ``-Xmx`` the running JVM took (the last one on its command line wins)."""
    try:
        arguments = Path(f"/proc/{pid}/cmdline").read_bytes().decode().split("\0")
    except OSError:
        return None
    sizes = [argument[len("-Xmx"):] for argument in arguments if argument.startswith("-Xmx")]
    return sizes[-1] if sizes else None


def measure_boot(server: SoulLinkServer, server_thread, max_wait_fraction: float | None) -> dict:
    players = [server.bot("Alice"), server.bot("Bob")]
    start_run(server, players)
    server.wait_until(
        lambda status: status["status"] == "RUNNING"
        and status["preparation"].get("active") is None
        and status["preparation"].get("readyCount", 0) >= 1,
        360,
        "the live run and its future-run pool to settle",
    )
    thread = server_thread(server)
    load_before = load_average_1m()
    idle = thread.measure(lambda: time.sleep(IDLE_SECONDS), max_wait_fraction)
    idle_ticks = parse_tick_query(server.rcon("tick query"))
    idle_cost = server.status().get("tickCost", {}).get("idle")
    with thread.window(max_wait_fraction) as preparation:
        response = server.rcon(f"soullink prepare {PREPARE_SEED} {PRELOAD_RADIUS}")
        assert "preparing a run" in response.lower(), response
        server.wait_until(lambda status: status["preparation"].get("ready", {}).get("seed") == PREPARE_SEED
                          and status["preparation"].get("active") is None, 360, "the measured preparation")
    ready = server.status()["preparation"]["ready"]
    for bot in players:
        bot.assert_connected()
    load_after = load_average_1m()
    return {
        "idle_cpu_ms_per_tick": cpu_ms_per_tick(idle),
        # A build without tickCost reports nothing; the summary then marks the metric unavailable.
        "soul_link_idle_micros_per_tick": idle_cost.get("meanMicros") if idle_cost else None,
        "preparation_cpu_seconds": preparation.result["cpu_ns"] / 1e9,
        "quality_pass": idle["quality_pass"] and preparation.result["quality_pass"],
        "noisy": is_noisy(max(load_before, load_after)),
        "load_1m": [load_before, load_after],
        "idle_window": idle,
        "preparation_window": preparation.result,
        "idle_ticks": idle_ticks,
        "idle_tick_cost": idle_cost,
        "preparation_timings_millis": ready.get("timingsMillis"),
    }


def summarize(boots: list[dict]) -> dict:
    pairs = [{boots[i]["arm"]: boots[i], boots[i + 1]["arm"]: boots[i + 1]} for i in range(0, len(boots) - 1, 2)]
    summary = {}
    for metric in METRICS:
        if any(boot.get(metric) is None for boot in boots):
            summary[metric] = {"unavailable": "not every boot reported it"}
            continue
        changes = paired_percent_changes(pairs, metric=metric, baseline_arm=BASELINE, candidate_arm=CANDIDATE)
        summary[metric] = {"changes_percent": changes, **summarize_paired_changes(changes)}
    summary["windows_failing_quality"] = sum(not boot["quality_pass"] for boot in boots)
    # With pinned CPUs the wait is the contention under study, reported here instead of failing quality.
    summary["runqueue_wait_fraction"] = {
        phase: {arm: statistics.median(boot[f"{phase}_window"]["runqueue_wait_fraction"]
                                       for boot in boots if boot["arm"] == arm) for arm in (BASELINE, CANDIDATE)}
        for phase in ("idle", "preparation")}
    summary["noisy_boots"] = sum(boot["noisy"] for boot in boots)
    return summary


def test_build_comparison(env, lab_host, server_thread):
    install, java, packets = env
    if not os.environ.get("MC_LAB_BASELINE_JAR"):
        pytest.fail("set MC_LAB_BASELINE_JAR to the baseline Soul Link jar (scripts/lab-compare.sh <ref> does)")
    baseline_jar = Path(os.environ["MC_LAB_BASELINE_JAR"]).resolve()
    candidates = sorted((install / "mods").glob(JAR_GLOB))
    assert len(candidates) == 1, f"expected one {JAR_GLOB} in {install / 'mods'}, found {candidates}"
    # Each arm runs from its own copy, so the arms differ only in the jar.
    arm_mods = {arm: mods_variant(install / "mods", install / "lab" / f"{arm}-mods", replace=JAR_GLOB, jar=jar)
                for arm, jar in ((BASELINE, baseline_jar), (CANDIDATE, candidates[0]))}
    config = LabConfig.from_env()
    sequence = boot_sequence(abba_blocks(BASELINE, CANDIDATE, BLOCKS))
    adjacent_pairs(sequence)  # validates the design before any boot
    design = {
        "blocks": BLOCKS, "sequence": list(sequence), "idle_seconds": IDLE_SECONDS,
        "preload_radius": PRELOAD_RADIUS, "prepare_seed": PREPARE_SEED, "level_seed": LEVEL_SEED,
        "loader": os.environ.get("MC_TEST_LOADER", "fabric"), "metrics": list(METRICS),
        "warmup_boots_per_session": WARMUP_BOOTS, "lab_config": config.as_dict(),
        "arms": {BASELINE: describe_jar(baseline_jar), CANDIDATE: describe_jar(candidates[0])},
    }
    refs = {BASELINE: os.environ.get("MC_LAB_BASELINE_REF"), CANDIDATE: os.environ.get("MC_LAB_CANDIDATE_REF")}
    report_dir = Path(os.environ.get("MC_LAB_REPORT_DIR", install.parent / "reports" / "lab"))
    report_dir.mkdir(parents=True, exist_ok=True)
    report_path = report_dir / "build-comparison.json"
    same_jar = design["arms"][BASELINE]["sha256"] == design["arms"][CANDIDATE]["sha256"]
    boots: list[dict] = []
    warmups: list[dict] = []
    if report_path.exists():
        previous = json.loads(report_path.read_text())
        if previous.get("design") == design and previous.get("lab") == mc_lab.__version__:
            boots, warmups = previous["boots"], previous.get("warmups", [])
            print(f"resuming from {len(boots)} recorded boots in {report_path}")

    def write(summary: dict | None = None) -> None:
        report = {"lab": mc_lab.__version__, "design": design, "refs": refs, "same_jar": same_jar,
                  "host": lab_host.as_dict(), "host_snapshot": snapshot_host(), "warmups": warmups,
                  "boots": boots, "summary": summary}
        report_path.write_text(json.dumps(report, indent=2) + "\n")

    def boot(session: str, arm: str) -> dict:
        server = SoulLinkServer(install, session, java, packets, SOULLINK_CONFIG, LEVEL_SEED,
                                mods=arm_mods[arm], **config.server_options())
        server.start()
        try:
            pinned = allowed_cpus(server.pid)
            if config.limits.cpus is not None:
                assert pinned and parse_cpus(pinned) == config.limits.cpus, f"{session} runs on CPUs {pinned}"
            heap = jvm_heap(server.pid)
            if config.limits.heap is not None:
                assert heap == config.limits.heap, f"{session} runs with -Xmx{heap}"
            applied = apply_gamerules(server.rcon, config.gamerules)
            result = measure_boot(server, server_thread, config.max_wait_fraction())
        finally:
            server.stop()
        problems = server.suspicious_lines()
        assert not problems, f"{session} logged errors:\n" + "\n".join(problems)
        return {"allowed_cpus": pinned, "max_heap": heap, "gamerules_applied": applied, **result}

    if len(boots) < len(sequence):
        for number in range(WARMUP_BOOTS):
            arm = sequence[len(boots)]
            result = boot(f"lab-warmup-{len(warmups):02d}-{arm}", arm)
            warmups.append({"before_boot": len(boots), "arm": arm, **result})
            write()
            print(f"warm-up {number + 1}/{WARMUP_BOOTS} {arm} (not paired): idle "
                  f"{result['idle_cpu_ms_per_tick']:.3f} ms/tick, preparation "
                  f"{result['preparation_cpu_seconds']:.2f} s CPU")

    for index in range(len(boots), len(sequence)):
        arm = sequence[index]
        result = boot(f"lab-{index:02d}-{arm}", arm)
        boots.append({"index": index, "arm": arm, **result})
        write()
        print(f"boot {index + 1}/{len(sequence)} {arm}: idle {result['idle_cpu_ms_per_tick']:.3f} ms/tick, "
              f"preparation {result['preparation_cpu_seconds']:.2f} s CPU")

    summary = summarize(boots)
    write(summary)
    print(f"report: {report_path}" + (" (A/A: both arms ran the same jar)" if same_jar else ""))
    print("lab configuration:", config.as_dict())
    print(f"windows failing quality: {summary['windows_failing_quality']}; median runqueue wait fraction: "
          + ", ".join(f"{phase} {arm} {fraction:.3f}" for phase, arms in summary["runqueue_wait_fraction"].items()
                      for arm, fraction in arms.items()))
    for metric in METRICS:
        row = summary[metric]
        if "unavailable" in row:
            print(f"{metric}: unavailable ({row['unavailable']})")
            continue
        print(f"{metric}: median {row['median_percent']:+.2f}% "
              f"[{row['ci_low_percent']:+.2f}%, {row['ci_high_percent']:+.2f}%] at "
              f"{row['confidence_achieved']:.1%} over {row['n']} pairs -> {row['no_regression_decision']}")
