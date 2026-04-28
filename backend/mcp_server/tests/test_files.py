import base64
import json
import pytest
import respx
import httpx
from tools.files import save_file, extract_text_from_file, get_file, delete_file


def test_save_file(http):
    content = b"hello file"
    b64 = base64.b64encode(content).decode()
    with respx.mock:
        respx.post("http://testserver/api/v1/files").mock(
            return_value=httpx.Response(201, json={"filePath": "files/abc.txt", "originalName": "test.txt"})
        )
        result = save_file(http, content_base64=b64, filename="test.txt", mime_type="text/plain")
        req = respx.calls[0].request
        assert req.content == content
        assert "fileName=test.txt" in str(req.url)
        assert result["filePath"] == "files/abc.txt"


def test_save_file_no_owner_params(http):
    b64 = base64.b64encode(b"data").decode()
    with respx.mock:
        respx.post("http://testserver/api/v1/files").mock(
            return_value=httpx.Response(201, json={"filePath": "files/x.bin"})
        )
        save_file(http, content_base64=b64, filename="x.bin")
        url = str(respx.calls[0].request.url)
        assert "personId" not in url
        assert "householdId" not in url


def test_extract_text_from_file(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/files/extract-text").mock(
            return_value=httpx.Response(200, json={"text": "hello", "extractionMethod": "pdf_parser"})
        )
        result = extract_text_from_file(http, file_path="files/abc.pdf")
        body = json.loads(respx.calls[0].request.content)
        assert body == {"filePath": "files/abc.pdf"}
        assert result["text"] == "hello"


def test_get_file(http):
    raw = b"file bytes"
    with respx.mock:
        respx.get("http://testserver/api/v1/files").mock(
            return_value=httpx.Response(200, content=raw,
                                        headers={"content-type": "application/octet-stream"})
        )
        result = get_file(http, file_path="files/abc.txt")
        url = str(respx.calls[0].request.url)
        assert "path=" in url
        assert result["content_base64"] == base64.b64encode(raw).decode()


def test_delete_file(http):
    with respx.mock:
        respx.delete("http://testserver/api/v1/files").mock(
            return_value=httpx.Response(204)
        )
        result = delete_file(http, file_path="files/abc.txt")
        assert "path=" in str(respx.calls[0].request.url)
        assert result == {}


def test_save_file_raises_on_400(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/files").mock(
            return_value=httpx.Response(400, json={"error": "bad_request"})
        )
        with pytest.raises(ValueError, match="400"):
            save_file(http, content_base64="aGVsbG8=", filename="test.txt")
