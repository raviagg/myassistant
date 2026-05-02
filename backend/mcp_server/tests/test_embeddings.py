from tools.embeddings import embed


def test_embed_returns_768_dim_list():
    result = embed("hello world")
    assert isinstance(result, list)
    assert len(result) == 768
    assert all(isinstance(v, float) for v in result)


def test_embed_different_texts_differ():
    a = embed("passport renewal")
    b = embed("health insurance premium")
    assert a != b


def test_embed_same_text_is_stable():
    assert embed("test") == embed("test")
