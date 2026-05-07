import base64
import mimetypes
import os
import httpx
from fastapi import APIRouter, UploadFile, File, HTTPException, Query
from fastapi.responses import Response
from pydantic import BaseModel

router = APIRouter()

_BASE_URL   = os.environ.get("CHATBOT_HTTP_URL", "http://localhost:8080")
_AUTH_TOKEN = os.environ.get("CHATBOT_AUTH_TOKEN", "dev-token-change-me-in-production")


class FileUploadResponse(BaseModel):
    filePath: str
    filename: str
    mimeType: str


@router.get("/api/download")
async def download_file(path: str = Query(...)):
    with httpx.Client(base_url=_BASE_URL, headers={"Authorization": f"Bearer {_AUTH_TOKEN}"}) as http:
        resp = http.get("/api/v1/files", params={"path": path})
    if not resp.is_success:
        raise HTTPException(status_code=resp.status_code, detail=resp.text)
    data = resp.json()
    raw = base64.b64decode(data["contentBase64"])
    filename = data.get("filename", path.split("/")[-1])
    mime = mimetypes.guess_type(filename)[0] or data.get("mimeType", "application/octet-stream")
    return Response(
        content=raw,
        media_type=mime,
        headers={"Content-Disposition": f'inline; filename="{filename}"'},
    )


@router.post("/api/files", response_model=FileUploadResponse)
async def upload_file(file: UploadFile = File(...)):
    content = await file.read()
    body = {
        "contentBase64": base64.b64encode(content).decode(),
        "filename":      file.filename,
        "mimeType":      file.content_type or "application/octet-stream",
    }
    with httpx.Client(base_url=_BASE_URL, headers={"Authorization": f"Bearer {_AUTH_TOKEN}"}) as http:
        resp = http.post("/api/v1/files", json=body)
    if resp.status_code not in (200, 201):
        raise HTTPException(status_code=resp.status_code, detail=resp.text)
    data = resp.json()
    return FileUploadResponse(
        filePath=data["filePath"],
        filename=data["filename"],
        mimeType=data["mimeType"],
    )
