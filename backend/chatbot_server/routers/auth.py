import os
import httpx
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

router = APIRouter()

_BASE_URL   = os.environ.get("CHATBOT_HTTP_URL", "http://localhost:8080")
_AUTH_TOKEN = os.environ.get("CHATBOT_AUTH_TOKEN", "dev-token-change-me-in-production")


class LoginRequest(BaseModel):
    username: str


class LoginResponse(BaseModel):
    personId: str
    displayName: str
    fullName: str


@router.post("/api/login", response_model=LoginResponse)
def login(body: LoginRequest):
    with httpx.Client(base_url=_BASE_URL, headers={"Authorization": f"Bearer {_AUTH_TOKEN}"}) as http:
        resp = http.get("/api/v1/persons", params={"userIdentifier": body.username})
        resp.raise_for_status()
        items = resp.json().get("items", [])

    if not items:
        raise HTTPException(
            status_code=404,
            detail={"error": "user_not_found", "message": f"No person with username '{body.username}'"}
        )

    person = items[0]
    display = person.get("preferredName") or person.get("fullName") or body.username
    return LoginResponse(personId=person["id"], displayName=display, fullName=person["fullName"])
