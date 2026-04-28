# Python MCP Layer — Design Spec
_Date: 2026-04-26_

## Overview

A thin Python MCP server that exposes every Scala HTTP endpoint as an MCP tool. One tool per REST endpoint; argument names and types match the REST request exactly. No business logic lives here — every tool is a direct HTTP proxy call.

---

## Repository Layout

```
myassistant/
  backend/                    ← top-level backend folder
    http_server/              ← Scala HTTP server (ZIO + zio-http)
  mcp_server/               ← Python MCP server (FastMCP)
    server.py                 ← FastMCP app, imports and registers all tool modules
    client.py                 ← shared httpx.Client + auth config
    tools/
      persons.py              ← 5 tools
      households.py           ← 5 tools
      person_household.py     ← 4 tools
      relationships.py        ← 6 tools
      documents.py            ← 4 tools
      facts.py                ← 5 tools
      schemas.py              ← 6 tools
      reference.py            ← 3 tools
      audit.py                ← 1 tool
      files.py                ← 4 tools
    tests/
      conftest.py             ← shared pytest fixtures (mock httpx transport)
      test_persons.py
      test_households.py
      test_person_household.py
      test_relationships.py
      test_documents.py
      test_facts.py
      test_schemas.py
      test_reference.py
      test_audit.py
      test_files.py
    pyproject.toml
```

---

## Architecture

```
Claude / MCP client
      │  MCP JSON-RPC (stdio)
      ▼
  server.py  (FastMCP)
      │  calls tool function
      ▼
  tools/<group>.py
      │  builds URL + body
      ▼
  client.py  (httpx.Client)
      │  HTTP + Bearer token
      ▼
  Scala backend  :8080
```

Transport is **stdio** (default for local MCP servers). The FastMCP app is started with `python -m mcp.server` or via `mcp run backend/mcp_server/server.py`.

---

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `MYASSISTANT_BASE_URL` | `http://localhost:8080` | Scala backend base URL |
| `MYASSISTANT_AUTH_TOKEN` | `dev-token-change-me-in-production` | Bearer token |

`client.py` reads these at startup and injects `Authorization: Bearer <token>` on every request.

---

## Tool-to-Endpoint Mapping

### Group 1a — Person (5 tools)

| Tool | Method | Path | Body / Query params |
|---|---|---|---|
| `list_persons` | GET | `/api/v1/persons` | query: `household_id`, `limit`, `offset` |
| `create_person` | POST | `/api/v1/persons` | body: `full_name`, `gender`, `date_of_birth?`, `preferred_name?`, `user_identifier?` |
| `get_person` | GET | `/api/v1/persons/{person_id}` | path: `person_id` |
| `update_person` | PATCH | `/api/v1/persons/{person_id}` | path: `person_id`; body: all optional |
| `delete_person` | DELETE | `/api/v1/persons/{person_id}` | path: `person_id` |

### Group 1b — Household (5 tools)

| Tool | Method | Path | Params |
|---|---|---|---|
| `list_households` | GET | `/api/v1/households` | — |
| `create_household` | POST | `/api/v1/households` | body: `name` |
| `get_household` | GET | `/api/v1/households/{household_id}` | path |
| `update_household` | PATCH | `/api/v1/households/{household_id}` | path + body: `name?` |
| `delete_household` | DELETE | `/api/v1/households/{household_id}` | path |

### Group 1c — Person-Household (4 tools)

| Tool | Method | Path | Params |
|---|---|---|---|
| `add_person_to_household` | PUT | `/api/v1/households/{household_id}/members/{person_id}` | path |
| `remove_person_from_household` | DELETE | `/api/v1/households/{household_id}/members/{person_id}` | path |
| `list_household_members` | GET | `/api/v1/households/{household_id}/members` | path |
| `list_person_households` | GET | `/api/v1/persons/{person_id}/households` | path |

### Group 1d — Relationship (6 tools)

| Tool | Method | Path | Params |
|---|---|---|---|
| `create_relationship` | POST | `/api/v1/relationships` | body: `from_person_id`, `to_person_id`, `relation_type` |
| `list_relationships` | GET | `/api/v1/relationships` | query: `from_person_id?`, `to_person_id?` |
| `get_relationship` | GET | `/api/v1/relationships/{from_id}/{to_id}` | path |
| `update_relationship` | PATCH | `/api/v1/relationships/{from_id}/{to_id}` | path + body: `relation_type` |
| `delete_relationship` | DELETE | `/api/v1/relationships/{from_id}/{to_id}` | path |
| `resolve_kinship` | GET | `/api/v1/relationships/{from_id}/{to_id}/kinship` | path |

### Group 2a — Document (4 tools)

| Tool | Method | Path | Params |
|---|---|---|---|
| `create_document` | POST | `/api/v1/documents` | body: `person_id?`, `household_id?`, `content_text`, `source_type`, `files`, `supersedes_ids` |
| `list_documents` | GET | `/api/v1/documents` | query: `person_id?`, `household_id?`, `source_type?`, `limit`, `offset` |
| `get_document` | GET | `/api/v1/documents/{document_id}` | path |
| `search_documents` | POST | `/api/v1/documents/search` | body: `query`, `person_id?`, `household_id?`, `limit?` |

### Group 2b — Fact (5 tools)

| Tool | Method | Path | Params |
|---|---|---|---|
| `create_fact` | POST | `/api/v1/facts` | body: `document_id`, `schema_id`, `entity_instance_id?`, `operation_type`, `fields` |
| `list_current_facts` | GET | `/api/v1/facts/current` | query: `schema_id`, `limit`, `offset` |
| `get_current_fact` | GET | `/api/v1/facts/{entity_id}/current` | path |
| `get_fact_history` | GET | `/api/v1/facts/{entity_id}/history` | path |
| `search_facts` | POST | `/api/v1/facts/search` | body: `query`, `domain?`, `limit?` |

### Group 3 — Schema (6 tools)

| Tool | Method | Path | Params |
|---|---|---|---|
| `list_schemas` | GET | `/api/v1/schemas` | — |
| `create_schema` | POST | `/api/v1/schemas` | body: `domain`, `entity_type`, `description`, `field_definitions`, `extraction_prompt`, `change_description?` |
| `get_current_schemas` | GET | `/api/v1/schemas/current` | — |
| `get_schema` | GET | `/api/v1/schemas/{schema_id}` | path |
| `add_schema_version` | POST | `/api/v1/schemas/{domain}/{entity_type}/versions` | path + same body as create |
| `deactivate_schema` | DELETE | `/api/v1/schemas/{domain}/{entity_type}/active` | path |

### Group 4 — Reference (3 tools)

| Tool | Method | Path |
|---|---|---|
| `list_domains` | GET | `/api/v1/reference/domains` |
| `list_source_types` | GET | `/api/v1/reference/source-types` |
| `list_kinship_aliases` | GET | `/api/v1/reference/kinship-aliases` |

### Group 5 — Audit (1 tool)

| Tool | Method | Path | Params |
|---|---|---|---|
| `log_interaction` | POST | `/api/v1/audit/interactions` | body: `person_id?`, `job_type?`, `message`, `response?`, `tool_calls`, `status`, `error?` |

### Group 6 — Files (4 tools)

| Tool | Method | Path | Params |
|---|---|---|---|
| `save_file` | POST | `/api/v1/files` | multipart: `file`, query: `person_id?`, `household_id?` |
| `extract_text_from_file` | POST | `/api/v1/files/extract-text` | body: `key`, `mime_type` |
| `list_files` | GET | `/api/v1/files` | query: `person_id?`, `household_id?` |
| `delete_file` | DELETE | `/api/v1/files` | query: `person_id?`, `household_id?` |

**Total: 43 tools** (health endpoint excluded — not useful as an MCP tool).

---

## Error Handling

- HTTP 2xx → return parsed JSON body as dict
- HTTP 4xx → raise `ValueError` with `"<STATUS>: <body>"` so MCP surfaces it as a tool error
- HTTP 5xx → raise `RuntimeError` with same format
- Network error (connection refused, timeout) → let `httpx` exception propagate with a clear message

---

## Testing

- **Framework:** pytest + pytest-asyncio
- **HTTP mocking:** `httpx` `MockTransport` / `respx` library — no real network calls
- **One test per tool** (44 total) verifying:
  1. Correct HTTP method and URL path
  2. Correct request body / query params forwarded
  3. Auth header present
  4. Returns parsed response on 200
  5. Raises on 4xx / 5xx
- `conftest.py` provides a `mock_client` fixture that patches `client.py`'s httpx client

---

## Dependencies (`pyproject.toml`)

```toml
[project]
name = "myassistant-mcp"
requires-python = ">=3.11"

[project.dependencies]
mcp = ">=1.0"
httpx = ">=0.27"

[project.optional-dependencies]
dev = ["pytest", "pytest-asyncio", "respx"]
```
