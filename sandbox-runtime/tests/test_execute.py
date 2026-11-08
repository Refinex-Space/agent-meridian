"""``POST /execute`` 与命令执行器。"""

import os
import time
from pathlib import Path

from fastapi.testclient import TestClient

from sandbox_runtime.executor import TIMEOUT_EXIT_CODE, run_command


def test_execute_returns_extension_fields(client: TestClient) -> None:
    resp = client.post("/execute", json={"command": "echo out; echo err >&2; exit 3"})

    assert resp.status_code == 200
    body = resp.json()
    # 扩展的 ExecutionResult 读取 stdout、stderr、exit_code
    assert body["stdout"] == "out\n"
    assert body["stderr"] == "err\n"
    assert body["exit_code"] == 3
    assert body["timed_out"] is False


def test_execute_runs_in_workspace_with_bash(client: TestClient, workspace: Path) -> None:
    # 扩展把命令包装为 cd '<root>' && (\n<command>\n)
    wrapped = f"cd '{workspace}' && (\nset -o pipefail; pwd; echo $BASH_VERSION | cut -c1\n)"

    body = client.post("/execute", json={"command": wrapped}).json()

    lines = body["stdout"].splitlines()
    assert os.path.realpath(lines[0]) == os.path.realpath(workspace)
    assert lines[1].isdigit()
    assert body["exit_code"] == 0


def test_execute_default_cwd_is_workspace(client: TestClient, workspace: Path) -> None:
    client.post("/execute", json={"command": "echo hi > made.txt"})

    assert (workspace / "made.txt").read_text() == "hi\n"


def test_execute_timeout_kills_process_group(client: TestClient, workspace: Path) -> None:
    marker = workspace / "survived"
    command = f"(sleep 3; touch '{marker}') & echo started; sleep 30"

    start = time.monotonic()
    body = client.post("/execute", json={"command": command, "timeout_seconds": 1}).json()
    elapsed = time.monotonic() - start

    assert body["exit_code"] == TIMEOUT_EXIT_CODE
    assert body["timed_out"] is True
    assert body["stdout"] == "started\n"
    assert body["stderr"].endswith("Command timed out after 1 seconds")
    assert elapsed < 3
    time.sleep(2.5)
    assert not marker.exists(), "后台子进程应随进程组一起被终止"


def test_execute_timeout_cannot_exceed_server_limit(client: TestClient) -> None:
    body = client.post("/execute", json={"command": "sleep 30", "timeout_seconds": 600}).json()

    assert body["exit_code"] == TIMEOUT_EXIT_CODE
    assert "after 5 seconds" in body["stderr"]


def test_execute_does_not_wait_for_background_children(client: TestClient) -> None:
    start = time.monotonic()
    body = client.post("/execute", json={"command": "sleep 20 & echo done"}).json()

    assert body["stdout"] == "done\n"
    assert body["exit_code"] == 0
    assert time.monotonic() - start < 3


def test_execute_truncates_output(client: TestClient) -> None:
    body = client.post("/execute", json={"command": "head -c 100000 /dev/zero | tr '\\0' a"}).json()

    assert body["stdout"].startswith("a" * 65536)
    assert body["stdout"].endswith("[output truncated: 34464 bytes omitted]")
    assert body["exit_code"] == 0


def test_execute_reports_signal_exit(client: TestClient) -> None:
    body = client.post("/execute", json={"command": "kill -TERM $$"}).json()

    assert body["exit_code"] == 128 + 15


def test_execute_replaces_invalid_utf8(client: TestClient) -> None:
    body = client.post("/execute", json={"command": "printf '\\377ok'"}).json()

    assert body["stdout"] == "�ok"


def test_execute_rejects_missing_command(client: TestClient) -> None:
    assert client.post("/execute", json={}).status_code == 422


def test_concurrent_commands_run_independently(workspace: Path) -> None:
    from concurrent.futures import ThreadPoolExecutor

    def run(i: int) -> str:
        return run_command(
            f"sleep 0.2; echo {i}", cwd=str(workspace), timeout=5, output_limit=1024
        ).stdout

    with ThreadPoolExecutor(8) as pool:
        results = list(pool.map(run, range(8)))

    assert results == [f"{i}\n" for i in range(8)]


def test_health(client: TestClient) -> None:
    assert client.get("/").json() == {"status": "ok"}
