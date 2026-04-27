# Python MCP Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a thin Python MCP server in `mcp_server/` that exposes every Scala backend REST endpoint as a FastMCP tool — one tool per endpoint, same arguments.

**Architecture:** `FastMCP` (stdio transport) → `httpx.Client` with Bearer auth → Scala HTTP at `localhost:8080`. Each tool group lives in its own module under `mcp_server/tools/`. Implementation functions take `http` as first arg for direct testability; `register(mcp, http)` wraps them as MCP tools with closures.

**Tech Stack:** Python ≥3.11, `mcp` (FastMCP), `httpx`, `pytest`, `respx` (HTTP mock)

> **Note on folder name:** The directory is `mcp_server/` (not `mcp/`) to avoid shadowing the installed `mcp` package on `sys.path`.

---

## File Map

| File | Responsibility |
|---|---|
| `mcp_server/pyproject.toml` | Package metadata + dependencies |
| `mcp_server/client.py` | `make_client()`, `_check(resp)` helper |
| `mcp_server/server.py` | `FastMCP` instance, imports all tool modules, `mcp.run()` |
| `mcp_server/tools/__init__.py` | Empty |
| `mcp_server/tools/persons.py` | 5 tools (list, create, get, update, delete) |
| `mcp_server/tools/households.py` | 5 tools |
| `mcp_server/tools/person_household.py` | 4 tools |
| `mcp_server/tools/relationships.py` | 6 tools |
| `mcp_server/tools/documents.py` | 4 tools |
| `mcp_server/tools/facts.py` | 5 tools |
| `mcp_server/tools/schemas.py` | 6 tools |
| `mcp_server/tools/reference.py` | 3 tools |
| `mcp_server/tools/audit.py` | 1 tool |
| `mcp_server/tools/files.py` | 4 tools |
| `mcp_server/tests/__init__.py` | Empty |
| `mcp_server/tests/conftest.py` | Shared `http_client` pytest fixture |
| `mcp_server/tests/test_persons.py` | 7 tests |
| `mcp_server/tests/test_households.py` | 6 tests |
| `mcp_server/tests/test_person_household.py` | 5 tests |
| `mcp_server/tests/test_relationships.py` | 7 tests |
| `mcp_server/tests/test_documents.py` | 5 tests |
| `mcp_server/tests/test_facts.py` | 6 tests |
| `mcp_server/tests/test_schemas.py` | 7 tests |
| `mcp_server/tests/test_reference.py` | 4 tests |
| `mcp_server/tests/test_audit.py` | 2 tests |
| `mcp_server/tests/test_files.py` | 5 tests |

---

## Task 0: Project Scaffold

**Files:**
- Create: `mcp_server/pyproject.toml`
- Create: `mcp_server/tools/__init__.py`
- Create: `mcp_server/tests/__init__.py`

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p mcp_server/tools mcp_server/tests
touch mcp_server/tools/__init__.py mcp_server/tests/__init__.py
```

- [ ] **Step 2: Write `mcp_server/pyproject.toml`**

```toml
[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[project]
name = "myassistant-mcp"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = [
    "mcp>=1.0",
    "httpx>=0.27",
]

[project.optional-dependencies]
dev = ["pytest>=8.0", "respx>=0.21"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

- [ ] **Step 3: Install dependencies**

```bash
cd mcp_server
pip install -e ".[dev]"
```

Expected: no errors, `mcp`, `httpx`, `pytest`, `respx` installed.

- [ ] **Step 4: Commit**

```bash
git add mcp_server/
git commit -m "chore: scaffold mcp_server Python package"
```

---

## Task 1: HTTP Client + Test Fixture

**Files:**
- Create: `mcp_server/client.py`
- Create: `mcp_server/tests/conftest.py`

- [ ] **Step 1: Write `mcp_server/client.py`**

```python
import os
import httpx

def make_client() -> httpx.Client:
    base_url = os.environ.get("MYASSISTANT_BASE_URL", "http://localhost:8080")
    token = os.environ.get("MYASSISTANT_AUTH_TOKEN", "dev-token-change-me-in-production")
    return httpx.Client(
        base_url=base_url,
        headers={"Authorization": f"Bearer {token}"},
        timeout=30.0,
    )

def _check(resp: httpx.Response) -> None:
    if resp.is_client_error:
        raise ValueError(f"{resp.status_code}: {resp.text}")
    if resp.is_server_error:
        raise RuntimeError(f"{resp.status_code}: {resp.text}")
```

- [ ] **Step 2: Write `mcp_server/tests/conftest.py`**

```python
import pytest
import httpx

@pytest.fixture
def http():
    return httpx.Client(base_url="http://testserver", headers={"Authorization": "Bearer test-token"})
```

- [ ] **Step 3: Verify import works**

```bash
cd mcp_server
python -c "from client import make_client, _check; print('ok')"
```

Expected: `ok`

- [ ] **Step 4: Commit**

```bash
git add mcp_server/client.py mcp_server/tests/conftest.py
git commit -m "feat(mcp): add http client and test fixture"
```

---

## Task 2: Persons Tools

**Files:**
- Create: `mcp_server/tools/persons.py`
- Create: `mcp_server/tests/test_persons.py`

- [ ] **Step 1: Write failing tests in `mcp_server/tests/test_persons.py`**

```python
import json
import pytest
import respx
import httpx
from tools.persons import (
    list_persons, create_person, get_person, update_person, delete_person,
)

def test_list_persons_no_filter(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/persons").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        result = list_persons(http)
        assert result["items"] == []
        assert respx.calls[0].request.headers["Authorization"] == "Bearer test-token"

def test_list_persons_with_household(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/persons").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        list_persons(http, household_id="hh-1")
        assert "householdId=hh-1" in str(respx.calls[0].request.url)

def test_create_person_required_fields(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/persons").mock(
            return_value=httpx.Response(201, json={"id": "p1", "fullName": "Alice", "gender": "female"})
        )
        result = create_person(http, full_name="Alice", gender="female")
        body = json.loads(respx.calls[0].request.content)
        assert body == {"fullName": "Alice", "gender": "female"}
        assert result["id"] == "p1"

def test_create_person_optional_fields(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/persons").mock(
            return_value=httpx.Response(201, json={"id": "p2"})
        )
        create_person(http, full_name="Bob", gender="male", date_of_birth="1990-01-01",
                      preferred_name="Bobby", user_identifier="bob@example.com")
        body = json.loads(respx.calls[0].request.content)
        assert body["dateOfBirth"] == "1990-01-01"
        assert body["preferredName"] == "Bobby"
        assert body["userIdentifier"] == "bob@example.com"

def test_get_person(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/persons/p1").mock(
            return_value=httpx.Response(200, json={"id": "p1", "fullName": "Alice"})
        )
        result = get_person(http, person_id="p1")
        assert result["fullName"] == "Alice"

def test_update_person(http):
    with respx.mock:
        respx.patch("http://testserver/api/v1/persons/p1").mock(
            return_value=httpx.Response(200, json={"id": "p1", "fullName": "Alicia"})
        )
        result = update_person(http, person_id="p1", full_name="Alicia")
        body = json.loads(respx.calls[0].request.content)
        assert body == {"fullName": "Alicia"}
        assert result["fullName"] == "Alicia"

def test_delete_person(http):
    with respx.mock:
        respx.delete("http://testserver/api/v1/persons/p1").mock(
            return_value=httpx.Response(204)
        )
        result = delete_person(http, person_id="p1")
        assert result == {}

def test_create_person_raises_on_4xx(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/persons").mock(
            return_value=httpx.Response(422, json={"error": "validation_error"})
        )
        with pytest.raises(ValueError, match="422"):
            create_person(http, full_name="X", gender="unknown")
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
cd mcp_server && pytest tests/test_persons.py -v
```

Expected: `ImportError: cannot import name 'list_persons' from 'tools.persons'`

- [ ] **Step 3: Write `mcp_server/tools/persons.py`**

```python
import httpx
from client import _check


def list_persons(
    http: httpx.Client,
    household_id: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict:
    """List persons, optionally filtered by household."""
    params: dict = {"limit": limit, "offset": offset}
    if household_id is not None:
        params["householdId"] = household_id
    resp = http.get("/api/v1/persons", params=params)
    _check(resp)
    return resp.json()


def create_person(
    http: httpx.Client,
    full_name: str,
    gender: str,
    date_of_birth: str | None = None,
    preferred_name: str | None = None,
    user_identifier: str | None = None,
) -> dict:
    """Register a new person."""
    body: dict = {"fullName": full_name, "gender": gender}
    if date_of_birth is not None:
        body["dateOfBirth"] = date_of_birth
    if preferred_name is not None:
        body["preferredName"] = preferred_name
    if user_identifier is not None:
        body["userIdentifier"] = user_identifier
    resp = http.post("/api/v1/persons", json=body)
    _check(resp)
    return resp.json()


def get_person(http: httpx.Client, person_id: str) -> dict:
    """Fetch a single person by ID."""
    resp = http.get(f"/api/v1/persons/{person_id}")
    _check(resp)
    return resp.json()


def update_person(
    http: httpx.Client,
    person_id: str,
    full_name: str | None = None,
    gender: str | None = None,
    date_of_birth: str | None = None,
    preferred_name: str | None = None,
    user_identifier: str | None = None,
) -> dict:
    """Update mutable fields on a person (PATCH semantics)."""
    body: dict = {}
    if full_name is not None:
        body["fullName"] = full_name
    if gender is not None:
        body["gender"] = gender
    if date_of_birth is not None:
        body["dateOfBirth"] = date_of_birth
    if preferred_name is not None:
        body["preferredName"] = preferred_name
    if user_identifier is not None:
        body["userIdentifier"] = user_identifier
    resp = http.patch(f"/api/v1/persons/{person_id}", json=body)
    _check(resp)
    return resp.json()


def delete_person(http: httpx.Client, person_id: str) -> dict:
    """Delete a person by ID."""
    resp = http.delete(f"/api/v1/persons/{person_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def list_persons_tool(
        household_id: str | None = None, limit: int = 50, offset: int = 0
    ) -> dict:
        """List persons, optionally filtered by household."""
        return list_persons(http, household_id, limit, offset)

    @mcp.tool()
    def create_person_tool(
        full_name: str,
        gender: str,
        date_of_birth: str | None = None,
        preferred_name: str | None = None,
        user_identifier: str | None = None,
    ) -> dict:
        """Register a new person."""
        return create_person(http, full_name, gender, date_of_birth, preferred_name, user_identifier)

    @mcp.tool()
    def get_person_tool(person_id: str) -> dict:
        """Fetch a single person by ID."""
        return get_person(http, person_id)

    @mcp.tool()
    def update_person_tool(
        person_id: str,
        full_name: str | None = None,
        gender: str | None = None,
        date_of_birth: str | None = None,
        preferred_name: str | None = None,
        user_identifier: str | None = None,
    ) -> dict:
        """Update mutable fields on a person."""
        return update_person(http, person_id, full_name, gender, date_of_birth, preferred_name, user_identifier)

    @mcp.tool()
    def delete_person_tool(person_id: str) -> dict:
        """Delete a person by ID."""
        return delete_person(http, person_id)
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
cd mcp_server && pytest tests/test_persons.py -v
```

Expected: `8 passed`

- [ ] **Step 5: Commit**

```bash
git add mcp_server/tools/persons.py mcp_server/tests/test_persons.py
git commit -m "feat(mcp): add persons tools with tests"
```

---

## Task 3: Household Tools

**Files:**
- Create: `mcp_server/tools/households.py`
- Create: `mcp_server/tests/test_households.py`

- [ ] **Step 1: Write `mcp_server/tests/test_households.py`**

```python
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
```

- [ ] **Step 2: Run — verify fail**

```bash
cd mcp_server && pytest tests/test_households.py -v
```

Expected: `ImportError`

- [ ] **Step 3: Write `mcp_server/tools/households.py`**

```python
import httpx
from client import _check


def list_households(http: httpx.Client) -> dict:
    """List all households."""
    resp = http.get("/api/v1/households")
    _check(resp)
    return resp.json()


def create_household(http: httpx.Client, name: str) -> dict:
    """Create a new household."""
    resp = http.post("/api/v1/households", json={"name": name})
    _check(resp)
    return resp.json()


def get_household(http: httpx.Client, household_id: str) -> dict:
    """Fetch a single household by ID."""
    resp = http.get(f"/api/v1/households/{household_id}")
    _check(resp)
    return resp.json()


def update_household(http: httpx.Client, household_id: str, name: str | None = None) -> dict:
    """Rename a household."""
    body: dict = {}
    if name is not None:
        body["name"] = name
    resp = http.patch(f"/api/v1/households/{household_id}", json=body)
    _check(resp)
    return resp.json()


def delete_household(http: httpx.Client, household_id: str) -> dict:
    """Delete a household by ID."""
    resp = http.delete(f"/api/v1/households/{household_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def list_households_tool() -> dict:
        """List all households."""
        return list_households(http)

    @mcp.tool()
    def create_household_tool(name: str) -> dict:
        """Create a new household."""
        return create_household(http, name)

    @mcp.tool()
    def get_household_tool(household_id: str) -> dict:
        """Fetch a single household by ID."""
        return get_household(http, household_id)

    @mcp.tool()
    def update_household_tool(household_id: str, name: str | None = None) -> dict:
        """Rename a household."""
        return update_household(http, household_id, name)

    @mcp.tool()
    def delete_household_tool(household_id: str) -> dict:
        """Delete a household by ID."""
        return delete_household(http, household_id)
```

- [ ] **Step 4: Run — verify pass**

```bash
cd mcp_server && pytest tests/test_households.py -v
```

Expected: `6 passed`

- [ ] **Step 5: Commit**

```bash
git add mcp_server/tools/households.py mcp_server/tests/test_households.py
git commit -m "feat(mcp): add households tools with tests"
```

---

## Task 4: Person-Household Tools

**Files:**
- Create: `mcp_server/tools/person_household.py`
- Create: `mcp_server/tests/test_person_household.py`

- [ ] **Step 1: Write `mcp_server/tests/test_person_household.py`**

```python
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
        result = add_person_to_household(http, household_id="hh1", person_id="p1")
        assert result == {}


def test_remove_person_from_household(http):
    with respx.mock:
        respx.delete("http://testserver/api/v1/households/hh1/members/p1").mock(
            return_value=httpx.Response(204)
        )
        result = remove_person_from_household(http, household_id="hh1", person_id="p1")
        assert result == {}


def test_list_household_members(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/households/hh1/members").mock(
            return_value=httpx.Response(200, json={"items": [{"personId": "p1"}], "total": 1, "limit": 1000, "offset": 0})
        )
        result = list_household_members(http, household_id="hh1")
        assert result["items"][0]["personId"] == "p1"


def test_list_person_households(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/persons/p1/households").mock(
            return_value=httpx.Response(200, json={"items": [{"householdId": "hh1"}], "total": 1, "limit": 1000, "offset": 0})
        )
        result = list_person_households(http, person_id="p1")
        assert result["items"][0]["householdId"] == "hh1"


def test_add_raises_on_404(http):
    with respx.mock:
        respx.put("http://testserver/api/v1/households/bad/members/p1").mock(
            return_value=httpx.Response(404, json={"error": "not_found"})
        )
        with pytest.raises(ValueError, match="404"):
            add_person_to_household(http, household_id="bad", person_id="p1")
```

- [ ] **Step 2: Run — verify fail**

```bash
cd mcp_server && pytest tests/test_person_household.py -v
```

Expected: `ImportError`

- [ ] **Step 3: Write `mcp_server/tools/person_household.py`**

```python
import httpx
from client import _check


def add_person_to_household(http: httpx.Client, household_id: str, person_id: str) -> dict:
    """Link a person to a household."""
    resp = http.put(f"/api/v1/households/{household_id}/members/{person_id}")
    _check(resp)
    return resp.json() if resp.content else {}


def remove_person_from_household(http: httpx.Client, household_id: str, person_id: str) -> dict:
    """Remove a person's membership from a household."""
    resp = http.delete(f"/api/v1/households/{household_id}/members/{person_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def list_household_members(http: httpx.Client, household_id: str) -> dict:
    """Return all members of a household."""
    resp = http.get(f"/api/v1/households/{household_id}/members")
    _check(resp)
    return resp.json()


def list_person_households(http: httpx.Client, person_id: str) -> dict:
    """Return all households a person belongs to."""
    resp = http.get(f"/api/v1/persons/{person_id}/households")
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def add_person_to_household_tool(household_id: str, person_id: str) -> dict:
        """Link a person to a household."""
        return add_person_to_household(http, household_id, person_id)

    @mcp.tool()
    def remove_person_from_household_tool(household_id: str, person_id: str) -> dict:
        """Remove a person from a household."""
        return remove_person_from_household(http, household_id, person_id)

    @mcp.tool()
    def list_household_members_tool(household_id: str) -> dict:
        """List all members of a household."""
        return list_household_members(http, household_id)

    @mcp.tool()
    def list_person_households_tool(person_id: str) -> dict:
        """List all households a person belongs to."""
        return list_person_households(http, person_id)
```

- [ ] **Step 4: Run — verify pass**

```bash
cd mcp_server && pytest tests/test_person_household.py -v
```

Expected: `5 passed`

- [ ] **Step 5: Commit**

```bash
git add mcp_server/tools/person_household.py mcp_server/tests/test_person_household.py
git commit -m "feat(mcp): add person-household tools with tests"
```

---

## Task 5: Relationship Tools

**Files:**
- Create: `mcp_server/tools/relationships.py`
- Create: `mcp_server/tests/test_relationships.py`

- [ ] **Step 1: Write `mcp_server/tests/test_relationships.py`**

```python
import json
import pytest
import respx
import httpx
from tools.relationships import (
    create_relationship, list_relationships, get_relationship,
    update_relationship, delete_relationship, resolve_kinship,
)


def test_create_relationship(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/relationships").mock(
            return_value=httpx.Response(201, json={"id": "r1", "relationType": "father"})
        )
        result = create_relationship(http, from_person_id="p1", to_person_id="p2", relation_type="father")
        body = json.loads(respx.calls[0].request.content)
        assert body == {"fromPersonId": "p1", "toPersonId": "p2", "relationType": "father"}
        assert result["relationType"] == "father"


def test_list_relationships(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/relationships").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 100, "offset": 0})
        )
        result = list_relationships(http, person_id="p1")
        assert "person_id=p1" in str(respx.calls[0].request.url)
        assert result["items"] == []


def test_get_relationship(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/relationships/p1/p2").mock(
            return_value=httpx.Response(200, json={"id": "r1", "relationType": "father"})
        )
        result = get_relationship(http, from_person_id="p1", to_person_id="p2")
        assert result["relationType"] == "father"


def test_update_relationship(http):
    with respx.mock:
        respx.patch("http://testserver/api/v1/relationships/p1/p2").mock(
            return_value=httpx.Response(200, json={"id": "r1", "relationType": "mother"})
        )
        result = update_relationship(http, from_person_id="p1", to_person_id="p2", relation_type="mother")
        body = json.loads(respx.calls[0].request.content)
        assert body == {"relationType": "mother"}
        assert result["relationType"] == "mother"


def test_delete_relationship(http):
    with respx.mock:
        respx.delete("http://testserver/api/v1/relationships/p1/p2").mock(
            return_value=httpx.Response(204)
        )
        result = delete_relationship(http, from_person_id="p1", to_person_id="p2")
        assert result == {}


def test_resolve_kinship(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/relationships/p1/p2/kinship").mock(
            return_value=httpx.Response(200, json={"chain": ["father", "sister"], "alias": "bua"})
        )
        result = resolve_kinship(http, from_person_id="p1", to_person_id="p2")
        assert result["alias"] == "bua"


def test_resolve_kinship_with_language(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/relationships/p1/p2/kinship").mock(
            return_value=httpx.Response(200, json={"chain": ["father", "sister"], "alias": "bua"})
        )
        resolve_kinship(http, from_person_id="p1", to_person_id="p2", language="hindi")
        assert "language=hindi" in str(respx.calls[0].request.url)
```

- [ ] **Step 2: Run — verify fail**

```bash
cd mcp_server && pytest tests/test_relationships.py -v
```

Expected: `ImportError`

- [ ] **Step 3: Write `mcp_server/tools/relationships.py`**

```python
import httpx
from client import _check


def create_relationship(
    http: httpx.Client, from_person_id: str, to_person_id: str, relation_type: str
) -> dict:
    """Record a directed relationship between two persons."""
    resp = http.post("/api/v1/relationships", json={
        "fromPersonId": from_person_id,
        "toPersonId": to_person_id,
        "relationType": relation_type,
    })
    _check(resp)
    return resp.json()


def list_relationships(http: httpx.Client, person_id: str) -> dict:
    """List all relationships where the person appears as subject or object."""
    resp = http.get("/api/v1/relationships", params={"person_id": person_id})
    _check(resp)
    return resp.json()


def get_relationship(http: httpx.Client, from_person_id: str, to_person_id: str) -> dict:
    """Fetch a single directed relationship."""
    resp = http.get(f"/api/v1/relationships/{from_person_id}/{to_person_id}")
    _check(resp)
    return resp.json()


def update_relationship(
    http: httpx.Client, from_person_id: str, to_person_id: str, relation_type: str
) -> dict:
    """Change the relation type on an existing relationship."""
    resp = http.patch(f"/api/v1/relationships/{from_person_id}/{to_person_id}",
                      json={"relationType": relation_type})
    _check(resp)
    return resp.json()


def delete_relationship(http: httpx.Client, from_person_id: str, to_person_id: str) -> dict:
    """Delete a directed relationship."""
    resp = http.delete(f"/api/v1/relationships/{from_person_id}/{to_person_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def resolve_kinship(
    http: httpx.Client, from_person_id: str, to_person_id: str, language: str = "english"
) -> dict:
    """Derive the cultural kinship name between two persons."""
    resp = http.get(f"/api/v1/relationships/{from_person_id}/{to_person_id}/kinship",
                    params={"language": language})
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def create_relationship_tool(from_person_id: str, to_person_id: str, relation_type: str) -> dict:
        """Record a directed relationship between two persons."""
        return create_relationship(http, from_person_id, to_person_id, relation_type)

    @mcp.tool()
    def list_relationships_tool(person_id: str) -> dict:
        """List all relationships for a person."""
        return list_relationships(http, person_id)

    @mcp.tool()
    def get_relationship_tool(from_person_id: str, to_person_id: str) -> dict:
        """Fetch a single directed relationship."""
        return get_relationship(http, from_person_id, to_person_id)

    @mcp.tool()
    def update_relationship_tool(from_person_id: str, to_person_id: str, relation_type: str) -> dict:
        """Change the relation type on an existing relationship."""
        return update_relationship(http, from_person_id, to_person_id, relation_type)

    @mcp.tool()
    def delete_relationship_tool(from_person_id: str, to_person_id: str) -> dict:
        """Delete a directed relationship."""
        return delete_relationship(http, from_person_id, to_person_id)

    @mcp.tool()
    def resolve_kinship_tool(
        from_person_id: str, to_person_id: str, language: str = "english"
    ) -> dict:
        """Derive the cultural kinship name between two persons."""
        return resolve_kinship(http, from_person_id, to_person_id, language)
```

- [ ] **Step 4: Run — verify pass**

```bash
cd mcp_server && pytest tests/test_relationships.py -v
```

Expected: `7 passed`

- [ ] **Step 5: Commit**

```bash
git add mcp_server/tools/relationships.py mcp_server/tests/test_relationships.py
git commit -m "feat(mcp): add relationship tools with tests"
```

---

## Task 6: Document Tools

**Files:**
- Create: `mcp_server/tools/documents.py`
- Create: `mcp_server/tests/test_documents.py`

- [ ] **Step 1: Write `mcp_server/tests/test_documents.py`**

```python
import json
import pytest
import respx
import httpx
from tools.documents import create_document, list_documents, get_document, search_documents


def test_create_document_minimal(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/documents").mock(
            return_value=httpx.Response(201, json={"id": "d1", "contentText": "hello"})
        )
        result = create_document(http, content_text="hello", source_type="user_input")
        body = json.loads(respx.calls[0].request.content)
        assert body["contentText"] == "hello"
        assert body["sourceType"] == "user_input"
        assert body["files"] == []
        assert body["supersedesIds"] == []
        assert result["id"] == "d1"


def test_create_document_with_owner(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/documents").mock(
            return_value=httpx.Response(201, json={"id": "d1"})
        )
        create_document(http, content_text="hi", source_type="user_input",
                        person_id="p1", household_id="hh1")
        body = json.loads(respx.calls[0].request.content)
        assert body["personId"] == "p1"
        assert body["householdId"] == "hh1"


def test_list_documents(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/documents").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        result = list_documents(http, person_id="p1", source_type="user_input")
        url = str(respx.calls[0].request.url)
        assert "personId=p1" in url
        assert "sourceType=user_input" in url
        assert result["items"] == []


def test_get_document(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/documents/d1").mock(
            return_value=httpx.Response(200, json={"id": "d1", "contentText": "hello"})
        )
        result = get_document(http, document_id="d1")
        assert result["contentText"] == "hello"


def test_search_documents(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/documents/search").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 20, "offset": 0})
        )
        result = search_documents(http, query="passport")
        body = json.loads(respx.calls[0].request.content)
        assert body["query"] == "passport"
        assert result["items"] == []
```

- [ ] **Step 2: Run — verify fail**

```bash
cd mcp_server && pytest tests/test_documents.py -v
```

Expected: `ImportError`

- [ ] **Step 3: Write `mcp_server/tools/documents.py`**

```python
import httpx
from client import _check


def create_document(
    http: httpx.Client,
    content_text: str,
    source_type: str,
    person_id: str | None = None,
    household_id: str | None = None,
    files: list | None = None,
    supersedes_ids: list | None = None,
) -> dict:
    """Create a new immutable document."""
    body: dict = {
        "contentText": content_text,
        "sourceType": source_type,
        "files": files or [],
        "supersedesIds": supersedes_ids or [],
    }
    if person_id is not None:
        body["personId"] = person_id
    if household_id is not None:
        body["householdId"] = household_id
    resp = http.post("/api/v1/documents", json=body)
    _check(resp)
    return resp.json()


def list_documents(
    http: httpx.Client,
    person_id: str | None = None,
    household_id: str | None = None,
    source_type: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict:
    """List documents with optional filters."""
    params: dict = {"limit": limit, "offset": offset}
    if person_id is not None:
        params["personId"] = person_id
    if household_id is not None:
        params["householdId"] = household_id
    if source_type is not None:
        params["sourceType"] = source_type
    resp = http.get("/api/v1/documents", params=params)
    _check(resp)
    return resp.json()


def get_document(http: httpx.Client, document_id: str) -> dict:
    """Fetch a single document by ID."""
    resp = http.get(f"/api/v1/documents/{document_id}")
    _check(resp)
    return resp.json()


def search_documents(
    http: httpx.Client,
    query: str,
    person_id: str | None = None,
    household_id: str | None = None,
    limit: int | None = None,
) -> dict:
    """Semantic search over documents."""
    body: dict = {"query": query}
    if person_id is not None:
        body["personId"] = person_id
    if household_id is not None:
        body["householdId"] = household_id
    if limit is not None:
        body["limit"] = limit
    resp = http.post("/api/v1/documents/search", json=body)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def create_document_tool(
        content_text: str,
        source_type: str,
        person_id: str | None = None,
        household_id: str | None = None,
        files: list | None = None,
        supersedes_ids: list | None = None,
    ) -> dict:
        """Create a new immutable document."""
        return create_document(http, content_text, source_type, person_id, household_id, files, supersedes_ids)

    @mcp.tool()
    def list_documents_tool(
        person_id: str | None = None,
        household_id: str | None = None,
        source_type: str | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> dict:
        """List documents with optional filters."""
        return list_documents(http, person_id, household_id, source_type, limit, offset)

    @mcp.tool()
    def get_document_tool(document_id: str) -> dict:
        """Fetch a single document by ID."""
        return get_document(http, document_id)

    @mcp.tool()
    def search_documents_tool(
        query: str,
        person_id: str | None = None,
        household_id: str | None = None,
        limit: int | None = None,
    ) -> dict:
        """Semantic search over documents."""
        return search_documents(http, query, person_id, household_id, limit)
```

- [ ] **Step 4: Run — verify pass**

```bash
cd mcp_server && pytest tests/test_documents.py -v
```

Expected: `5 passed`

- [ ] **Step 5: Commit**

```bash
git add mcp_server/tools/documents.py mcp_server/tests/test_documents.py
git commit -m "feat(mcp): add document tools with tests"
```

---

## Task 7: Fact Tools

**Files:**
- Create: `mcp_server/tools/facts.py`
- Create: `mcp_server/tests/test_facts.py`

- [ ] **Step 1: Write `mcp_server/tests/test_facts.py`**

```python
import json
import pytest
import respx
import httpx
from tools.facts import create_fact, list_current_facts, get_current_fact, get_fact_history, search_facts


def test_create_fact(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/facts").mock(
            return_value=httpx.Response(201, json={"id": "f1", "operationType": "create"})
        )
        result = create_fact(http, document_id="d1", schema_id="s1",
                             operation_type="create", fields={"title": "test"})
        body = json.loads(respx.calls[0].request.content)
        assert body["documentId"] == "d1"
        assert body["schemaId"] == "s1"
        assert body["operationType"] == "create"
        assert body["fields"] == {"title": "test"}
        assert "entityInstanceId" not in body
        assert result["id"] == "f1"


def test_create_fact_with_entity_instance_id(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/facts").mock(
            return_value=httpx.Response(201, json={"id": "f2"})
        )
        create_fact(http, document_id="d1", schema_id="s1",
                    operation_type="update", fields={"status": "done"},
                    entity_instance_id="ei1")
        body = json.loads(respx.calls[0].request.content)
        assert body["entityInstanceId"] == "ei1"


def test_list_current_facts(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/facts/current").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        result = list_current_facts(http, schema_id="s1")
        assert "schemaId=s1" in str(respx.calls[0].request.url)
        assert result["items"] == []


def test_get_current_fact(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/facts/ei1/current").mock(
            return_value=httpx.Response(200, json={"entityInstanceId": "ei1", "fields": {}})
        )
        result = get_current_fact(http, entity_id="ei1")
        assert result["entityInstanceId"] == "ei1"


def test_get_fact_history(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/facts/ei1/history").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 100, "offset": 0})
        )
        result = get_fact_history(http, entity_id="ei1")
        assert result["items"] == []


def test_search_facts(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/facts/search").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 20, "offset": 0})
        )
        result = search_facts(http, query="passport", domain="health")
        body = json.loads(respx.calls[0].request.content)
        assert body["query"] == "passport"
        assert body["domain"] == "health"
        assert result["items"] == []
```

- [ ] **Step 2: Run — verify fail**

```bash
cd mcp_server && pytest tests/test_facts.py -v
```

Expected: `ImportError`

- [ ] **Step 3: Write `mcp_server/tools/facts.py`**

```python
import httpx
from client import _check


def create_fact(
    http: httpx.Client,
    document_id: str,
    schema_id: str,
    operation_type: str,
    fields: dict,
    entity_instance_id: str | None = None,
) -> dict:
    """Persist a single fact operation extracted from a document."""
    body: dict = {
        "documentId": document_id,
        "schemaId": schema_id,
        "operationType": operation_type,
        "fields": fields,
    }
    if entity_instance_id is not None:
        body["entityInstanceId"] = entity_instance_id
    resp = http.post("/api/v1/facts", json=body)
    _check(resp)
    return resp.json()


def list_current_facts(
    http: httpx.Client, schema_id: str, limit: int = 50, offset: int = 0
) -> dict:
    """List current entity states filtered by schema."""
    resp = http.get("/api/v1/facts/current",
                    params={"schemaId": schema_id, "limit": limit, "offset": offset})
    _check(resp)
    return resp.json()


def get_current_fact(http: httpx.Client, entity_id: str) -> dict:
    """Get the merged current state for a single entity instance."""
    resp = http.get(f"/api/v1/facts/{entity_id}/current")
    _check(resp)
    return resp.json()


def get_fact_history(http: httpx.Client, entity_id: str) -> dict:
    """Get the full operation history for an entity instance."""
    resp = http.get(f"/api/v1/facts/{entity_id}/history")
    _check(resp)
    return resp.json()


def search_facts(
    http: httpx.Client,
    query: str,
    domain: str | None = None,
    limit: int | None = None,
) -> dict:
    """Semantic search over current fact states."""
    body: dict = {"query": query}
    if domain is not None:
        body["domain"] = domain
    if limit is not None:
        body["limit"] = limit
    resp = http.post("/api/v1/facts/search", json=body)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def create_fact_tool(
        document_id: str,
        schema_id: str,
        operation_type: str,
        fields: dict,
        entity_instance_id: str | None = None,
    ) -> dict:
        """Persist a single fact operation extracted from a document."""
        return create_fact(http, document_id, schema_id, operation_type, fields, entity_instance_id)

    @mcp.tool()
    def list_current_facts_tool(schema_id: str, limit: int = 50, offset: int = 0) -> dict:
        """List current entity states filtered by schema."""
        return list_current_facts(http, schema_id, limit, offset)

    @mcp.tool()
    def get_current_fact_tool(entity_id: str) -> dict:
        """Get the merged current state for a single entity instance."""
        return get_current_fact(http, entity_id)

    @mcp.tool()
    def get_fact_history_tool(entity_id: str) -> dict:
        """Get the full operation history for an entity instance."""
        return get_fact_history(http, entity_id)

    @mcp.tool()
    def search_facts_tool(
        query: str, domain: str | None = None, limit: int | None = None
    ) -> dict:
        """Semantic search over current fact states."""
        return search_facts(http, query, domain, limit)
```

- [ ] **Step 4: Run — verify pass**

```bash
cd mcp_server && pytest tests/test_facts.py -v
```

Expected: `6 passed`

- [ ] **Step 5: Commit**

```bash
git add mcp_server/tools/facts.py mcp_server/tests/test_facts.py
git commit -m "feat(mcp): add fact tools with tests"
```

---

## Task 8: Schema Tools

**Files:**
- Create: `mcp_server/tools/schemas.py`
- Create: `mcp_server/tests/test_schemas.py`

- [ ] **Step 1: Write `mcp_server/tests/test_schemas.py`**

```python
import json
import pytest
import respx
import httpx
from tools.schemas import (
    list_schemas, create_schema, get_current_schemas,
    get_schema, add_schema_version, deactivate_schema,
)

_FIELD_DEFS = [{"name": "title", "type": "text", "required": True}]


def test_list_schemas(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/schemas").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 100, "offset": 0})
        )
        result = list_schemas(http)
        assert result["items"] == []


def test_create_schema(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/schemas").mock(
            return_value=httpx.Response(201, json={"id": "sc1", "entityType": "todo_item"})
        )
        result = create_schema(
            http, domain="todo", entity_type="todo_item",
            description="A task", field_definitions=_FIELD_DEFS,
            extraction_prompt="Extract todo items",
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["domain"] == "todo"
        assert body["entityType"] == "todo_item"
        assert body["fieldDefinitions"] == _FIELD_DEFS
        assert result["id"] == "sc1"


def test_get_current_schemas(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/schemas/current").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 100, "offset": 0})
        )
        result = get_current_schemas(http)
        assert result["items"] == []


def test_get_schema(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/schemas/sc1").mock(
            return_value=httpx.Response(200, json={"id": "sc1", "entityType": "todo_item"})
        )
        result = get_schema(http, schema_id="sc1")
        assert result["id"] == "sc1"


def test_add_schema_version(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/schemas/todo/todo_item/versions").mock(
            return_value=httpx.Response(201, json={"id": "sc2", "schemaVersion": 2})
        )
        result = add_schema_version(
            http, domain="todo", entity_type="todo_item",
            description="v2", field_definitions=_FIELD_DEFS,
            extraction_prompt="Extract todos v2",
        )
        assert result["schemaVersion"] == 2


def test_deactivate_schema(http):
    with respx.mock:
        respx.delete("http://testserver/api/v1/schemas/todo/todo_item/active").mock(
            return_value=httpx.Response(204)
        )
        result = deactivate_schema(http, domain="todo", entity_type="todo_item")
        assert result == {}


def test_create_schema_raises_on_conflict(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/schemas").mock(
            return_value=httpx.Response(409, json={"error": "conflict"})
        )
        with pytest.raises(ValueError, match="409"):
            create_schema(http, domain="todo", entity_type="todo_item",
                          description="A task", field_definitions=_FIELD_DEFS,
                          extraction_prompt="prompt")
```

- [ ] **Step 2: Run — verify fail**

```bash
cd mcp_server && pytest tests/test_schemas.py -v
```

Expected: `ImportError`

- [ ] **Step 3: Write `mcp_server/tools/schemas.py`**

```python
import httpx
from client import _check


def list_schemas(http: httpx.Client) -> dict:
    """List all schema definitions."""
    resp = http.get("/api/v1/schemas")
    _check(resp)
    return resp.json()


def create_schema(
    http: httpx.Client,
    domain: str,
    entity_type: str,
    description: str,
    field_definitions: list,
    extraction_prompt: str,
    change_description: str | None = None,
) -> dict:
    """Create a new entity type schema."""
    body: dict = {
        "domain": domain,
        "entityType": entity_type,
        "description": description,
        "fieldDefinitions": field_definitions,
        "extractionPrompt": extraction_prompt,
    }
    if change_description is not None:
        body["changeDescription"] = change_description
    resp = http.post("/api/v1/schemas", json=body)
    _check(resp)
    return resp.json()


def get_current_schemas(http: httpx.Client) -> dict:
    """List only the currently active schema per entity type."""
    resp = http.get("/api/v1/schemas/current")
    _check(resp)
    return resp.json()


def get_schema(http: httpx.Client, schema_id: str) -> dict:
    """Fetch a specific schema version by ID."""
    resp = http.get(f"/api/v1/schemas/{schema_id}")
    _check(resp)
    return resp.json()


def add_schema_version(
    http: httpx.Client,
    domain: str,
    entity_type: str,
    description: str,
    field_definitions: list,
    extraction_prompt: str,
    change_description: str | None = None,
) -> dict:
    """Create a new version of an existing schema."""
    body: dict = {
        "domain": domain,
        "entityType": entity_type,
        "description": description,
        "fieldDefinitions": field_definitions,
        "extractionPrompt": extraction_prompt,
    }
    if change_description is not None:
        body["changeDescription"] = change_description
    resp = http.post(f"/api/v1/schemas/{domain}/{entity_type}/versions", json=body)
    _check(resp)
    return resp.json()


def deactivate_schema(http: httpx.Client, domain: str, entity_type: str) -> dict:
    """Mark the active schema for a domain/entity_type as inactive."""
    resp = http.delete(f"/api/v1/schemas/{domain}/{entity_type}/active")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def list_schemas_tool() -> dict:
        """List all schema definitions."""
        return list_schemas(http)

    @mcp.tool()
    def create_schema_tool(
        domain: str, entity_type: str, description: str,
        field_definitions: list, extraction_prompt: str,
        change_description: str | None = None,
    ) -> dict:
        """Create a new entity type schema."""
        return create_schema(http, domain, entity_type, description, field_definitions, extraction_prompt, change_description)

    @mcp.tool()
    def get_current_schemas_tool() -> dict:
        """List only the currently active schema per entity type."""
        return get_current_schemas(http)

    @mcp.tool()
    def get_schema_tool(schema_id: str) -> dict:
        """Fetch a specific schema version by ID."""
        return get_schema(http, schema_id)

    @mcp.tool()
    def add_schema_version_tool(
        domain: str, entity_type: str, description: str,
        field_definitions: list, extraction_prompt: str,
        change_description: str | None = None,
    ) -> dict:
        """Create a new version of an existing schema."""
        return add_schema_version(http, domain, entity_type, description, field_definitions, extraction_prompt, change_description)

    @mcp.tool()
    def deactivate_schema_tool(domain: str, entity_type: str) -> dict:
        """Mark the active schema for a domain/entity_type as inactive."""
        return deactivate_schema(http, domain, entity_type)
```

- [ ] **Step 4: Run — verify pass**

```bash
cd mcp_server && pytest tests/test_schemas.py -v
```

Expected: `7 passed`

- [ ] **Step 5: Commit**

```bash
git add mcp_server/tools/schemas.py mcp_server/tests/test_schemas.py
git commit -m "feat(mcp): add schema tools with tests"
```

---

## Task 9: Reference Tools

**Files:**
- Create: `mcp_server/tools/reference.py`
- Create: `mcp_server/tests/test_reference.py`

- [ ] **Step 1: Write `mcp_server/tests/test_reference.py`**

```python
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
```

- [ ] **Step 2: Run — verify fail**

```bash
cd mcp_server && pytest tests/test_reference.py -v
```

Expected: `ImportError`

- [ ] **Step 3: Write `mcp_server/tools/reference.py`**

```python
import httpx
from client import _check


def list_domains(http: httpx.Client) -> list:
    """Return all life domains."""
    resp = http.get("/api/v1/reference/domains")
    _check(resp)
    return resp.json()


def list_source_types(http: httpx.Client) -> list:
    """Return all registered source types."""
    resp = http.get("/api/v1/reference/source-types")
    _check(resp)
    return resp.json()


def list_kinship_aliases(http: httpx.Client) -> list:
    """Return all cultural kinship name mappings."""
    resp = http.get("/api/v1/reference/kinship-aliases")
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def list_domains_tool() -> list:
        """Return all life domains."""
        return list_domains(http)

    @mcp.tool()
    def list_source_types_tool() -> list:
        """Return all registered source types."""
        return list_source_types(http)

    @mcp.tool()
    def list_kinship_aliases_tool() -> list:
        """Return all cultural kinship name mappings."""
        return list_kinship_aliases(http)
```

- [ ] **Step 4: Run — verify pass**

```bash
cd mcp_server && pytest tests/test_reference.py -v
```

Expected: `4 passed`

- [ ] **Step 5: Commit**

```bash
git add mcp_server/tools/reference.py mcp_server/tests/test_reference.py
git commit -m "feat(mcp): add reference tools with tests"
```

---

## Task 10: Audit Tool

**Files:**
- Create: `mcp_server/tools/audit.py`
- Create: `mcp_server/tests/test_audit.py`

- [ ] **Step 1: Write `mcp_server/tests/test_audit.py`**

```python
import json
import pytest
import respx
import httpx
from tools.audit import log_interaction


def test_log_interaction_for_person(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/audit/interactions").mock(
            return_value=httpx.Response(201, json={"id": "a1", "status": "success"})
        )
        result = log_interaction(
            http, message="hi", status="success",
            tool_calls=[], person_id="p1",
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["message"] == "hi"
        assert body["status"] == "success"
        assert body["personId"] == "p1"
        assert body["toolCalls"] == []
        assert result["id"] == "a1"


def test_log_interaction_for_job(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/audit/interactions").mock(
            return_value=httpx.Response(201, json={"id": "a2"})
        )
        log_interaction(
            http, message="plaid sync", status="success",
            tool_calls=[], job_type="plaid_poll",
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["jobType"] == "plaid_poll"
        assert "personId" not in body
```

- [ ] **Step 2: Run — verify fail**

```bash
cd mcp_server && pytest tests/test_audit.py -v
```

Expected: `ImportError`

- [ ] **Step 3: Write `mcp_server/tools/audit.py`**

```python
import httpx
from client import _check


def log_interaction(
    http: httpx.Client,
    message: str,
    status: str,
    tool_calls: list,
    person_id: str | None = None,
    job_type: str | None = None,
    response: str | None = None,
    error: str | None = None,
) -> dict:
    """Persist a record of one interaction turn to the audit log."""
    body: dict = {"message": message, "status": status, "toolCalls": tool_calls}
    if person_id is not None:
        body["personId"] = person_id
    if job_type is not None:
        body["jobType"] = job_type
    if response is not None:
        body["response"] = response
    if error is not None:
        body["error"] = error
    resp = http.post("/api/v1/audit/interactions", json=body)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def log_interaction_tool(
        message: str,
        status: str,
        tool_calls: list,
        person_id: str | None = None,
        job_type: str | None = None,
        response: str | None = None,
        error: str | None = None,
    ) -> dict:
        """Persist a record of one interaction turn to the audit log."""
        return log_interaction(http, message, status, tool_calls, person_id, job_type, response, error)
```

- [ ] **Step 4: Run — verify pass**

```bash
cd mcp_server && pytest tests/test_audit.py -v
```

Expected: `2 passed`

- [ ] **Step 5: Commit**

```bash
git add mcp_server/tools/audit.py mcp_server/tests/test_audit.py
git commit -m "feat(mcp): add audit tool with tests"
```

---

## Task 11: File Tools

**Files:**
- Create: `mcp_server/tools/files.py`
- Create: `mcp_server/tests/test_files.py`

> **Note on `save_file`:** The Scala endpoint accepts either raw body or multipart. We send raw body bytes (decoded from base64) with `fileName`, `mimeType`, `personId`, `householdId` as query params — simpler than multipart and fully supported.
> **Note on `get_file`:** The endpoint returns raw bytes. The tool base64-encodes them for the MCP response.

- [ ] **Step 1: Write `mcp_server/tests/test_files.py`**

```python
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
            return_value=httpx.Response(201, json={"key": "files/abc.txt", "originalName": "test.txt"})
        )
        result = save_file(http, content_base64=b64, filename="test.txt", mime_type="text/plain")
        req = respx.calls[0].request
        assert req.content == content
        assert "fileName=test.txt" in str(req.url)
        assert result["key"] == "files/abc.txt"


def test_extract_text_from_file(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/files/extract-text").mock(
            return_value=httpx.Response(200, json={"key": "files/abc.pdf", "extractedText": "hello"}))
        result = extract_text_from_file(http, key="files/abc.pdf", mime_type="application/pdf")
        body = json.loads(respx.calls[0].request.content)
        assert body == {"key": "files/abc.pdf", "mimeType": "application/pdf"}
        assert result["extractedText"] == "hello"


def test_get_file(http):
    raw = b"file bytes"
    with respx.mock:
        respx.get("http://testserver/api/v1/files").mock(
            return_value=httpx.Response(200, content=raw,
                                        headers={"content-type": "application/octet-stream"})
        )
        result = get_file(http, key="files/abc.txt")
        assert "key=files%2Fabc.txt" in str(respx.calls[0].request.url) or "key=files/abc.txt" in str(respx.calls[0].request.url)
        assert result["content_base64"] == base64.b64encode(raw).decode()


def test_delete_file(http):
    with respx.mock:
        respx.delete("http://testserver/api/v1/files").mock(
            return_value=httpx.Response(204)
        )
        result = delete_file(http, key="files/abc.txt")
        assert "key=files" in str(respx.calls[0].request.url)
        assert result == {}


def test_save_file_raises_on_400(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/files").mock(
            return_value=httpx.Response(400, json={"error": "bad_request"})
        )
        with pytest.raises(ValueError, match="400"):
            save_file(http, content_base64="aGVsbG8=", filename="test.txt")
```

- [ ] **Step 2: Run — verify fail**

```bash
cd mcp_server && pytest tests/test_files.py -v
```

Expected: `ImportError`

- [ ] **Step 3: Write `mcp_server/tools/files.py`**

```python
import base64
import httpx
from client import _check


def save_file(
    http: httpx.Client,
    content_base64: str,
    filename: str,
    mime_type: str = "application/octet-stream",
    person_id: str | None = None,
    household_id: str | None = None,
) -> dict:
    """Upload a file from base64-encoded content and return its storage key."""
    raw = base64.b64decode(content_base64)
    params: dict = {"fileName": filename, "mimeType": mime_type}
    if person_id is not None:
        params["personId"] = person_id
    if household_id is not None:
        params["householdId"] = household_id
    resp = http.post("/api/v1/files", content=raw,
                     headers={"Content-Type": mime_type}, params=params)
    _check(resp)
    return resp.json()


def extract_text_from_file(http: httpx.Client, key: str, mime_type: str) -> dict:
    """Extract plain text from a stored file."""
    resp = http.post("/api/v1/files/extract-text", json={"key": key, "mimeType": mime_type})
    _check(resp)
    return resp.json()


def get_file(http: httpx.Client, key: str) -> dict:
    """Download a stored file and return its content as base64."""
    resp = http.get("/api/v1/files", params={"key": key})
    _check(resp)
    return {"content_base64": base64.b64encode(resp.content).decode()}


def delete_file(http: httpx.Client, key: str) -> dict:
    """Delete a stored file by its storage key."""
    resp = http.delete("/api/v1/files", params={"key": key})
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def save_file_tool(
        content_base64: str,
        filename: str,
        mime_type: str = "application/octet-stream",
        person_id: str | None = None,
        household_id: str | None = None,
    ) -> dict:
        """Upload a file and return its storage key."""
        return save_file(http, content_base64, filename, mime_type, person_id, household_id)

    @mcp.tool()
    def extract_text_from_file_tool(key: str, mime_type: str) -> dict:
        """Extract plain text from a stored file."""
        return extract_text_from_file(http, key, mime_type)

    @mcp.tool()
    def get_file_tool(key: str) -> dict:
        """Download a stored file as base64-encoded content."""
        return get_file(http, key)

    @mcp.tool()
    def delete_file_tool(key: str) -> dict:
        """Delete a stored file by its storage key."""
        return delete_file(http, key)
```

- [ ] **Step 4: Run — verify pass**

```bash
cd mcp_server && pytest tests/test_files.py -v
```

Expected: `5 passed`

- [ ] **Step 5: Commit**

```bash
git add mcp_server/tools/files.py mcp_server/tests/test_files.py
git commit -m "feat(mcp): add file tools with tests"
```

---

## Task 12: Server Entry Point

**Files:**
- Create: `mcp_server/server.py`

- [ ] **Step 1: Write `mcp_server/server.py`**

```python
from mcp.server.fastmcp import FastMCP
from client import make_client
from tools import (
    persons, households, person_household, relationships,
    documents, facts, schemas, reference, audit, files,
)

mcp = FastMCP("myassistant")
http = make_client()

persons.register(mcp, http)
households.register(mcp, http)
person_household.register(mcp, http)
relationships.register(mcp, http)
documents.register(mcp, http)
facts.register(mcp, http)
schemas.register(mcp, http)
reference.register(mcp, http)
audit.register(mcp, http)
files.register(mcp, http)

if __name__ == "__main__":
    mcp.run()
```

- [ ] **Step 2: Verify server imports without error**

```bash
cd mcp_server && python -c "from server import mcp; print('server ok')"
```

Expected output: `server ok`

- [ ] **Step 3: Run full test suite**

```bash
cd mcp_server && pytest tests/ -v
```

Expected: all tests pass (60+ total across all modules)

- [ ] **Step 4: Commit**

```bash
git add mcp_server/server.py
git commit -m "feat(mcp): add server entry point, wire all 43 tools"
```

---

## Task 13: Final Verification + Push

- [ ] **Step 1: Run full test suite one final time**

```bash
cd mcp_server && pytest tests/ -v --tb=short
```

Expected: all tests pass, zero failures

- [ ] **Step 2: Verify server can be described via MCP CLI (optional smoke test)**

```bash
cd mcp_server && python server.py &
# In another terminal or after a brief pause:
# mcp list-tools --server "python server.py"  (if mcp CLI supports it)
kill %1
```

- [ ] **Step 3: Push branch**

```bash
git push origin feature/implement-python-mcp-layer
```
