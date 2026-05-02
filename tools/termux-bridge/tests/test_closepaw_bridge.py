import json
import os
import shlex
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

from conftest import HOST, BridgeProcess, start_process, stop_process, wait_for_health


OUTPUT_CAP_BYTES = 65536


def python_command(code):
    return f"{shlex.quote(sys.executable)} -c {shlex.quote(code)}"


def assert_dead(pid):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.05)
    raise AssertionError(f"process {pid} is still alive")


def wait_for_file(path):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if path.exists():
            return
        time.sleep(0.02)
    raise AssertionError(f"{path} was not created")


def wait_for_exit(proc):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            return
        time.sleep(0.05)
    raise AssertionError("bridge process did not exit")


def test_health_endpoint_returns_ok_version_and_identity(bridge_server):
    response = bridge_server.get_json("/v1/health")

    assert response.status == 200
    assert response.body["status"] == "ok"
    assert response.body["version"] == "1"
    assert response.body["identity"] == "closepaw-bridge"
    assert isinstance(response.body["uptime_ms"], int)
    assert isinstance(response.body["last_request_ms_ago"], int)


def test_exec_endpoint_runs_simple_command(bridge_server):
    response = bridge_server.post_json("/v1/exec", {"command": "echo hi"})

    assert response.status == 200
    assert response.body["stdout"] == "hi\n"
    assert response.body["stderr"] == ""
    assert response.body["exit_code"] == 0
    assert response.body["timed_out"] is False
    assert response.body["stdout_truncated"] is False
    assert response.body["stderr_truncated"] is False


def test_exec_timeout_kills_process_group(bridge_server, tmp_path):
    child_pid_file = tmp_path / "child.pid"
    command = f"sleep 30 & echo $! > {shlex.quote(str(child_pid_file))}; wait"

    response = bridge_server.post_json(
        "/v1/exec",
        {"command": command, "timeout_ms": 400},
        timeout=5,
    )

    assert response.status == 200
    assert response.body["timed_out"] is True
    assert response.body["exit_code"] is None
    child_pid = int(child_pid_file.read_text())
    assert_dead(child_pid)


def test_exec_returns_http_200_for_non_zero_exit_code(bridge_server):
    response = bridge_server.post_json("/v1/exec", {"command": "exit 7"})

    assert response.status == 200
    assert response.body["exit_code"] == 7
    assert response.body["timed_out"] is False


def test_stdout_output_cap_writes_full_stdout_artifact(bridge_server):
    output_size = OUTPUT_CAP_BYTES + 123
    command = python_command(
        f"import sys; sys.stdout.buffer.write(b'x' * {output_size})"
    )

    response = bridge_server.post_json("/v1/exec", {"command": command}, timeout=10)

    assert response.status == 200
    assert response.body["exit_code"] == 0
    assert response.body["stdout"] == "x" * OUTPUT_CAP_BYTES
    assert response.body["stderr"] == ""
    assert response.body["stdout_truncated"] is True
    assert response.body["stderr_truncated"] is False
    assert response.body["stderr_ref"] is None
    artifact = Path(response.body["stdout_ref"])
    assert bridge_server.home in artifact.parents
    assert artifact.name.endswith("_stdout")
    assert not artifact.name.endswith(".txt")
    assert artifact.read_bytes() == b"x" * output_size


def test_stderr_output_cap_writes_full_stderr_artifact_independently(bridge_server):
    output_size = OUTPUT_CAP_BYTES + 123
    command = python_command(
        f"import sys; sys.stderr.buffer.write(b'e' * {output_size})"
    )

    response = bridge_server.post_json("/v1/exec", {"command": command}, timeout=10)

    assert response.status == 200
    assert response.body["exit_code"] == 0
    assert response.body["stdout"] == ""
    assert response.body["stderr"] == "e" * OUTPUT_CAP_BYTES
    assert response.body["stdout_truncated"] is False
    assert response.body["stderr_truncated"] is True
    assert response.body["stdout_ref"] is None
    artifact = Path(response.body["stderr_ref"])
    assert bridge_server.home in artifact.parents
    assert artifact.name.endswith("_stderr")
    assert not artifact.name.endswith(".txt")
    assert artifact.read_bytes() == b"e" * output_size


def test_exec_env_is_merged_into_process_environment(bridge_server):
    response = bridge_server.post_json(
        "/v1/exec",
        {"command": "printf '%s\\n' \"$TERM\"", "env": {"TERM": "dumb"}},
    )

    assert response.status == 200
    assert response.body["stdout"] == "dumb\n"


def test_max_output_bytes_overrides_default_cap(bridge_server):
    command = python_command("import sys; sys.stdout.buffer.write(b'x' * 4096)")

    response = bridge_server.post_json(
        "/v1/exec",
        {"command": command, "max_output_bytes": 1024},
        timeout=10,
    )

    assert response.status == 200
    assert response.body["stdout"] == "x" * 1024
    assert response.body["stdout_truncated"] is True
    artifact = Path(response.body["stdout_ref"])
    assert artifact.name.endswith("_stdout")
    assert artifact.read_bytes() == b"x" * 4096


def test_invalid_env_and_max_output_bytes_return_invalid_request(bridge_server):
    bad_requests = [
        {"command": "true", "env": {"TERM": 1}},
        {"command": "true", "env": ["TERM=dumb"]},
        {"command": "true", "max_output_bytes": 0},
        {"command": "true", "max_output_bytes": True},
    ]

    for payload in bad_requests:
        response = bridge_server.post_json("/v1/exec", payload)
        assert response.status == 400
        assert response.body == {"error": "invalid_request"}


def test_cwd_is_constrained_to_resolved_workspace(bridge_server, tmp_path):
    workspace = bridge_server.home / "closepaw" / "workspace"
    subdir = workspace / "sub"
    subdir.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    symlink = workspace / "escape"
    symlink.symlink_to(outside)

    ok = bridge_server.post_json(
        "/v1/exec",
        {"command": "pwd", "cwd": "~/closepaw/workspace/sub"},
    )
    absolute_escape = bridge_server.post_json("/v1/exec", {"command": "pwd", "cwd": "/tmp"})
    dotdot_escape = bridge_server.post_json(
        "/v1/exec",
        {"command": "pwd", "cwd": "~/closepaw/workspace/../.."},
    )
    symlink_escape = bridge_server.post_json(
        "/v1/exec",
        {"command": "pwd", "cwd": "~/closepaw/workspace/escape"},
    )

    assert ok.status == 200
    assert Path(ok.body["stdout"].strip()) == subdir.resolve()
    for response in (absolute_escape, dotdot_escape, symlink_escape):
        assert response.status == 400
        assert response.body == {"error": "workspace_escape"}


def test_stale_daemon_pidfile_is_overwritten_by_new_daemon(tmp_path, free_port, bridge_factory):
    pidfile = tmp_path / ".closepaw" / "bridge.pid"
    pidfile.parent.mkdir(parents=True)
    pidfile.write_text(
        json.dumps(
            {
                "pid": 999999999,
                "port": free_port,
                "version": "1",
                "identity": "closepaw-bridge",
            }
        )
    )

    bridge = bridge_factory(free_port, tmp_path)

    response = bridge.get_json("/v1/health")
    assert response.status == 200
    assert response.body["identity"] == "closepaw-bridge"
    pidfile_data = json.loads(pidfile.read_text())
    assert pidfile_data["pid"] == bridge.process.pid
    assert pidfile_data["port"] == free_port
    assert pidfile_data["identity"] == "closepaw-bridge"


def test_live_old_bridge_is_health_probed_then_replaced(tmp_path, free_port, bridge_factory):
    first = bridge_factory(free_port, tmp_path)
    second_proc = start_process(free_port, tmp_path)
    second = BridgeProcess(port=free_port, home=tmp_path, process=second_proc)
    pidfile = tmp_path / ".closepaw" / "bridge.pid"
    try:
        wait_for_exit(first.process)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            assert second.process.poll() is None
            try:
                owner_pid = json.loads(pidfile.read_text())["pid"]
            except (FileNotFoundError, KeyError, json.JSONDecodeError):
                owner_pid = None
            if owner_pid == second_proc.pid:
                wait_for_health(second)
                break
            time.sleep(0.05)
        else:
            raise AssertionError("replacement bridge did not take over pidfile")

        assert second.get_json("/v1/health").body["identity"] == "closepaw-bridge"
    finally:
        stop_process(second_proc)


def test_live_foreign_pidfile_reports_port_in_use_without_killing_pid(tmp_path, free_port):
    foreign = subprocess.Popen(["sleep", "30"])
    pidfile = tmp_path / ".closepaw" / "bridge.pid"
    pidfile.parent.mkdir(parents=True)
    pidfile.write_text(json.dumps({"pid": foreign.pid, "port": free_port, "identity": "foreign"}))

    proc = start_process(free_port, tmp_path)
    try:
        stdout, stderr = proc.communicate(timeout=5)
        assert proc.returncode != 0
        assert stdout == ""
        assert "port_in_use" in stderr
        assert foreign.poll() is None
    finally:
        stop_process(proc)
        foreign.terminate()
        foreign.wait(timeout=5)


def test_port_in_use_exits_non_zero_and_reports_error(tmp_path, free_port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind((HOST, free_port))
        sock.listen(1)
        proc = start_process(free_port, tmp_path)
        try:
            stdout, stderr = proc.communicate(timeout=5)
        finally:
            stop_process(proc)

    assert proc.returncode != 0
    assert stdout == ""
    assert "port_in_use" in stderr


def test_busy_exec_returns_http_409_without_waiting(bridge_server, tmp_path):
    ready_file = tmp_path / "busy.ready"
    long_command = python_command(
        "from pathlib import Path; import time; "
        f"Path({str(ready_file)!r}).write_text('ready'); "
        "time.sleep(1)"
    )
    result = {}

    def run_long_exec():
        try:
            result["response"] = bridge_server.post_json(
                "/v1/exec",
                {"command": long_command, "timeout_ms": 5000},
                timeout=10,
            )
        except BaseException as exc:
            result["error"] = exc

    thread = threading.Thread(target=run_long_exec)
    thread.start()
    try:
        deadline = time.monotonic() + 5
        while not ready_file.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        assert ready_file.exists()

        started_at = time.monotonic()
        response = bridge_server.post_json("/v1/exec", {"command": "echo second"})

        assert response.status == 409
        assert response.body == {"error": "busy"}
        assert time.monotonic() - started_at < 0.5
    finally:
        thread.join(timeout=10)

    assert not thread.is_alive()
    assert "error" not in result
    first_response = result["response"]
    assert first_response.status == 200
    assert first_response.body["exit_code"] == 0


def test_client_disconnect_kills_process_group_and_releases_exec_lock(bridge_server):
    child_pid_file = bridge_server.home / "closepaw" / "workspace" / "disconnect_child.pid"
    command = f"sleep 30 & echo $! > {shlex.quote(str(child_pid_file))}; wait"
    payload = json.dumps({"command": command}).encode()
    request = (
        b"POST /v1/exec HTTP/1.1\r\n"
        + f"Host: {HOST}:{bridge_server.port}\r\n".encode()
        + b"Content-Type: application/json\r\n"
        + f"Content-Length: {len(payload)}\r\n\r\n".encode()
        + payload
    )

    with socket.create_connection((HOST, bridge_server.port), timeout=5) as sock:
        sock.sendall(request)
        wait_for_file(child_pid_file)

    child_pid = int(child_pid_file.read_text())
    assert_dead(child_pid)

    started_at = time.monotonic()
    response = bridge_server.post_json("/v1/exec", {"command": "echo ok"})
    assert response.status == 200
    assert response.body["stdout"] == "ok\n"
    assert time.monotonic() - started_at < 1


def test_watchdog_idle_shutdown_exits_and_cleans_pidfile(tmp_path, free_port, bridge_factory):
    bridge = bridge_factory(free_port, tmp_path, idle_timeout_sec=1, watchdog_tick_sec=0.1)

    assert bridge.get_json("/v1/health").status == 200
    wait_for_exit(bridge.process)

    assert bridge.process.returncode == 0
    assert not (tmp_path / ".closepaw" / "bridge.pid").exists()


def test_health_requests_refresh_watchdog_idle_timer(tmp_path, free_port, bridge_factory):
    bridge = bridge_factory(free_port, tmp_path, idle_timeout_sec=1, watchdog_tick_sec=0.1)

    for _ in range(4):
        time.sleep(0.5)
        assert bridge.get_json("/v1/health").status == 200
        assert bridge.process.poll() is None

    assert bridge.process.poll() is None
