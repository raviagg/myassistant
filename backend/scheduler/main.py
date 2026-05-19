import time
import os
import httpx
from handlers.news_poll import NewsPollHandler
from handlers.plaid_poll import PlaidPollHandler

HTTP_SERVER_URL = os.environ["HTTP_SERVER_URL"]
AUTH_TOKEN = os.environ["AUTH_TOKEN"]
POLL_INTERVAL = 60  # seconds


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
            handler = handlers.get(conn["sourceType"])
            if handler is None:
                continue
            # Only route to handlers that have been migrated to source_connections.
            if conn["sourceType"] not in {"plaid_poll"}:
                continue
            print(f"[scheduler] running source_connection {conn['id']} ({conn['sourceType']})")
            handler.run(conn)
    except Exception as e:
        print(f"[scheduler] source-connections poll error: {e}")


def _poll_scheduled_jobs(http: httpx.Client, handlers: dict) -> None:
    """Poll legacy scheduled_job-based handlers (news_poll, not yet migrated)."""
    try:
        resp = http.get("/api/v1/scheduled-jobs/due")
        resp.raise_for_status()
        jobs = resp.json().get("items", [])
        for job in jobs:
            handler = handlers.get(job["sourceType"])
            if handler is None:
                continue
            # Skip handlers migrated to source_connections.
            if job["sourceType"] in {"plaid_poll"}:
                continue
            print(f"[scheduler] running scheduled job {job['id']} ({job['sourceType']})")
            handler.run(job)
    except Exception as e:
        print(f"[scheduler] scheduled-jobs poll error: {e}")


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
        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    run()
