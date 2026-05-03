# Local Embeddings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hardcoded `[0.1, 0.2, 0.3]` embedding placeholder with real vectors generated locally by `sentence-transformers`, making semantic search actually work.

**Architecture:** A new `embeddings.py` module in the Python MCP server loads `BAAI/bge-base-en-v1.5` (768-dim) as a module-level singleton and exposes a single `embed(text) -> list[float]` function. The four MCP tools that currently require the LLM to supply an `embedding: list` parameter are updated: write tools (`create_document`, `create_fact`) generate embeddings internally from existing text parameters; search tools (`search_documents`, `search_current_facts`) replace `embedding: list` with `query_text: str`. The Scala HTTP server contract is unchanged — it still receives a `float[]` vector. A DB migration resizes both `VECTOR(1536)` columns to `VECTOR(768)` to match the new model.

**Tech Stack:** Python `sentence-transformers>=3.0`, `BAAI/bge-base-en-v1.5` (768 dims, ~430MB, downloads from HuggingFace on first use), PostgreSQL `pgvector`, Flyway migrations (Scala).

---

## File Map

| Action | File | What changes |
|---|---|---|
| Create | `backend/mcp_server/tools/embeddings.py` | Singleton model loader + `embed()` |
| Modify | `backend/mcp_server/pyproject.toml` | Add `sentence-transformers>=3.0` dependency |
| Create | `backend/http_server/src/main/resources/db/migration/V9__resize_embeddings.sql` | Resize VECTOR(1536)→VECTOR(768) for document + fact |
| Modify | `backend/mcp_server/tools/documents.py` | Remove `embedding` from `create_document`; replace with `query_text` in `search_documents` |
| Modify | `backend/mcp_server/tools/facts.py` | Remove `embedding` from `create_fact`; replace with `query_text` in `search_current_facts` |
| Modify | `client/common/tool_definitions.py` | Update schemas for all 4 affected tools |
| Modify | `client/common/system_prompt.py` | Remove embedding placeholder instructions |
| Modify | `docs/mcp-tools.md` | Update parameter docs for the 4 tools |

---

## Task 1: DB migration — resize embedding columns to 768 dims

pgvector does not support `ALTER COLUMN` type changes for vector columns. The migration drops and re-adds both `embedding` columns. Existing data had fake `[0.1, 0.2, 0.3]` embeddings so data loss is intentional.

**Files:**
- Create: `backend/http_server/src/main/resources/db/migration/V9__resize_embeddings.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V9__resize_embeddings.sql
-- Resize vector columns from 1536 (OpenAI ada-002 placeholder) to 768
-- (BAAI/bge-base-en-v1.5). pgvector does not support ALTER COLUMN for
-- vector types, so we drop and re-add. Existing embeddings were all
-- [0.1, 0.2, 0.3] placeholders — intentional data loss.

-- document
DROP INDEX IF EXISTS idx_document_embedding;
ALTER TABLE document DROP COLUMN IF EXISTS embedding;
ALTER TABLE document ADD COLUMN embedding VECTOR(768);
CREATE INDEX idx_document_embedding
  ON document USING hnsw(embedding vector_cosine_ops);

-- fact
DROP INDEX IF EXISTS idx_fact_embedding;
ALTER TABLE fact DROP COLUMN IF EXISTS embedding;
ALTER TABLE fact ADD COLUMN embedding VECTOR(768);
CREATE INDEX idx_fact_embedding
  ON fact USING hnsw(embedding vector_cosine_ops);
```

- [ ] **Step 2: Verify migration runs cleanly**

With the Scala HTTP server running (or via `psql` directly):
```bash
# Start the server — Flyway runs migrations on boot
cd backend/http_server && sbt run
# Expected in logs: "Successfully applied 1 migration to schema"
```

Or verify in psql:
```sql
SELECT column_name, data_type, udt_name
FROM information_schema.columns
WHERE table_name IN ('document', 'fact')
  AND column_name = 'embedding';
-- Should show udt_name = 'vector' for both rows
-- (pgvector stores dimension in the type itself — confirm with \d document)

\d document
-- embedding column should read: vector(768)
\d fact
-- embedding column should read: vector(768)
```

- [ ] **Step 3: Commit**

```bash
git add backend/http_server/src/main/resources/db/migration/V9__resize_embeddings.sql
git commit -m "feat(db): resize embedding columns from 1536 to 768 dims for bge-base model"
```

---

## Task 2: Embedding utility module

**Files:**
- Create: `backend/mcp_server/tools/embeddings.py`
- Modify: `backend/mcp_server/pyproject.toml`

- [ ] **Step 1: Add sentence-transformers dependency**

Edit `backend/mcp_server/pyproject.toml` — add to `dependencies`:

```toml
[project]
name = "myassistant-mcp"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = [
    "mcp>=1.0",
    "httpx>=0.27",
    "sentence-transformers>=3.0",
]
```

- [ ] **Step 2: Write the failing test**

Create `backend/mcp_server/tests/test_embeddings.py`:

```python
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
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend/mcp_server
pip install -e ".[dev]"
pytest tests/test_embeddings.py -v
# Expected: ModuleNotFoundError or ImportError — embeddings.py does not exist yet
```

- [ ] **Step 4: Write the implementation**

Create `backend/mcp_server/tools/embeddings.py`:

```python
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
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd backend/mcp_server
pytest tests/test_embeddings.py -v
# Expected: 3 passed (model downloads ~430MB on first run)
```

- [ ] **Step 6: Commit**

```bash
git add backend/mcp_server/pyproject.toml backend/mcp_server/tools/embeddings.py backend/mcp_server/tests/test_embeddings.py
git commit -m "feat(mcp): add local embedding utility using bge-base-en-v1.5 (768 dims)"
```

---

## Task 3: Update documents.py — remove embedding from interface

**Files:**
- Modify: `backend/mcp_server/tools/documents.py`

- [ ] **Step 1: Write the failing test**

Create `backend/mcp_server/tests/test_documents_embed.py`:

```python
import respx
import httpx
import pytest
from tools.documents import create_document, search_documents


@respx.mock
def test_create_document_generates_embedding_internally():
    """create_document must NOT require an embedding param and must post a 768-dim vector."""
    captured = {}

    def capture(request, route):
        import json
        captured["body"] = json.loads(request.content)
        return httpx.Response(201, json={"id": "doc-1", "contentText": "hello", "embedding": []})

    respx.post("http://test/api/v1/documents").mock(side_effect=capture)

    http = httpx.Client(base_url="http://test")
    create_document(http, content_text="hello world", source_type_id="src-1", person_id="p-1")

    assert "embedding" in captured["body"]
    assert len(captured["body"]["embedding"]) == 768


@respx.mock
def test_search_documents_accepts_query_text():
    """search_documents takes query_text: str, not embedding: list."""
    captured = {}

    def capture(request, route):
        import json
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={"results": []})

    respx.post("http://test/api/v1/documents/search").mock(side_effect=capture)

    http = httpx.Client(base_url="http://test")
    search_documents(http, query_text="passport details", person_id="p-1")

    assert "embedding" in captured["body"]
    assert len(captured["body"]["embedding"]) == 768
    assert "queryText" not in captured["body"]
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend/mcp_server
pytest tests/test_documents_embed.py -v
# Expected: TypeError — create_document still requires embedding param
```

- [ ] **Step 3: Implement the changes**

Replace `backend/mcp_server/tools/documents.py` with:

```python
import httpx
from client import _check
from tools.embeddings import embed


def create_document(
    http: httpx.Client,
    content_text: str,
    source_type_id: str,
    person_id: str | None = None,
    household_id: str | None = None,
    supersedes_ids: list | None = None,
    files: list | None = None,
) -> dict:
    body: dict = {
        "contentText": content_text,
        "sourceTypeId": source_type_id,
        "embedding": embed(content_text),
        "supersedesIds": supersedes_ids or [],
        "files": files or [],
    }
    if person_id is not None:
        body["personId"] = person_id
    if household_id is not None:
        body["householdId"] = household_id
    resp = http.post("/api/v1/documents", json=body)
    _check(resp)
    return resp.json()


def get_document(http: httpx.Client, document_id: str) -> dict:
    resp = http.get(f"/api/v1/documents/{document_id}")
    _check(resp)
    return resp.json()


def list_documents(
    http: httpx.Client,
    person_id: str | None = None,
    household_id: str | None = None,
    source_type_id: str | None = None,
    created_after: str | None = None,
    created_before: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict:
    params: dict = {"limit": limit, "offset": offset}
    if person_id is not None:
        params["personId"] = person_id
    if household_id is not None:
        params["householdId"] = household_id
    if source_type_id is not None:
        params["sourceTypeId"] = source_type_id
    if created_after is not None:
        params["createdAfter"] = created_after
    if created_before is not None:
        params["createdBefore"] = created_before
    resp = http.get("/api/v1/documents", params=params)
    _check(resp)
    return resp.json()


def search_documents(
    http: httpx.Client,
    query_text: str,
    person_id: str | None = None,
    household_id: str | None = None,
    source_type_id: str | None = None,
    limit: int = 10,
    similarity_threshold: float = 0.7,
) -> dict:
    body: dict = {
        "embedding": embed(query_text),
        "limit": limit,
        "similarityThreshold": similarity_threshold,
    }
    if person_id is not None:
        body["personId"] = person_id
    if household_id is not None:
        body["householdId"] = household_id
    if source_type_id is not None:
        body["sourceTypeId"] = source_type_id
    resp = http.post("/api/v1/documents/search", json=body)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="create_document")
    def _create_tool(
        content_text: str,
        source_type_id: str,
        person_id: str | None = None,
        household_id: str | None = None,
        supersedes_ids: list | None = None,
        files: list | None = None,
    ) -> dict:
        """Persist a new immutable document. Embedding is generated automatically from content_text. At least one of person_id or household_id must be provided."""
        return create_document(http, content_text, source_type_id, person_id, household_id, supersedes_ids, files)

    @mcp.tool(name="get_document")
    def _get_tool(document_id: str) -> dict:
        """Fetch a single document by UUID."""
        return get_document(http, document_id)

    @mcp.tool(name="list_documents")
    def _list_tool(
        person_id: str | None = None,
        household_id: str | None = None,
        source_type_id: str | None = None,
        created_after: str | None = None,
        created_before: str | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> dict:
        """Filter-based listing of documents."""
        return list_documents(http, person_id, household_id, source_type_id, created_after, created_before, limit, offset)

    @mcp.tool(name="search_documents")
    def _search_tool(
        query_text: str,
        person_id: str | None = None,
        household_id: str | None = None,
        source_type_id: str | None = None,
        limit: int = 10,
        similarity_threshold: float = 0.7,
    ) -> dict:
        """Vector similarity search over documents using a natural language query."""
        return search_documents(http, query_text, person_id, household_id, source_type_id, limit, similarity_threshold)
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend/mcp_server
pytest tests/test_documents_embed.py -v
# Expected: 2 passed
```

- [ ] **Step 5: Commit**

```bash
git add backend/mcp_server/tools/documents.py backend/mcp_server/tests/test_documents_embed.py
git commit -m "feat(mcp): auto-generate embeddings in create_document and search_documents"
```

---

## Task 4: Update facts.py — remove embedding from interface

**Files:**
- Modify: `backend/mcp_server/tools/facts.py`

- [ ] **Step 1: Write the failing test**

Create `backend/mcp_server/tests/test_facts_embed.py`:

```python
import json
import respx
import httpx
from tools.facts import create_fact, search_current_facts


@respx.mock
def test_create_fact_generates_embedding_internally():
    """create_fact must NOT require an embedding param and must post a 768-dim vector."""
    captured = {}

    def capture(request, route):
        captured["body"] = json.loads(request.content)
        return httpx.Response(201, json={"id": "fact-1"})

    respx.post("http://test/api/v1/facts").mock(side_effect=capture)

    http = httpx.Client(base_url="http://test")
    create_fact(
        http,
        document_id="doc-1",
        schema_id="schema-1",
        entity_instance_id="inst-1",
        operation_type="create",
        fields={"title": "Renew passport", "status": "pending"},
    )

    assert "embedding" in captured["body"]
    assert len(captured["body"]["embedding"]) == 768


@respx.mock
def test_search_current_facts_accepts_query_text():
    """search_current_facts takes query_text: str, not embedding: list."""
    captured = {}

    def capture(request, route):
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={"results": []})

    respx.post("http://test/api/v1/facts/search").mock(side_effect=capture)

    http = httpx.Client(base_url="http://test")
    search_current_facts(http, query_text="passport renewal task", person_id="p-1")

    assert "embedding" in captured["body"]
    assert len(captured["body"]["embedding"]) == 768
    assert "queryText" not in captured["body"]
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend/mcp_server
pytest tests/test_facts_embed.py -v
# Expected: TypeError — create_fact still requires embedding param
```

- [ ] **Step 3: Implement the changes**

Replace `backend/mcp_server/tools/facts.py` with:

```python
import json
import httpx
from client import _check
from tools.embeddings import embed


def create_fact(
    http: httpx.Client,
    document_id: str,
    schema_id: str,
    entity_instance_id: str,
    operation_type: str,
    fields: dict,
) -> dict:
    body: dict = {
        "documentId": document_id,
        "schemaId": schema_id,
        "entityInstanceId": entity_instance_id,
        "operationType": operation_type,
        "fields": fields,
        "embedding": embed(json.dumps(fields, sort_keys=True)),
    }
    resp = http.post("/api/v1/facts", json=body)
    _check(resp)
    return resp.json()


def get_fact_history(http: httpx.Client, entity_instance_id: str) -> dict:
    resp = http.get(f"/api/v1/facts/{entity_instance_id}/history")
    _check(resp)
    return resp.json()


def get_current_fact(http: httpx.Client, entity_instance_id: str) -> dict:
    resp = http.get(f"/api/v1/facts/{entity_instance_id}/current")
    _check(resp)
    return resp.json()


def list_current_facts(
    http: httpx.Client,
    person_id: str | None = None,
    household_id: str | None = None,
    domain_id: str | None = None,
    entity_type: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict:
    params: dict = {"limit": limit, "offset": offset}
    if person_id is not None:
        params["personId"] = person_id
    if household_id is not None:
        params["householdId"] = household_id
    if domain_id is not None:
        params["domainId"] = domain_id
    if entity_type is not None:
        params["entityType"] = entity_type
    resp = http.get("/api/v1/facts/current", params=params)
    _check(resp)
    return resp.json()


def search_current_facts(
    http: httpx.Client,
    query_text: str,
    person_id: str | None = None,
    household_id: str | None = None,
    domain_id: str | None = None,
    entity_type: str | None = None,
    limit: int = 10,
    similarity_threshold: float = 0.7,
) -> dict:
    body: dict = {
        "embedding": embed(query_text),
        "limit": limit,
        "similarityThreshold": similarity_threshold,
    }
    if person_id is not None:
        body["personId"] = person_id
    if household_id is not None:
        body["householdId"] = household_id
    if domain_id is not None:
        body["domainId"] = domain_id
    if entity_type is not None:
        body["entityType"] = entity_type
    resp = http.post("/api/v1/facts/search", json=body)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="create_fact")
    def _create_tool(
        document_id: str,
        schema_id: str,
        entity_instance_id: str,
        operation_type: str,
        fields: dict,
    ) -> dict:
        """Persist a single fact operation. Embedding is generated automatically from fields. For 'update'/'delete': resolve entity_instance_id first via search_current_facts."""
        return create_fact(http, document_id, schema_id, entity_instance_id, operation_type, fields)

    @mcp.tool(name="get_fact_history")
    def _history_tool(entity_instance_id: str) -> dict:
        """Retrieve the full operation history for a single entity instance."""
        return get_fact_history(http, entity_instance_id)

    @mcp.tool(name="get_current_fact")
    def _current_tool(entity_instance_id: str) -> dict:
        """Retrieve the merged current state for a single entity instance."""
        return get_current_fact(http, entity_instance_id)

    @mcp.tool(name="list_current_facts")
    def _list_tool(
        person_id: str | None = None,
        household_id: str | None = None,
        domain_id: str | None = None,
        entity_type: str | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> dict:
        """Filter-based listing of current entity states."""
        return list_current_facts(http, person_id, household_id, domain_id, entity_type, limit, offset)

    @mcp.tool(name="search_current_facts")
    def _search_tool(
        query_text: str,
        person_id: str | None = None,
        household_id: str | None = None,
        domain_id: str | None = None,
        entity_type: str | None = None,
        limit: int = 10,
        similarity_threshold: float = 0.7,
    ) -> dict:
        """Vector similarity search over current entity states using a natural language query. PRIMARY tool for resolving entity_instance_id by description."""
        return search_current_facts(http, query_text, person_id, household_id, domain_id, entity_type, limit, similarity_threshold)
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend/mcp_server
pytest tests/test_facts_embed.py -v
# Expected: 2 passed
```

- [ ] **Step 5: Commit**

```bash
git add backend/mcp_server/tools/facts.py backend/mcp_server/tests/test_facts_embed.py
git commit -m "feat(mcp): auto-generate embeddings in create_fact and search_current_facts"
```

---

## Task 5: Update tool_definitions.py and system_prompt.py

**Files:**
- Modify: `client/common/tool_definitions.py`
- Modify: `client/common/system_prompt.py`

- [ ] **Step 1: Update create_document schema in tool_definitions.py**

In `client/common/tool_definitions.py`, replace the `create_document` entry (lines ~306–343):

```python
{
    "name": "create_document",
    "description": (
        "Persist a new immutable document. Every piece of information — typed by a user, "
        "uploaded as a file, or sent by a polling job — becomes a document first. "
        "Documents are never updated or deleted. "
        "Embedding is generated automatically from content_text. "
        "At least one of person_id or household_id must be provided."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "content_text": {"type": "string", "description": "Full natural language content"},
            "source_type_id": {
                "type": "string",
                "description": "UUID from list_source_types (e.g. user_input, gmail_poll)",
            },
            "person_id": {"type": "string", "description": "Owner person (at least one of person/household required)"},
            "household_id": {"type": "string"},
            "supersedes_ids": {
                "type": "array",
                "items": {"type": "string"},
                "description": "UUIDs of documents this one replaces",
            },
            "files": {
                "type": "array",
                "items": {"type": "object"},
                "description": "Attached file references, each with file_path and file_type",
            },
        },
        "required": ["content_text", "source_type_id"],
    },
},
```

- [ ] **Step 2: Update search_documents schema in tool_definitions.py**

Replace the `search_documents` entry (lines ~372–398):

```python
{
    "name": "search_documents",
    "description": (
        "Vector similarity search over documents. "
        "Used to find documents semantically related to a query — "
        "e.g. identifying which existing documents a new one might supersede."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "query_text": {
                "type": "string",
                "description": "Natural language search query",
            },
            "person_id": {"type": "string"},
            "household_id": {"type": "string"},
            "source_type_id": {"type": "string"},
            "limit": {"type": "integer", "description": "Default 10"},
            "similarity_threshold": {
                "type": "number",
                "description": "Minimum cosine similarity 0-1. Default 0.7",
            },
        },
        "required": ["query_text"],
    },
},
```

- [ ] **Step 3: Update create_fact schema in tool_definitions.py**

Replace the `create_fact` entry (lines ~400–437):

```python
{
    "name": "create_fact",
    "description": (
        "Persist a single fact operation extracted from a document. "
        "Facts are append-only — never updated or deleted in place. "
        "For operation_type='create': generate a fresh UUID for entity_instance_id. "
        "For 'update' or 'delete': first resolve the entity_instance_id via search_current_facts. "
        "Embedding is generated automatically from the fields."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "document_id": {"type": "string", "description": "Source document UUID (provenance)"},
            "schema_id": {"type": "string", "description": "UUID from entity_type_schema"},
            "entity_instance_id": {
                "type": "string",
                "description": (
                    "Stable UUID identifying this logical entity. "
                    "Generate a new UUID for 'create'; reuse the same UUID for 'update'/'delete'."
                ),
            },
            "operation_type": {
                "type": "string",
                "enum": ["create", "update", "delete"],
            },
            "fields": {
                "type": "object",
                "description": "JSONB field values. Only changed fields needed for updates.",
            },
        },
        "required": ["document_id", "schema_id", "entity_instance_id", "operation_type", "fields"],
    },
},
```

- [ ] **Step 4: Update search_current_facts schema in tool_definitions.py**

Replace the `search_current_facts` entry (lines ~486–512):

```python
{
    "name": "search_current_facts",
    "description": (
        "Vector similarity search over current entity states. "
        "PRIMARY tool for resolving entity_instance_id from natural language — "
        "use this when the user refers to an entity by description "
        "(e.g. 'my passport renewal', 'the Aetna policy'). "
        "Returns merged current state with similarity scores. Only active entities returned."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "query_text": {
                "type": "string",
                "description": "Natural language description of the entity to find",
            },
            "person_id": {"type": "string"},
            "household_id": {"type": "string"},
            "domain_id": {"type": "string"},
            "entity_type": {"type": "string"},
            "limit": {"type": "integer", "description": "Default 10"},
            "similarity_threshold": {"type": "number", "description": "Default 0.7"},
        },
        "required": ["query_text"],
    },
},
```

- [ ] **Step 5: Update system_prompt.py — remove placeholder instructions**

In `client/common/system_prompt.py`, replace:

```python
TEST_PROMPT_ADDENDUM = """

EMBEDDING PARAMETERS: For any embedding parameter, pass [0.1, 0.2, 0.3] as placeholder.
For entity_instance_id on a new create, use a descriptive placeholder like "NEW-UUID-passport-renewal".
For UUID values from earlier tool calls, use placeholders like "DOMAIN-ID-FROM-LIST-DOMAINS"."""


# Appended by chatbot — same placeholder for now; real embeddings are a future TODO.
CHATBOT_PROMPT_ADDENDUM = """

EMBEDDINGS: Pass [0.1, 0.2, 0.3] as a placeholder for all embedding parameters.
Real vector generation will be integrated in a future release.

ENTITY IDs: When creating a new fact (operation_type="create"), generate a fresh UUID v4
for entity_instance_id in the format xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx."""
```

With:

```python
TEST_PROMPT_ADDENDUM = """

For entity_instance_id on a new create, use a descriptive placeholder like "NEW-UUID-passport-renewal".
For UUID values from earlier tool calls, use placeholders like "DOMAIN-ID-FROM-LIST-DOMAINS"."""


CHATBOT_PROMPT_ADDENDUM = """

ENTITY IDs: When creating a new fact (operation_type="create"), generate a fresh UUID v4
for entity_instance_id in the format xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx."""
```

- [ ] **Step 6: Commit**

```bash
git add client/common/tool_definitions.py client/common/system_prompt.py
git commit -m "feat(client): remove embedding params from tool schemas; use query_text for searches"
```

---

## Task 6: Update mcp-tools.md

**Files:**
- Modify: `docs/mcp-tools.md`

- [ ] **Step 1: Update create_document tool doc**

Find the `create_document` parameter table in `docs/mcp-tools.md` and remove the `embedding` row. Change the description to note embedding is auto-generated:

```markdown
### `create_document`

**Purpose:** Persist a new immutable document ... Embedding is generated automatically from `content_text`.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `content_text` | string | yes | Full natural language content |
| `source_type_id` | UUID | yes | From `list_source_types` |
| `person_id` | UUID | no | At least one of person/household required |
| `household_id` | UUID | no | |
| `supersedes_ids` | UUID[] | no | Documents this one replaces |
| `files` | object[] | no | Attached file references |
```

- [ ] **Step 2: Update search_documents tool doc**

Replace `embedding: list` with `query_text: string` in the parameter table:

```markdown
### `search_documents`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `query_text` | string | yes | Natural language search query |
| `person_id` | UUID | no | |
| `household_id` | UUID | no | |
| `source_type_id` | UUID | no | |
| `limit` | integer | no | Default 10 |
| `similarity_threshold` | float | no | Default 0.7 |
```

- [ ] **Step 3: Update create_fact tool doc**

Remove `embedding` row from parameter table:

```markdown
### `create_fact`

**Purpose:** Persist a single fact operation ... Embedding is generated automatically from `fields`.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `document_id` | UUID | yes | Source document (provenance) |
| `schema_id` | UUID | yes | From `entity_type_schema` |
| `entity_instance_id` | UUID | yes | Stable ID for this logical entity |
| `operation_type` | enum | yes | `create` \| `update` \| `delete` |
| `fields` | object | yes | JSONB field values |
```

- [ ] **Step 4: Update search_current_facts tool doc**

Replace `embedding: list` with `query_text: string`:

```markdown
### `search_current_facts`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `query_text` | string | yes | Natural language description of the entity to find |
| `person_id` | UUID | no | |
| `household_id` | UUID | no | |
| `domain_id` | UUID | no | |
| `entity_type` | string | no | e.g. `todo_item`, `insurance_card` |
| `limit` | integer | no | Default 10 |
| `similarity_threshold` | float | no | Default 0.7 |
```

- [ ] **Step 5: Commit**

```bash
git add docs/mcp-tools.md
git commit -m "docs: update mcp-tools.md — remove embedding params, add query_text to search tools"
```

---

## Task 7: Push branch

- [ ] **Step 1: Push to remote**

```bash
git push -u origin feature/local-embeddings
```
