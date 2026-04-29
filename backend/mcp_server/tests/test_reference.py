import pytest
import respx
import httpx
from tools.reference import list_domains, list_source_types, list_kinship_aliases


def test_list_domains(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/reference/domains").mock(
            return_value=httpx.Response(200, json=[{"id": "d1", "name": "health"}])
        )
        result = list_domains(http)
        assert result[0]["name"] == "health"


def test_list_source_types(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/reference/source-types").mock(
            return_value=httpx.Response(200, json=[{"id": "st1", "name": "user_input"}])
        )
        result = list_source_types(http)
        assert result[0]["name"] == "user_input"


def test_list_kinship_aliases_no_filter(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/reference/kinship-aliases").mock(
            return_value=httpx.Response(200, json=[{"alias": "bua", "language": "hindi"}])
        )
        result = list_kinship_aliases(http)
        assert result[0]["alias"] == "bua"
        assert "language=" not in str(respx.calls[0].request.url)


def test_list_kinship_aliases_with_language(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/reference/kinship-aliases").mock(
            return_value=httpx.Response(200, json=[{"alias": "bua", "language": "hindi"}])
        )
        list_kinship_aliases(http, language="hindi")
        assert "language=hindi" in str(respx.calls[0].request.url)


def test_list_domains_raises_on_500(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/reference/domains").mock(
            return_value=httpx.Response(500, text="Internal Server Error")
        )
        with pytest.raises(RuntimeError, match="500"):
            list_domains(http)
