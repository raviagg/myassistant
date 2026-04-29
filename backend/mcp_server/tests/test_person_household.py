import pytest
import respx
import httpx
from tools.person_household import (
    add_person_to_household, remove_person_from_household,
    list_household_members, list_person_households,
)


def test_add_person_to_household(http):
    with respx.mock:
        respx.put("http://testserver/api/v1/households/hh1/members/p1").mock(
            return_value=httpx.Response(200, json={})
        )
        result = add_person_to_household(http, person_id="p1", household_id="hh1")
        assert result == {}


def test_remove_person_from_household(http):
    with respx.mock:
        respx.delete("http://testserver/api/v1/households/hh1/members/p1").mock(
            return_value=httpx.Response(204)
        )
        result = remove_person_from_household(http, person_id="p1", household_id="hh1")
        assert result == {}


def test_list_household_members(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/households/hh1/members").mock(
            return_value=httpx.Response(200, json={"items": [{"personId": "p1"}], "total": 1})
        )
        result = list_household_members(http, household_id="hh1")
        assert result["items"][0]["personId"] == "p1"


def test_list_person_households(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/persons/p1/households").mock(
            return_value=httpx.Response(200, json={"items": [{"householdId": "hh1"}], "total": 1})
        )
        result = list_person_households(http, person_id="p1")
        assert result["items"][0]["householdId"] == "hh1"


def test_add_raises_on_404(http):
    with respx.mock:
        respx.put("http://testserver/api/v1/households/bad/members/p1").mock(
            return_value=httpx.Response(404, json={"error": "not_found"})
        )
        with pytest.raises(ValueError, match="404"):
            add_person_to_household(http, person_id="p1", household_id="bad")
