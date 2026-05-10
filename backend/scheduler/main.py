import time
import os
import httpx
from handlers.news_poll import NewsPollHandler

HTTP_SERVER_URL = os.environ["HTTP_SERVER_URL"]
AUTH_TOKEN = os.environ["AUTH_TOKEN"]
POLL_INTERVAL = 60  # seconds


def build_handler_map(http: httpx.Client) -> dict:
    return {
        "news_poll": NewsPollHandler(http),
    }


def run():
    http = httpx.Client(
        base_url=HTTP_SERVER_URL,
        headers={"Authorization": f"Bearer {AUTH_TOKEN}"},
        timeout=30,
    )
    handlers = build_handler_map(http)
    print(f"[scheduler] started — polling every {POLL_INTERVAL}s")
    while True:
        try:
            resp = http.get("/api/v1/scheduled-jobs/due")
            resp.raise_for_status()
            jobs = resp.json().get("items", [])
            for job in jobs:
                handler = handlers.get(job["sourceType"])
                if handler:
                    print(f"[scheduler] running job {job['id']} ({job['sourceType']})")
                    handler.run(job)
        except Exception as e:
            print(f"[scheduler] poll error: {e}")
        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    run()
