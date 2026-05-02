#!/usr/bin/env python3
"""ClosePaw Termux bridge daemon."""
BRIDGE_VERSION = "1"
import argparse
import errno
import json
import os
import select
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
IDENTITY = "closepaw-bridge"
DEFAULT_HOST, DEFAULT_PORT = "127.0.0.1", 18422
DEFAULT_TIMEOUT_MS = MAX_TIMEOUT_MS = 120_000
DEFAULT_OUTPUT_BYTES = 65536
POLL_SEC = 0.25
exec_lock = threading.Lock()
class InvalidRequest(Exception): pass
class WorkspaceEscape(Exception): pass
def closepaw_dir():
    path = Path.home() / ".closepaw"
    path.mkdir(parents=True, exist_ok=True)
    return path
def workspace_root():
    raw = os.environ.get("CLOSEPAW_WORKSPACE") or "~/closepaw/workspace"
    path = Path(os.path.expanduser(raw)).resolve()
    path.mkdir(parents=True, exist_ok=True)
    return path
def port_in_use():
    print("port_in_use", file=sys.stderr)
    sys.exit(1)
def pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False
def wait_dead(pid, timeout_sec):
    deadline = time.monotonic() + timeout_sec
    while time.monotonic() < deadline:
        if not pid_alive(pid):
            return True
        time.sleep(0.05)
    return not pid_alive(pid)
def read_pidfile(pidfile):
    if not pidfile.exists():
        return {}
    try:
        data = json.loads(pidfile.read_text())
        return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        try:
            pidfile.unlink()
        except OSError:
            pass
        return {}
def health_is_bridge(port):
    if isinstance(port, bool) or not isinstance(port, int):
        return False
    try:
        url = f"http://127.0.0.1:{port}/v1/health"
        with urllib.request.urlopen(url, timeout=1) as response:
            data = json.loads(response.read().decode())
        return data.get("identity") == IDENTITY
    except (OSError, ValueError, json.JSONDecodeError, urllib.error.URLError):
        return False
def terminate_pid(pid):
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
        if not wait_dead(pid, 1):
            port_in_use()
def kill_old_bridge(pidfile):
    data = read_pidfile(pidfile)
    if not data:
        return
    pid = data.get("pid")
    if isinstance(pid, bool) or not isinstance(pid, int) or not pid_alive(pid):
        try:
            pidfile.unlink()
        except OSError:
            pass
        return
    if data.get("identity") != IDENTITY or not health_is_bridge(data.get("port")):
        port_in_use()
    terminate_pid(pid)
def write_pidfile(pidfile, port):
    payload = {"pid": os.getpid(), "port": port, "version": BRIDGE_VERSION,
               "identity": IDENTITY, "started_at": time.time()}
    pidfile.write_text(json.dumps(payload, separators=(",", ":")))
def remove_own_pidfile(pidfile):
    try:
        data = json.loads(pidfile.read_text())
        if data.get("pid") == os.getpid() and data.get("identity") == IDENTITY:
            pidfile.unlink()
    except (OSError, json.JSONDecodeError):
        pass
class Capture:
    def __init__(self, call_id, stream, max_bytes):
        self.stream = stream
        self.max_bytes = max_bytes
        self.visible = bytearray()
        self.truncated = False
        self.ref = None
        self._artifact = None
        self.call_id = call_id
    def append(self, chunk):
        if not chunk:
            return
        if not self.truncated and len(self.visible) + len(chunk) <= self.max_bytes:
            self.visible.extend(chunk)
            return
        if not self.truncated:
            self.truncated = True
            artifact_dir = closepaw_dir() / "artifacts"
            artifact_dir.mkdir(parents=True, exist_ok=True)
            self.ref = str(artifact_dir / f"{self.call_id}_{self.stream}")
            self._artifact = open(self.ref, "wb")
            self._artifact.write(self.visible)
        remaining = self.max_bytes - len(self.visible)
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
    except (ProcessLookupError, OSError):
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
def positive_int(value, default):
    if value is None:
        return default
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise InvalidRequest
    return value
def parse_timeout(value):
    return min(positive_int(value, DEFAULT_TIMEOUT_MS), MAX_TIMEOUT_MS)
def resolve_cwd(value):
    root = workspace_root()
    if value is None or value == "":
        return str(root)
    if not isinstance(value, str):
        raise InvalidRequest
    path = Path(os.path.expanduser(value)).resolve()
    if path != root and root not in path.parents:
        raise WorkspaceEscape
    if not path.is_dir():
        raise InvalidRequest
    return str(path)
def parse_env(value):
    merged = os.environ.copy()
    if value is None:
        return merged
    if not isinstance(value, dict):
        raise InvalidRequest
    for key, val in value.items():
        if not isinstance(key, str) or not isinstance(val, str):
            raise InvalidRequest
        merged[key] = val
    return merged
def parse_exec(payload):
    command = payload.get("command")
    if not isinstance(command, str):
        raise InvalidRequest
    return {
        "command": command,
        "cwd": resolve_cwd(payload.get("cwd")),
        "timeout_ms": parse_timeout(payload.get("timeout_ms")),
        "env": parse_env(payload.get("env")),
        "max_bytes": positive_int(payload.get("max_output_bytes"), DEFAULT_OUTPUT_BYTES),
    }
def run_command(request, spec):
    call_id = uuid.uuid4().hex
    stdout = Capture(call_id, "stdout", spec["max_bytes"])
    stderr = Capture(call_id, "stderr", spec["max_bytes"])
    start = time.monotonic()
    timed_out = False
    disconnected = False
    try:
        proc = subprocess.Popen(
            ["bash", "-c", spec["command"]],
            start_new_session=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=spec["cwd"],
            env=spec["env"],
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
        if int((time.monotonic() - start) * 1000) >= spec["timeout_ms"]:
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
    def __init__(self, address, handler, idle_timeout_sec, watchdog_tick_sec):
        super().__init__(address, handler)
        self.started_at = time.monotonic()
        self.last_request_at = self.started_at
        self.last_request_lock = threading.Lock()
        self.idle_timeout_sec = idle_timeout_sec
        self.watchdog_tick_sec = watchdog_tick_sec
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
        old_timeout = self.connection.gettimeout()
        self.connection.settimeout(5)
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 1024 * 1024:
                raise ValueError
            data = json.loads(self.rfile.read(length).decode())
        except (OSError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
            raise InvalidRequest
        finally:
            self.connection.settimeout(old_timeout)
        if not isinstance(data, dict):
            raise InvalidRequest
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
        try:
            spec = parse_exec(self.read_json())
        except WorkspaceEscape:
            self.send_json(400, {"error": "workspace_escape"})
            return
        except InvalidRequest:
            self.send_json(400, {"error": "invalid_request"})
            return
        if not exec_lock.acquire(blocking=False):
            self.send_json(409, {"error": "busy"})
            return
        try:
            status, body, disconnected = run_command(self, spec)
            if not disconnected:
                self.send_json(status, body)
        finally:
            exec_lock.release()
def watchdog(server):  # watchdog|idle
    while True:
        time.sleep(server.watchdog_tick_sec)
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
    parser.add_argument("--watchdog-tick-sec", type=float, default=60, help=argparse.SUPPRESS)
    return parser.parse_args()
def main():
    args = parse_args()
    pidfile = closepaw_dir() / "bridge.pid"
    kill_old_bridge(pidfile)
    try:
        server = BridgeServer((args.host, args.port), Handler,
                              args.idle_timeout_sec, args.watchdog_tick_sec)
    except OSError as exc:
        if exc.errno == errno.EADDRINUSE:
            print("port_in_use", file=sys.stderr)
        else:
            print(f"internal_error: {exc.strerror or exc}", file=sys.stderr)
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
