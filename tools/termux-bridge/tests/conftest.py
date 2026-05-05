import json
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

import pytest


HOST = "127.0.0.1"
BRIDGE_SCRIPT = Path(__file__).resolve().parents[1] / "closepaw_bridge.py"


@dataclass
class JsonResponse:
    status: int
    body: dict


@dataclass
class BridgeProcess:
    port: int
    home: Path
    process: subprocess.Popen

    def get_json(self, path, timeout=5):
        return request_json(self.port, "GET", path, timeout=timeout)

    def post_json(self, path, payload, timeout=10):
        return request_json(self.port, "POST", path, payload=payload, timeout=timeout)


@pytest.fixture
def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind((HOST, 0))
        return sock.getsockname()[1]


@pytest.fixture
def bridge_factory():
    processes = []

    def start_bridge(port, home, idle_timeout_sec=0, watchdog_tick_sec=None, extra_env=None):
        proc = start_process(
            port,
            home,
            idle_timeout_sec=idle_timeout_sec,
            watchdog_tick_sec=watchdog_tick_sec,
            extra_env=extra_env,
        )
        processes.append(proc)
        bridge = BridgeProcess(port=port, home=home, process=proc)
        wait_for_health(bridge)
        return bridge

    try:
        yield start_bridge
    finally:
        for proc in reversed(processes):
            stop_process(proc)


@pytest.fixture
def bridge_server(tmp_path, free_port, bridge_factory):
    return bridge_factory(free_port, tmp_path)


def start_process(port, home, idle_timeout_sec=0, watchdog_tick_sec=None, extra_env=None):
    env = os.environ.copy()
    env["HOME"] = str(home)
    env["PYTHONUNBUFFERED"] = "1"
    if extra_env:
        env.update(extra_env)
    args = [
        sys.executable,
        str(BRIDGE_SCRIPT),
        "--host",
        HOST,
        "--port",
        str(port),
        "--idle-timeout-sec",
        str(idle_timeout_sec),
    ]
    if watchdog_tick_sec is not None:
        args.extend(["--watchdog-tick-sec", str(watchdog_tick_sec)])
    return subprocess.Popen(
        args,
        cwd=str(home),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def stop_process(proc):
    if proc.poll() is not None:
        return
    proc.send_signal(signal.SIGTERM)
    try:
        proc.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.communicate(timeout=5)


def wait_for_health(bridge):
    deadline = time.monotonic() + 5
    last_error = None
    while time.monotonic() < deadline:
        if bridge.process.poll() is not None:
            _, stderr = bridge.process.communicate(timeout=1)
            raise AssertionError(
                f"bridge exited before health check; code={bridge.process.returncode}; "
                f"stderr={stderr!r}"
            )
        try:
            response = bridge.get_json("/v1/health", timeout=0.5)
            if response.status == 200 and response.body.get("status") == "ok":
                return
        except OSError as exc:
            last_error = exc
        time.sleep(0.05)
    raise AssertionError(f"bridge did not become healthy on port {bridge.port}: {last_error!r}")


def request_json(port, method, path, payload=None, timeout=5):
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload, separators=(",", ":")).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        f"http://{HOST}:{port}{path}",
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return JsonResponse(
                status=response.status,
                body=json.loads(response.read().decode()),
            )
    except urllib.error.HTTPError as exc:
        body = exc.read().decode()
        return JsonResponse(status=exc.code, body=json.loads(body))
