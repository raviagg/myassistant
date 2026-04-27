import json
import pytest
import respx
import httpx
from tools.households import list_households, create_household, get_household, update_household, delete_household


def test_list_households(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/households").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 1000, "offset": 0})
        )
        result = list_households(http)
        assert result["items"] == []


def test_create_household(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/households").mock(
            return_value=httpx.Response(201, json={"id": "hh1", "name": "Aggarwal Family"})
        )
        result = create_household(http, name="Aggarwal Family")
        assert json.loads(respx.calls[0].request.content) == {"name": "Aggarwal Family"}
        assert result["id"] == "hh1"


def test_get_household(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/households/hh1").mock(
            return_value=httpx.Response(200, json={"id": "hh1", "name": "Aggarwal Family"})
        )
        result = get_household(http, household_id="hh1")
        assert result["name"] == "Aggarwal Family"


def test_update_household(http):
    with respx.mock:
        respx.patch("http://testserver/api/v1/households/hh1").mock(
            return_value=httpx.Response(200, json={"id": "hh1", "name": "New Name"})
        )
        result = update_household(http, household_id="hh1", name="New Name")
        assert json.loads(respx.calls[0].request.content) == {"name": "New Name"}
        assert result["name"] == "New Name"


def test_delete_household(http):
    with respx.mock:
        respx.delete("http://testserver/api/v1/households/hh1").mock(
            return_value=httpx.Response(204)
        )
        result = delete_household(http, household_id="hh1")
        assert result == {}


def test_get_household_raises_on_404(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/households/missing").mock(
            return_value=httpx.Response(404, json={"error": "not_found"})
        )
        with pytest.raises(ValueError, match="404"):
            get_household(http, household_id="missing")
