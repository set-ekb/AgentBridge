#!/usr/bin/env python3
"""User-invoked Termux startup; no model request, shell, key rotation or task replay."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

from opencode_bridge_gateway import CONFIG, private_file, sanitized_status


class StartupError(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def gateway_status(token):
    request = urllib.request.Request('http://127.0.0.1:4097/status',
                                     headers={'Authorization': 'Bearer ' + token})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=2) as response:
            body = json.loads(response.read(16385))
        return (body.get('gateway') == 'agentbridge-opencode'
                and body.get('mode') == 'read_only'
                and body.get('healthy') is True)
    except (OSError, ValueError):
        return False


def occupied(port):
    with socket.socket() as probe:
        probe.settimeout(0.5)
        return probe.connect_ex(('127.0.0.1', port)) == 0


def spawn(command, env, log_path, workdir):
    fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'ab') as log:
        return subprocess.Popen(command, env=env, cwd=workdir, stdin=subprocess.DEVNULL,
                                stdout=log, stderr=log, start_new_session=True)


def wait_ready(check, process, label, timeout=35):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise StartupError(label + ' exited; see private startup logs in Termux')
        if check():
            return
        time.sleep(0.5)
    raise StartupError(label + ' readiness timeout; see private startup logs in Termux')


def start(enable_tasks=False, supervise=False):
    os.umask(0o077)
    CONFIG.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Lock is released automatically on exit; never infer ownership from a stale PID.
    fd = os.open(CONFIG / 'startup.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise StartupError('Startup already in progress; check connection shortly')
        password = private_file(CONFIG / 'password')
        token = private_file(CONFIG / 'pairing-token', create=True)
        here = Path(__file__).resolve().parent
        profile = here / 'opencode_mcp_profile.json'
        gateway = here / 'opencode_bridge_gateway.py'
        if not profile.is_file() or not gateway.is_file():
            raise StartupError('Install the launcher, gateway, task bridge and MCP profile together')
        env = dict(os.environ, OPENCODE_SERVER_PASSWORD=password,
                   AGENTBRIDGE_MCP_TOKEN=token, OPENCODE_CONFIG=str(profile))
        started = []
        try:
            if not sanitized_status(4096, password)['healthy']:
                if occupied(4096):
                    raise StartupError('Port 4096 is occupied by an unverified server; it was not changed')
                binary = shutil.which('opencode')
                if not binary:
                    raise StartupError('OpenCode is not installed or not in Termux PATH')
                server = spawn([binary, 'serve', '--hostname', '127.0.0.1', '--port', '4096'],
                               env, CONFIG / 'opencode-startup.log', here)
                started.append(server)
                wait_ready(lambda: sanitized_status(4096, password)['healthy'], server, 'OpenCode')
            if not gateway_status(token):
                if occupied(4097):
                    raise StartupError('Port 4097 is occupied by an unverified gateway; it was not changed')
                command = [sys.executable, str(gateway)]
                if enable_tasks:
                    command.append('--enable-tasks')
                bridge = spawn(command, env, CONFIG / 'gateway-startup.log', here)
                started.append(bridge)
                wait_ready(lambda: gateway_status(token), bridge, 'Gateway')
        except BaseException:
            # Clean up only processes created by this attempt; leave existing sessions alone.
            for process in reversed(started):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=3)
            raise
        print('OpenCode and paired gateway are reachable. No task was submitted.')
        print('Existing processes were reused; their MCP configuration and task mode were not changed.')
        if supervise:
            # Keep a Termux-managed background command alive rather than leaving only
            # orphan children after the short launcher exits. Never replay tasks/restart.
            while True:
                time.sleep(15)
                if any(process.poll() is not None for process in started):
                    raise StartupError('A started process exited; check Termux before starting again')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--enable-tasks', action='store_true')
    parser.add_argument('--supervise', action='store_true', help='Keep the Termux background command alive; no automatic restart')
    args = parser.parse_args()
    try:
        start(args.enable_tasks, args.supervise)
    except Exception as exc:
        # Do not print raw exception messages which may contain credentials/HTTP details.
        print(str(exc) if isinstance(exc, StartupError) else
              'Startup failed; check the local files, permissions and private logs in Termux', file=sys.stderr)
        sys.exit(1)
