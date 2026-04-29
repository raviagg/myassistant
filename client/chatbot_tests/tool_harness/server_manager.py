import glob
import os
import pathlib
import subprocess
import sys
import time
from contextlib import contextmanager

import httpx

_DEFAULT_PORT   = 8181
_DEFAULT_TOKEN  = "dev-token-change-me-in-production"
_DEFAULT_DB     = "myassistant"
_HEALTH_TIMEOUT = 30


def auth_token() -> str:
    return os.environ.get("CHATBOT_AUTH_TOKEN", _DEFAULT_TOKEN)


def _find_jar() -> str:
    repo_root = pathlib.Path(__file__).parents[3]
    pattern   = str(repo_root / "backend" / "http_server" / "target" / "scala-3.4.2"
                    / "myassistant-backend-assembly-*.jar")
    matches   = glob.glob(pattern)
    if not matches:
        print(
            f"\nERROR: http_server fat JAR not found at {pattern}\n"
            "Build it first:\n"
            "  cd backend/http_server && sbt assembly\n",
            file=sys.stderr,
        )
        sys.exit(1)
    return matches[0]


def _wait_for_health(url: str, timeout: int = _HEALTH_TIMEOUT) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            r = httpx.get(f"{url}/health", timeout=2.0)
            if r.status_code == 200:
                return
        except Exception:
            pass
        time.sleep(0.5)
    raise RuntimeError(f"http_server did not become healthy at {url} within {timeout}s")


@contextmanager
def managed_server():
    """
    Context manager that yields the http_server base URL.

    If CHATBOT_HTTP_URL is set  -> yield it directly (user manages the server).
    If CHATBOT_HTTP_URL is unset -> start the fat JAR as a subprocess on port 8181,
                                    wait for /health, yield URL, then kill on exit.
    """
    explicit_url = os.environ.get("CHATBOT_HTTP_URL")
    if explicit_url:
        yield explicit_url
        return

    db   = os.environ.get("CHATBOT_DB", _DEFAULT_DB)
    jar  = _find_jar()
    url  = f"http://localhost:{_DEFAULT_PORT}"
    env  = {
        **os.environ,
        "DB_URL":      f"jdbc:postgresql://localhost:5432/{db}",
        "DB_USER":     os.environ.get("DB_USER", "myassistant"),
        "DB_PASSWORD": os.environ.get("DB_PASSWORD", "changeme"),
        "AUTH_TOKEN":  auth_token(),
        "SERVER_PORT": str(_DEFAULT_PORT),
    }
    print(f"\n[server_manager] Starting http_server on port {_DEFAULT_PORT} (db={db})...")
    proc = subprocess.Popen(
        ["java", "-jar", jar],
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        _wait_for_health(url)
        print(f"[server_manager] http_server ready at {url}")
        yield url
    finally:
        print("\n[server_manager] Stopping http_server...")
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
