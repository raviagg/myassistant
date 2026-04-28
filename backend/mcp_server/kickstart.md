# Kickstart — MCP Server

Everything you need to install, run, test, and connect the Python MCP server from a fresh checkout.

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Python | 3.11+ | `brew install python@3.11` |
| pip | any recent | included with Python |

The MCP server connects to the Scala HTTP server. Start that first (see `backend/http_server/kickstart.md`) before running the MCP server or its integration smoke tests.

---

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `MYASSISTANT_BASE_URL` | `http://localhost:8080` | Scala HTTP server base URL |
| `MYASSISTANT_AUTH_TOKEN` | `dev-token-change-me-in-production` | Bearer token sent on every request |

These are read at startup by `client.py`. Override them for any non-local environment:

```bash
export MYASSISTANT_BASE_URL="http://localhost:8080"
export MYASSISTANT_AUTH_TOKEN="dev-token-change-me-in-production"
```

---

## Install

```bash
cd backend/mcp_server

# Install with dev dependencies (pytest + respx for testing)
pip install -e ".[dev]"
```

This installs the `mcp`, `httpx`, `pytest`, and `respx` packages. The `-e` flag installs in editable mode so changes to `tools/` are reflected immediately.

---

## Run the MCP Server

The server communicates over **stdio** (standard MCP transport). It is started by an MCP host (Claude Desktop, Claude Code, MCP Inspector) — you do not run it directly in normal use.

### With Claude Code

Add this to your Claude Code MCP config (`~/.claude/config.json` or equivalent):

```json
{
  "mcpServers": {
    "myassistant": {
      "command": "python",
      "args": ["/absolute/path/to/backend/mcp_server/server.py"],
      "env": {
        "MYASSISTANT_BASE_URL": "http://localhost:8080",
        "MYASSISTANT_AUTH_TOKEN": "dev-token-change-me-in-production"
      }
    }
  }
}
```

### With MCP Inspector (browser UI for manual tool testing)

```bash
cd backend/mcp_server

# Opens browser at http://localhost:6274
# In the UI: set Command = python, Args = /absolute/path/to/backend/mcp_server/server.py
mcp dev server.py
```

If `mcp dev` fails with a typer/uv error, install the CLI extras:

```bash
pip install "mcp[cli]"
```

### Verify the server loads

```bash
cd backend/mcp_server
python -c "from server import mcp; print('server ok')"
```

---

## Project Layout

```
backend/mcp_server/
  client.py          shared httpx.Client + _check() error helper
  server.py          FastMCP entry point — registers all 43 tools
  tools/
    persons.py        5 tools  (list, create, get, update, delete)
    households.py     5 tools
    person_household.py  4 tools
    relationships.py  6 tools  (includes resolve_kinship)
    documents.py      4 tools  (includes search_documents)
    facts.py          5 tools  (includes search_facts)
    schemas.py        6 tools
    reference.py      3 tools
    audit.py          1 tool   (log_interaction)
    files.py          4 tools
  tests/
    conftest.py       shared http fixture (httpx.Client pointing at testserver)
    test_persons.py   7 tests
    test_households.py  6 tests
    test_person_household.py  5 tests
    test_relationships.py  7 tests
    test_documents.py  5 tests
    test_facts.py     6 tests
    test_schemas.py   7 tests
    test_reference.py  4 tests
    test_audit.py     2 tests
    test_files.py     5 tests
  pyproject.toml
```

---

## Tests

Tests use **respx** to mock httpx — no real network calls, no running server required.

```bash
cd backend/mcp_server

# Run all tests
pytest

# Run a specific module
pytest tests/test_persons.py -v

# Run with short traceback on failure
pytest tests/ -v --tb=short
```

Each test verifies:
1. Correct HTTP method and URL path forwarded to the Scala backend
2. Correct request body / query params
3. `Authorization: Bearer <token>` header present
4. Parsed JSON returned on 2xx
5. `ValueError` raised on 4xx, `RuntimeError` on 5xx

---

## Tool Groups

| Group | Tools | Scala endpoints |
|---|---|---|
| Persons | `list_persons`, `create_person`, `get_person`, `update_person`, `delete_person` | `GET/POST/PATCH/DELETE /api/v1/persons` |
| Households | `list_households`, `create_household`, `get_household`, `update_household`, `delete_household` | `GET/POST/PATCH/DELETE /api/v1/households` |
| Person-Household | `add_person_to_household`, `remove_person_from_household`, `list_household_members`, `list_person_households` | `/api/v1/households/{id}/members` |
| Relationships | `create_relationship`, `list_relationships`, `get_relationship`, `update_relationship`, `delete_relationship`, `resolve_kinship` | `/api/v1/relationships` |
| Documents | `create_document`, `list_documents`, `get_document`, `search_documents` | `/api/v1/documents` |
| Facts | `create_fact`, `list_current_facts`, `get_current_fact`, `get_fact_history`, `search_facts` | `/api/v1/facts` |
| Schemas | `list_schemas`, `create_schema`, `get_current_schemas`, `get_schema`, `add_schema_version`, `deactivate_schema` | `/api/v1/schemas` |
| Reference | `list_domains`, `list_source_types`, `list_kinship_aliases` | `/api/v1/reference` |
| Audit | `log_interaction` | `POST /api/v1/audit/interactions` |
| Files | `save_file`, `extract_text_from_file`, `list_files`, `delete_file` | `/api/v1/files` |

**Total: 43 tools.** The health endpoint is excluded — not useful as an MCP tool.

---

## Error Handling

| HTTP status | Behaviour |
|---|---|
| 2xx | Return parsed JSON body as dict |
| 4xx | Raise `ValueError("{status}: {body}")` — surfaces as tool error in the MCP host |
| 5xx | Raise `RuntimeError("{status}: {body}")` |
| Network failure | Let httpx exception propagate with its own message |
