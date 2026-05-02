from sentence_transformers import SentenceTransformer

_MODEL_NAME = "BAAI/bge-base-en-v1.5"
_model: SentenceTransformer | None = None


def _get_model() -> SentenceTransformer:
    global _model
    if _model is None:
        _model = SentenceTransformer(_MODEL_NAME)
    return _model


def embed(text: str) -> list[float]:
    """Return a 768-dim embedding for text using bge-base-en-v1.5."""
    return _get_model().encode(text, normalize_embeddings=True).tolist()
