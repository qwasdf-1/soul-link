"""Fixtures for the real-server suite. Default-config modules share one session server with clean resets between modules."""

from __future__ import annotations

import shutil
import pytest

from helpers import SoulLinkServer, start_run, wait_true
from mc_testkit.server import SUSPICIOUS, VANILLA_DIAGNOSTICS, environment

# Small preloads keep preparation fast; tests that measure preloading opt into larger radii.
DEFAULT_CONFIG = {"preloadRadius": 2}
DEFAULT_LEVEL_SEED = 4242
_DEFAULT_RULES = (
    ("sharedVitality", "true"),
    ("sharedPotions", "false"),
    ("soulLinkHardcore", "false"),
    ("halfHeart", "false"),
    ("naturalRegeneration", "true"),
    ("timerVisible", "true"),
    ("damageReport", "normal"),
    ("runStats", "true"),
)


def _is_default_module(module) -> bool:
    return (
        not getattr(module, "FRESH_SERVER", False)
        and getattr(module, "SOULLINK_CONFIG", DEFAULT_CONFIG) == DEFAULT_CONFIG
        and getattr(module, "LEVEL_SEED", DEFAULT_LEVEL_SEED) == DEFAULT_LEVEL_SEED
        and getattr(module, "ALLOWED_LOG", ()) == ()
    )


def _disconnect_bots(process: SoulLinkServer) -> None:
    if not process._bots:
        return
    for bot in list(process._bots):
        bot.disconnect()
    process._bots.clear()
    if process.running:
        wait_true(lambda: "There are 0 of a max of" in process.rcon("list"), 10, "all bots to disconnect")


def _new_suspicious_lines(process: SoulLinkServer, start_line: int, allow: tuple[str, ...]) -> list[str]:
    allowed = allow + VANILLA_DIAGNOSTICS
    with process._lines_lock:
        slice_lines = list(process.lines[start_line:])
    return [line for line in slice_lines if SUSPICIOUS.search(line) and not any(a in line for a in allowed)]


def _reset_shared_server(process: SoulLinkServer) -> None:
    if not process.running:
        process.start()
    _disconnect_bots(process)
    probe_pack = process.world / "datapacks" / "soullink_fingerprint_probe"
    if probe_pack.exists():
        shutil.rmtree(probe_pack)
        process.rcon("reload")
    process.rcon("gamerule natural_health_regeneration true")
    process.rcon("difficulty normal")
    for rule, value in _DEFAULT_RULES:
        process.rcon(f"soullink rules {rule} {value} default")
    if process.status()["preparation"].get("readyLimit", 1) != 1:
        process.rcon("soullink setup pool 1")
    start_run(process, [], command="soullink reset")


@pytest.fixture(scope="session")
def env():
    try:
        return environment()
    except KeyError as missing:
        pytest.exit(f"Run through ./gradlew integrationTest (missing environment variable {missing})", returncode=2)


@pytest.fixture(scope="session")
def _shared_default_server(env):
    state: dict[str, object] = {"process": None, "dirty": False}
    yield state
    process = state["process"]
    if isinstance(process, SoulLinkServer):
        process.stop()


@pytest.fixture(scope="module")
def server(env, request, _shared_default_server):
    install, java, packets = env
    module = request.module
    allow = getattr(module, "ALLOWED_LOG", ())
    if _is_default_module(module):
        process = _shared_default_server["process"]
        if process is None:
            process = SoulLinkServer(install, "default_shared", java, packets,
                                     DEFAULT_CONFIG, DEFAULT_LEVEL_SEED)
            process.start()
            _shared_default_server["process"] = process
            _shared_default_server["dirty"] = False
        elif _shared_default_server["dirty"]:
            _reset_shared_server(process)
            _shared_default_server["dirty"] = False
        log_start = len(process.lines)
        try:
            yield process
        finally:
            _disconnect_bots(process)
            _shared_default_server["dirty"] = True
        problems = _new_suspicious_lines(process, log_start, allow)
        assert not problems, "unexpected errors in the server log:\n" + "\n".join(problems)
        return

    process = SoulLinkServer(install, module.__name__.rsplit(".", 1)[-1], java, packets,
                            getattr(module, "SOULLINK_CONFIG", DEFAULT_CONFIG),
                            getattr(module, "LEVEL_SEED", DEFAULT_LEVEL_SEED))
    process.start()
    yield process
    process.stop()
    problems = process.suspicious_lines(allow=allow)
    assert not problems, "unexpected errors in the server log:\n" + "\n".join(problems)


@pytest.fixture
def fresh_server(env, request):
    """A dedicated server per test, for crash and restart scenarios."""
    install, java, packets = env
    process = SoulLinkServer(install, request.node.name[:60], java, packets,
                            getattr(request.module, "SOULLINK_CONFIG", DEFAULT_CONFIG),
                            getattr(request.module, "LEVEL_SEED", DEFAULT_LEVEL_SEED))
    yield process
    if process.running:
        process.stop()
