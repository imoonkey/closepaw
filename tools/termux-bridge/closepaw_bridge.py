#!/usr/bin/env python3
"""ClosePaw Termux bridge daemon."""
BRIDGE_VERSION = "1"

import argparse
import json
import os
import select
import signal
import socket
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

IDENTITY = "closepaw-bridge"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 18422
DEFAULT_TIMEOUT_MS = 120_000
MAX_TIMEOUT_MS = 120_000
OUTPUT_CAP_BYTES = 256 * 1024
POLL_SEC = 0.25
exec_lock = threading.Lock()

def closepaw_dir():
    path = Path.home() / ".closepaw"
    path.mkdir(parents=True, exist_ok=True)
    return path


def workspace_dir():
    path = Path(os.path.expanduser("~/closepaw/workspace"))
    path.mkdir(parents=True, exist_ok=True)
    return str(path)


def port_in_use():
    print("port_in_use", file=sys.stderr)
    sys.exit(1)


def pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def wait_dead(pid, timeout_sec):
    deadline = time.monotonic() + timeout_sec
    while time.monotonic() < deadline:
        if not pid_alive(pid):
            return True
        time.sleep(0.05)
    return not pid_alive(pid)


def kill_old_bridge(pidfile):
    if not pidfile.exists():
        return
    try:
        data = json.loads(pidfile.read_text())
    except (OSError, json.JSONDecodeError):
        port_in_use()
    if data.get("identity") != IDENTITY:
        port_in_use()

    pid = data.get("pid")
    if not isinstance(pid, int) or pid == os.getpid() or not pid_alive(pid):
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    except OSError:
        port_in_use()
    if not wait_dead(pid, 2):
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        wait_dead(pid, 1)


def write_pidfile(pidfile, port):
    payload = {
        "pid": os.getpid(),
        "port": port,
        "version": BRIDGE_VERSION,
        "identity": IDENTITY,
        "started_at": time.time(),
    }
    pidfile.write_text(json.dumps(payload, separators=(",", ":")))


def remove_own_pidfile(pidfile):
    try:
        data = json.loads(pidfile.read_text())
        if data.get("pid") == os.getpid() and data.get("identity") == IDENTITY:
            pidfile.unlink()
    except (OSError, json.JSONDecodeError):
        pass


class Capture:
    def __init__(self, call_id, stream):
        self.call_id = call_id
        self.stream = stream
        self.visible = bytearray()
        self.truncated = False
        self.ref = None
        self._artifact = None

    def append(self, chunk):
        if not chunk:
            return
        if not self.truncated and len(self.visible) + len(chunk) <= OUTPUT_CAP_BYTES:
            self.visible.extend(chunk)
            return
        if not self.truncated:
            self.truncated = True
            artifact_dir = closepaw_dir() / "artifacts"
            artifact_dir.mkdir(parents=True, exist_ok=True)
            self.ref = str(artifact_dir / f"{self.call_id}.{self.stream}.txt")
            self._artifact = open(self.ref, "wb")
            self._artifact.write(self.visible)
        remaining = OUTPUT_CAP_BYTES - len(self.visible)
        if remaining > 0:
            self.visible.extend(chunk[:remaining])
        self._artifact.write(chunk)

    def close(self):
        if self._artifact:
            self._artifact.close()

    def text(self):
        return bytes(self.visible).decode(errors="replace")


def drain(pipe, capture):
    try:
        for chunk in iter(lambda: pipe.read(4096), b""):
            capture.append(chunk)
    finally:
        capture.close()
        pipe.close()


def client_connected(conn):
    try:
        readable, _, _ = select.select([conn], [], [], 0)
        if not readable:
            return True
        return bool(conn.recv(1, socket.MSG_PEEK))
    except BlockingIOError:
        return True
    except OSError:
        return False


def kill_process_group(proc, graceful):
    try:
        pgid = os.getpgid(proc.pid)
        os.killpg(pgid, signal.SIGTERM if graceful else signal.SIGKILL)
    except ProcessLookupError:
        return
    except OSError:
        return
    if not graceful:
        return
    try:
        proc.wait(timeout=2)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(pgid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def parse_timeout(value):
    if value is None:
        return DEFAULT_TIMEOUT_MS
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError
    return min(value, MAX_TIMEOUT_MS)


def resolve_cwd(value):
    if value is None or value == "":
        return workspace_dir()
    if not isinstance(value, str):
        raise ValueError
    path = os.path.expanduser(value)
    if not os.path.isdir(path):
        raise ValueError
    return path


def run_command(request, payload):
    command = payload.get("command")
    if not isinstance(command, str):
        return 400, {"error": "invalid_request"}, False
    try:
        cwd = resolve_cwd(payload.get("cwd"))
        timeout_ms = parse_timeout(payload.get("timeout_ms"))
    except ValueError:
        return 400, {"error": "invalid_request"}, False

    call_id = uuid.uuid4().hex
    stdout = Capture(call_id, "stdout")
    stderr = Capture(call_id, "stderr")
    start = time.monotonic()
    timed_out = False
    disconnected = False
    try:
        proc = subprocess.Popen(
            ["bash", "-c", command],
            start_new_session=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=cwd,
        )
    except OSError:
        return 500, {"error": "exec_failed"}, False

    threads = [
        threading.Thread(target=drain, args=(proc.stdout, stdout), daemon=True),
        threading.Thread(target=drain, args=(proc.stderr, stderr), daemon=True),
    ]
    for thread in threads:
        thread.start()

    while proc.poll() is None:
        if int((time.monotonic() - start) * 1000) >= timeout_ms:
            timed_out = True
            kill_process_group(proc, graceful=True)
            break
        if not client_connected(request.connection):
            disconnected = True
            kill_process_group(proc, graceful=False)
            break
        time.sleep(POLL_SEC)

    try:
        proc.wait(timeout=1)
    except subprocess.TimeoutExpired:
        kill_process_group(proc, graceful=False)
        proc.wait()
    for thread in threads:
        thread.join()

    return 200, {
        "exit_code": None if timed_out else proc.returncode,
        "stdout": stdout.text(),
        "stderr": stderr.text(),
        "stdout_truncated": stdout.truncated,
        "stderr_truncated": stderr.truncated,
        "stdout_ref": stdout.ref,
        "stderr_ref": stderr.ref,
        "timed_out": timed_out,
        "duration_ms": int((time.monotonic() - start) * 1000),
    }, disconnected


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, handler, idle_timeout_sec):
        super().__init__(address, handler)
        self.started_at = time.monotonic()
        self.last_request_at = self.started_at
        self.last_request_lock = threading.Lock()
        self.idle_timeout_sec = idle_timeout_sec

    def touch_request(self):
        with self.last_request_lock:
            self.last_request_at = time.monotonic()

    def uptime_ms(self):
        return int((time.monotonic() - self.started_at) * 1000)

    def last_request_ms_ago(self):
        with self.last_request_lock:
            return int((time.monotonic() - self.last_request_at) * 1000)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        return

    def send_error(self, code, message=None, explain=None):
        self.send_json(code, {"error": "http_error"})

    def send_json(self, status, body):
        data = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def read_json(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 1024 * 1024:
                raise ValueError
            data = json.loads(self.rfile.read(length).decode())
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
            raise ValueError
        if not isinstance(data, dict):
            raise ValueError
        return data

    def do_GET(self):
        self.server.touch_request()
        if self.path.split("?", 1)[0] != "/v1/health":
            self.send_json(404, {"error": "not_found"})
            return
        self.send_json(200, {
            "status": "ok",
            "version": BRIDGE_VERSION,
            "identity": IDENTITY,
            "uptime_ms": self.server.uptime_ms(),
            "last_request_ms_ago": self.server.last_request_ms_ago(),
        })

    def do_POST(self):
        self.server.touch_request()
        if self.path.split("?", 1)[0] != "/v1/exec":
            self.send_json(404, {"error": "not_found"})
            return
        if not exec_lock.acquire(blocking=False):
            self.send_json(409, {"error": "busy"})
            return
        try:
            try:
                payload = self.read_json()
            except ValueError:
                self.send_json(400, {"error": "invalid_request"})
                return
            status, body, disconnected = run_command(self, payload)
            if not disconnected:
                self.send_json(status, body)
        finally:
            exec_lock.release()


def watchdog(server):  # watchdog|idle
    while True:
        time.sleep(60)
        if server.idle_timeout_sec <= 0:
            continue
        with server.last_request_lock:
            idle = time.monotonic() - server.last_request_at
        if idle > server.idle_timeout_sec:
            server.shutdown()
            return


def parse_args():
    parser = argparse.ArgumentParser(description="ClosePaw Termux bridge daemon")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--idle-timeout-sec", type=int, default=1800)
    return parser.parse_args()

def main():
    args = parse_args()
    pidfile = closepaw_dir() / "bridge.pid"
    kill_old_bridge(pidfile)
    try:
        server = BridgeServer((args.host, args.port), Handler, args.idle_timeout_sec)
    except OSError:
        print("port_in_use", file=sys.stderr)
        return 1
    write_pidfile(pidfile, args.port)
    threading.Thread(target=watchdog, args=(server,), daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        remove_own_pidfile(pidfile)
    return 0

if __name__ == "__main__":
    sys.exit(main())
