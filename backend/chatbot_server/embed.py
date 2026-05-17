from sentence_transformers import SentenceTransformer

_MODEL_NAME = "BAAI/bge-base-en-v1.5"
_model: SentenceTransformer | None = None


def embed(text: str) -> list[float]:
    global _model
    if _model is None:
        _model = SentenceTransformer(_MODEL_NAME)
    return _model.encode(text, normalize_embeddings=True).tolist()
