import pytest
import respx
import httpx
from tools.reference import list_domains, list_source_types, list_kinship_aliases


def test_list_domains(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/reference/domains").mock(
            return_value=httpx.Response(200, json=[{"name": "health", "description": "Health domain"}])
        )
        result = list_domains(http)
        assert result[0]["name"] == "health"


def test_list_source_types(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/reference/source-types").mock(
            return_value=httpx.Response(200, json=[{"name": "user_input", "description": "Typed by user"}])
        )
        result = list_source_types(http)
        assert result[0]["name"] == "user_input"


def test_list_kinship_aliases(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/reference/kinship-aliases").mock(
            return_value=httpx.Response(200, json=[{"alias": "bua", "language": "hindi"}])
        )
        result = list_kinship_aliases(http)
        assert result[0]["alias"] == "bua"


def test_list_domains_raises_on_500(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/reference/domains").mock(
            return_value=httpx.Response(500, text="Internal Server Error")
        )
        with pytest.raises(RuntimeError, match="500"):
            list_domains(http)
