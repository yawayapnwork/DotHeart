#!/usr/bin/env python3
"""
test_pipeline.py - one-command end-to-end smoke test for the DotHeart
pipeline: (optionally) starts the local FastAPI backend, generates a
transparent pixel-art heart sprite, pushes it through the real
scripts/push_art CLI, then verifies GET /api/v1/widget/current and
GET /static/{filename} return consistent, correct data.

Usage:
    python scripts/test_pipeline.py
    python scripts/test_pipeline.py --port 8123 --size 16
    python scripts/test_pipeline.py --server-url http://127.0.0.1:8000 --no-start-server

By default this script:
  1. Checks whether --server-url is already serving GET /health.
  2. If not (and the URL points at localhost/127.0.0.1), starts its own
     `uvicorn app.main:app` subprocess against a throwaway temp DATA_DIR
     and a freshly generated WIDGET_TOKEN, and tears it down at the end -
     so a first-time run needs no manual setup at all.
  3. Generates a heart PNG via heart_sprite.generate_heart_png().
  4. Shells out to the real scripts/push_art (not a reimplementation of
     its upload logic) so this test exercises the exact tool a user runs.
  5. Verifies the server's response against locally recomputed truth:
     checksum, PNG magic bytes, and full byte-for-byte content equality.

Prints a PASS/FAIL line per check and exits 0 only if every check passed.
Zero external dependencies: stdlib only (urllib, subprocess, hashlib,
socket, secrets).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))
from heart_sprite import generate_heart_png  # noqa: E402  (needs sys.path insert above)

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class CheckFailure(Exception):
    """Raised by a check_* helper; caught by main() to report a clean FAIL."""


def _print_step(label: str) -> None:
    print(f"\n== {label} ==")


def _pass(label: str) -> None:
    print(f"[PASS] {label}")


def _fail(label: str, detail: str) -> None:
    print(f"[FAIL] {label}: {detail}")


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_for_health(base_url: str, timeout_seconds: float) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"{base_url}/health", timeout=2) as resp:
                if resp.status == 200:
                    return True
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.3)
    return False


def start_backend(port: int, token: str, data_dir: Path) -> subprocess.Popen:
    env_cmd = [
        sys.executable,
        "-m",
        "uvicorn",
        "app.main:app",
        "--host",
        "127.0.0.1",
        "--port",
        str(port),
    ]
    env = dict(os.environ)
    env["WIDGET_TOKEN"] = token
    env["DATA_DIR"] = str(data_dir)
    env["LOG_LEVEL"] = "WARNING"
    return subprocess.Popen(
        env_cmd,
        cwd=str(REPO_ROOT),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )


def run_push_art(server_url: str, token: str, image_path: Path, message: str) -> tuple[int, str]:
    env = dict(os.environ)
    env["DOTHEART_SERVER_URL"] = server_url
    env["DOTHEART_TOKEN"] = token
    result = subprocess.run(
        [sys.executable, str(SCRIPT_DIR / "push_art"), str(image_path), message],
        cwd=str(REPO_ROOT),
        env=env,
        capture_output=True,
        text=True,
        timeout=90,
    )
    output = result.stdout + result.stderr
    return result.returncode, output


def http_get(url: str) -> tuple[int, bytes, dict[str, str]]:
    """Returns (status, body, headers). Header keys are lowercased in the
    returned dict - uvicorn/Starlette send lowercase header names (e.g.
    "cache-control", not "Cache-Control"), and a plain dict() built from
    response.headers.items() loses the case-insensitive lookup that
    email.message.Message (the underlying type) natively provides. Always
    look up headers here with a lowercase key.
    """
    request = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            headers = {k.lower(): v for k, v in response.headers.items()}
            return response.status, response.read(), headers
    except urllib.error.HTTPError as e:
        headers = {k.lower(): v for k, v in e.headers.items()} if e.headers else {}
        return e.code, e.read(), headers


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="test_pipeline",
        description="End-to-end smoke test for the DotHeart backend + push_art pipeline.",
    )
    parser.add_argument(
        "--server-url",
        default=None,
        help="Backend base URL to test against (default: auto - reuse if already running on "
        "127.0.0.1:8000, else start a throwaway instance on a free port).",
    )
    parser.add_argument(
        "--no-start-server",
        action="store_true",
        help="Never start a backend subprocess; fail if --server-url isn't already reachable.",
    )
    parser.add_argument("--port", type=int, default=None, help="Port to start a throwaway backend on.")
    parser.add_argument("--size", type=int, default=32, choices=[16, 32], help="Test sprite size.")
    parser.add_argument(
        "--message", default="pipeline smoke test", help="Status message to push with the sprite."
    )
    args = parser.parse_args(argv)

    failures = 0
    server_process: Optional[subprocess.Popen] = None
    temp_data_dir: Optional[Path] = None
    sprite_dir: Optional[Path] = None
    token = secrets.token_urlsafe(24)

    try:
        # -------------------------------------------------------------
        # Step 1: resolve a running backend, or start a throwaway one.
        # -------------------------------------------------------------
        _print_step("Backend availability")

        candidate_url = args.server_url or "http://127.0.0.1:8000"
        already_running = wait_for_health(candidate_url, timeout_seconds=1.5)

        if already_running:
            server_url = candidate_url
            _pass(f"Reusing already-running backend at {server_url}")
            env_token = os.environ.get("DOTHEART_TOKEN", "")
            if not env_token:
                _fail(
                    "Backend availability",
                    "reusing an already-running backend requires its WIDGET_TOKEN value to be "
                    "set as DOTHEART_TOKEN in this shell's environment (this script cannot "
                    "know a token it didn't generate itself).",
                )
                return 1
            token = env_token
            print("  Using DOTHEART_TOKEN from the environment for this already-running instance.")
        elif args.no_start_server:
            _fail("Backend availability", f"{candidate_url} is not reachable and --no-start-server was set")
            return 1
        else:
            port = args.port or find_free_port()
            server_url = f"http://127.0.0.1:{port}"
            temp_data_dir = Path(tempfile.mkdtemp(prefix="dotheart_pipeline_test_"))
            print(f"  Starting throwaway backend on {server_url} (DATA_DIR={temp_data_dir})")
            server_process = start_backend(port, token, temp_data_dir)
            if not wait_for_health(server_url, timeout_seconds=20):
                out = ""
                if server_process.stdout:
                    out = server_process.stdout.read()
                _fail("Backend availability", f"server did not become healthy within 20s.\n{out}")
                return 1
            _pass(f"Started backend at {server_url}")

        # -------------------------------------------------------------
        # Step 2: generate the test sprite.
        # -------------------------------------------------------------
        _print_step("Sprite generation")
        sprite_bytes = generate_heart_png(args.size)
        expected_checksum = hashlib.sha256(sprite_bytes).hexdigest()
        sprite_dir = Path(tempfile.mkdtemp(prefix="dotheart_sprite_"))
        sprite_path = sprite_dir / f"heart_{args.size}.png"
        sprite_path.write_bytes(sprite_bytes)
        _pass(f"Generated {len(sprite_bytes)}-byte {args.size}x{args.size} heart PNG (checksum {expected_checksum[:12]}...)")

        # -------------------------------------------------------------
        # Step 3: push it through the real push_art CLI.
        # -------------------------------------------------------------
        _print_step("push_art upload")
        rc, output = run_push_art(server_url, token, sprite_path, args.message)
        print("\n".join(f"  {line}" for line in output.splitlines()))
        if rc != 0:
            _fail("push_art upload", f"exited with code {rc}")
            failures += 1
        else:
            _pass("push_art exited 0")

        # -------------------------------------------------------------
        # Step 4: verify GET /api/v1/widget/current.
        # -------------------------------------------------------------
        _print_step("GET /api/v1/widget/current")
        status, body, _headers = http_get(f"{server_url}/api/v1/widget/current")
        if status != 200:
            _fail("current endpoint status", f"expected 200, got {status}: {body[:200]!r}")
            failures += 1
            payload = {}
        else:
            _pass("current endpoint returned HTTP 200")
            try:
                payload = json.loads(body.decode("utf-8"))
            except json.JSONDecodeError as e:
                _fail("current endpoint JSON", str(e))
                failures += 1
                payload = {}

        if payload:
            if payload.get("checksum") == expected_checksum:
                _pass(f"checksum matches locally computed sha256 ({expected_checksum[:12]}...)")
            else:
                _fail(
                    "checksum mismatch",
                    f"server returned {payload.get('checksum')!r}, expected {expected_checksum!r}",
                )
                failures += 1

            if payload.get("message") == args.message:
                _pass("message round-tripped correctly")
            else:
                _fail("message mismatch", f"server returned {payload.get('message')!r}")
                failures += 1

            image_url = payload.get("image_url")
            if not image_url:
                _fail("image_url present", "missing from response")
                failures += 1
                image_url = None
        else:
            image_url = None

        # -------------------------------------------------------------
        # Step 5: verify GET /static/{filename} - status, PNG magic
        # bytes, and full byte-for-byte content equality against what
        # was uploaded (validates the backend's atomic-write path
        # preserved the exact bytes, per WORKFLOW.md's storage invariant).
        # -------------------------------------------------------------
        _print_step("GET /static/{filename}")
        if image_url:
            static_status, static_body, static_headers = http_get(f"{server_url}{image_url}")
            if static_status != 200:
                _fail("static endpoint status", f"expected 200, got {static_status}")
                failures += 1
            else:
                _pass("static endpoint returned HTTP 200")

            if static_body[:8] == PNG_SIGNATURE:
                _pass("response has a valid PNG magic-byte header")
            else:
                _fail("PNG magic bytes", f"got {static_body[:8]!r}")
                failures += 1

            if static_body == sprite_bytes:
                _pass("served bytes are byte-for-byte identical to the uploaded sprite")
            else:
                _fail(
                    "byte-for-byte equality",
                    f"served {len(static_body)} bytes, uploaded {len(sprite_bytes)} bytes - differ",
                )
                failures += 1

            cache_control = static_headers.get("cache-control", "")
            if "no-cache" in cache_control or "must-revalidate" in cache_control:
                _pass(f"Cache-Control header present: {cache_control!r}")
            else:
                _fail("Cache-Control header", f"unexpected value {cache_control!r}")
                failures += 1
        else:
            _fail("static endpoint", "skipped - no image_url from the previous step")
            failures += 1

    finally:
        if server_process is not None:
            server_process.terminate()
            try:
                server_process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server_process.kill()
                server_process.wait(timeout=5)
        if temp_data_dir is not None:
            shutil.rmtree(temp_data_dir, ignore_errors=True)
        if sprite_dir is not None:
            shutil.rmtree(sprite_dir, ignore_errors=True)

    print("\n" + "=" * 60)
    if failures == 0:
        print("ALL CHECKS PASSED")
        return 0
    print(f"{failures} CHECK(S) FAILED")
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        print("\nAborted.")
        sys.exit(130)
