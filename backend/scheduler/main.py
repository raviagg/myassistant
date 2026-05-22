import time
import os
import httpx
from handlers.news_poll import NewsPollHandler
from handlers.plaid_poll import PlaidPollHandler

HTTP_SERVER_URL = os.environ["HTTP_SERVER_URL"]
AUTH_TOKEN = os.environ["AUTH_TOKEN"]
POLL_INTERVAL = 60  # seconds

# Source types dispatched via source_connections (not legacy scheduled_job).
_SOURCE_CONN_TYPES = {"plaid_poll", "news_poll"}


def build_handler_map(http: httpx.Client) -> dict:
    return {
        "news_poll":  NewsPollHandler(http),
        "plaid_poll": PlaidPollHandler(http),
    }


def _poll_source_connections(http: httpx.Client, handlers: dict) -> None:
    """Poll source_connections-based handlers (plaid_poll, others migrated)."""
    try:
        resp = http.get("/api/v1/source-connections/due")
        resp.raise_for_status()
        conns = resp.json().get("items", [])
        for conn in conns:
            source_type = conn["sourceType"]
            if source_type not in _SOURCE_CONN_TYPES:
                continue
            handler = handlers.get(source_type)
            if handler is None:
                continue
            print(f"[scheduler] running source_connection {conn['id']} ({source_type})")
            handler.run(conn)
    except Exception as e:
        print(f"[scheduler] source-connections poll error: {e}")


def _poll_scheduled_jobs(http: httpx.Client, handlers: dict) -> None:
    """Poll legacy scheduled_job-based handlers (not yet migrated to source_connections)."""
    try:
        resp = http.get("/api/v1/scheduled-jobs/due")
        resp.raise_for_status()
        jobs = resp.json().get("items", [])
        for job in jobs:
            source_type = job["sourceType"]
            if source_type in _SOURCE_CONN_TYPES:
                continue
            handler = handlers.get(source_type)
            if handler is None:
                continue
            print(f"[scheduler] running scheduled job {job['id']} ({source_type})")
            handler.run(job)
    except Exception as e:
        print(f"[scheduler] scheduled-jobs poll error: {e}")


def _poll_adhoc_runs(http: httpx.Client, handlers: dict) -> None:
    """Pick up pending adhoc sync_runs and execute them immediately."""
    try:
        resp = http.get("/api/v1/source-connections/adhoc-pending")
        resp.raise_for_status()
        items = resp.json().get("items", [])
        for item in items:
            conn        = item["connection"]
            run_id      = item["pendingRunId"]
            source_type = conn["sourceType"]
            if source_type not in _SOURCE_CONN_TYPES:
                continue
            handler = handlers.get(source_type)
            if handler is None:
                continue
            print(f"[scheduler] running adhoc {conn['id']} ({source_type}) run={run_id}")
            handler.run(conn, existing_run_id=run_id)
    except Exception as e:
        print(f"[scheduler] adhoc-runs poll error: {e}")


def run():
    http = httpx.Client(
        base_url=HTTP_SERVER_URL,
        headers={"Authorization": f"Bearer {AUTH_TOKEN}"},
        timeout=30,
    )
    handlers = build_handler_map(http)
    print(f"[scheduler] started — polling every {POLL_INTERVAL}s")
    while True:
        _poll_source_connections(http, handlers)
        _poll_scheduled_jobs(http, handlers)
        _poll_adhoc_runs(http, handlers)
        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    run()
