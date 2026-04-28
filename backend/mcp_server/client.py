import os
import httpx

def make_client() -> httpx.Client:
    base_url = os.environ.get("MYASSISTANT_BASE_URL", "http://localhost:8080")
    token = os.environ.get("MYASSISTANT_AUTH_TOKEN", "dev-token-change-me-in-production")
    return httpx.Client(
        base_url=base_url,
        headers={"Authorization": f"Bearer {token}"},
        timeout=30.0,
    )

def _check(resp: httpx.Response) -> None:
    if resp.is_client_error:
        raise ValueError(f"{resp.status_code}: {resp.text}")
    if resp.is_server_error:
        raise RuntimeError(f"{resp.status_code}: {resp.text}")
