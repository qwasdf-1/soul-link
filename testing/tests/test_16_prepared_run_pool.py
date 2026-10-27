"""FIFO prepared-run pooling and restart recovery."""

from __future__ import annotations

from helpers import start_run, wait_true


def test_pool_is_bounded_fifo_and_survives_restart(server):
    server.wait_until(lambda status: status["status"] == "RUNNING", 240, "the initial run")
    output = server.rcon("soullink setup pool 3")
    assert "pool set to 3" in output

    def full_pool():
        status = server.status()
        return status if status["preparation"].get("readyCount") == 3 else None

    full = wait_true(full_pool, 360, "three future runs to be prepared")
    queued = [run["runId"] for run in full["preparation"]["readyRuns"]]
    assert len(queued) == 3 and len(set(queued)) == 3
    assert full["preparation"]["readyLimit"] == 3

    server.restart()
    restored = server.wait_until(lambda status: status["preparation"].get("readyCount") == 3,
                                 60, "the prepared pool to be restored after restart")
    assert [run["runId"] for run in restored["preparation"]["readyRuns"]] == queued

    started = start_run(server, [], command="soullink reset")
    assert started["activeRunId"] == queued[0], "the oldest prepared run starts first"
    wait_true(lambda: server.status()["preparation"].get("readyCount") == 3,
              360, "the pool to refill after a run starts")
