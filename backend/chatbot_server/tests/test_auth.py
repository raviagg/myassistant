import respx, httpx

PERSON_RESPONSE = {
    "id": "aaaaaaaa-0000-4000-a000-000000000001",
    "fullName": "Ravi Aggarwal",
    "preferredName": "Ravi",
    "gender": "male",
    "userIdentifier": "raaggarw",
    "createdAt": "2026-01-01T00:00:00Z",
    "updatedAt": "2026-01-01T00:00:00Z",
}

def test_login_known_username_returns_person(client):
    with respx.mock(base_url="http://localhost:8080") as mock:
        mock.get("/api/v1/persons").mock(
            return_value=httpx.Response(200, json={"items": [PERSON_RESPONSE], "total": 1})
        )
        resp = client.post("/api/login", json={"username": "raaggarw"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["personId"] == PERSON_RESPONSE["id"]
    assert data["displayName"] == "Ravi"
    assert data["fullName"] == "Ravi Aggarwal"

def test_login_unknown_username_returns_404(client):
    with respx.mock(base_url="http://localhost:8080") as mock:
        mock.get("/api/v1/persons").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0})
        )
        resp = client.post("/api/login", json={"username": "nobody"})
    assert resp.status_code == 404
    assert resp.json()["detail"]["error"] == "user_not_found"
