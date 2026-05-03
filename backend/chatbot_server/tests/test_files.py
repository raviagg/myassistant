import base64
import io
import respx, httpx

SCALA_FILE_RESPONSE = {
    "filePath": "/data/files/2026/04/test.txt",
    "filename": "test.txt",
    "mimeType": "text/plain",
    "sizeBytes": 13,
}

def test_upload_file_proxies_to_scala(client):
    with respx.mock(base_url="http://localhost:8080") as mock:
        mock.post("/api/v1/files").mock(
            return_value=httpx.Response(201, json=SCALA_FILE_RESPONSE)
        )
        resp = client.post(
            "/api/files",
            files={"file": ("test.txt", io.BytesIO(b"hello, world!"), "text/plain")},
        )
    assert resp.status_code == 200
    data = resp.json()
    assert data["filePath"] == "/data/files/2026/04/test.txt"
    assert data["filename"] == "test.txt"

def test_upload_encodes_content_as_base64(client):
    content = b"hello, world!"
    captured = {}

    def capture(request, *args, **kwargs):
        captured["body"] = request.content
        return httpx.Response(201, json=SCALA_FILE_RESPONSE)

    with respx.mock(base_url="http://localhost:8080") as mock:
        mock.post("/api/v1/files").mock(side_effect=capture)
        client.post(
            "/api/files",
            files={"file": ("test.txt", io.BytesIO(content), "text/plain")},
        )
    import json
    body = json.loads(captured["body"])
    assert base64.b64decode(body["contentBase64"]) == content
