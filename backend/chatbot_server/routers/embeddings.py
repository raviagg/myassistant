from fastapi import APIRouter
from pydantic import BaseModel

from embed import embed as _embed

router = APIRouter()


class EmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    embedding: list[float]


@router.post("/api/embed", response_model=EmbedResponse)
def embed_text(req: EmbedRequest) -> EmbedResponse:
    return EmbedResponse(embedding=_embed(req.text))
