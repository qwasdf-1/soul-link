"""Fixtures for the real-server suite. Each test module gets its own fresh server and world."""

from __future__ import annotations

import pytest

from helpers import SoulLinkServer
from terra_rasa_testkit.server import environment

# Small preloads keep preparation fast; tests that measure preloading opt into larger radii.
DEFAULT_CONFIG = {"preloadRadius": 2}
DEFAULT_LEVEL_SEED = 4242


@pytest.fixture(scope="session")
def env():
    try:
        return environment()
    except KeyError as missing:
        pytest.exit(f"Run through ./gradlew integrationTest (missing environment variable {missing})", returncode=2)


@pytest.fixture(scope="module")
def server(env, request):
    install, java, packets = env
    module = request.module
    process = SoulLinkServer(install, module.__name__.rsplit(".", 1)[-1], java, packets,
                            getattr(module, "SOULLINK_CONFIG", DEFAULT_CONFIG),
                            getattr(module, "LEVEL_SEED", DEFAULT_LEVEL_SEED))
    process.start()
    yield process
    process.stop()
    problems = process.suspicious_lines(allow=getattr(module, "ALLOWED_LOG", ()))
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
