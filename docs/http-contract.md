# HTTP Contract — myassistant Scala Backend

**Base URL:** `http://host:port/api/v1`  
**Auth:** Every endpoint requires `Authorization: Bearer <token>`. Requests without a valid token return `401 Unauthorized`.  
**Content-Type:** `application/json` for all request and response bodies.  
**Versioning:** URL path — current version is `v1`.

---

## Table of Contents

| Group | Endpoints |
|---|---|
| [1a — Person](#group-1a--person) | 5 |
| [1b — Household](#group-1b--household) | 5 |
| [1c — Person-Household](#group-1c--person-household) | 4 |
| [1d — Relationship](#group-1d--relationship) | 6 |
| [2a — Document](#group-2a--document) | 4 |
| [2b — Fact](#group-2b--fact) | 5 |
| [3 — Schema Governance](#group-3--schema-governance) | 6 |
| [4 — Reference](#group-4--reference) | 3 |
| [5 — Audit](#group-5--audit) | 1 |
| [6 — File Handling](#group-6--file-handling) | 4 |
| **Total** | **43** |

---

## Common Conventions

### Standard error envelope

All error responses use this shape:

```json
{
  "error": "error_code",
  "message": "Human-readable explanation",
  "details": {}
}
```

### Common HTTP status codes

| Code | Meaning |
|---|---|
| `200 OK` | Successful read or update |
| `201 Created` | Resource created successfully |
| `204 No Content` | Successful delete or void operation |
| `400 Bad Request` | Invalid request body or query parameter |
| `401 Unauthorized` | Missing or invalid Bearer token |
| `404 Not Found` | Requested resource does not exist |
| `409 Conflict` | Unique constraint violation |
| `422 Unprocessable Entity` | Business rule violation (e.g. referential integrity) |
| `500 Internal Server Error` | Unexpected server error |

### Field definitions object (used in schema tools)

Each element of `field_definitions` has this shape:

```json
{
  "name": "premium",
  "type": "number",
  "required": false,
  "description": "Monthly premium amount"
}
```

Valid `type` values: `text`, `number`, `date`, `boolean`, `file`.

---

## Group 1a — Person

### `POST /api/v1/persons`

**MCP tool:** `create_person`

Register a new person.

**Request body:**
```json
{
  "full_name": "Ravi Aggarwal",
  "gender": "male",
  "date_of_birth": "1990-03-15",
  "preferred_name": "Ravi",
  "user_identifier": "raaggarw"
}
```

| Field | Type | Required |
|---|---|---|
| `full_name` | string | yes |
| `gender` | `"male"` \| `"female"` | yes |
| `date_of_birth` | date (ISO 8601 `YYYY-MM-DD`) | no |
| `preferred_name` | string | no |
| `user_identifier` | string | no |

**Response `201`:**
```json
{
  "id": "uuid",
  "full_name": "Ravi Aggarwal",
  "gender": "male",
  "date_of_birth": "1990-03-15",
  "preferred_name": "Ravi",
  "user_identifier": "raaggarw",
  "created_at": "2026-04-25T10:00:00Z",
  "updated_at": "2026-04-25T10:00:00Z"
}
```

**Errors:** `400`, `409` (duplicate `user_identifier`)

---

### `GET /api/v1/persons/{person_id}`

**MCP tool:** `get_person`

Fetch a single person by UUID.

**Path parameter:** `person_id` — UUID

**Response `200`:** Full person row (same shape as create response).

**Errors:** `404`

---

### `GET /api/v1/persons`

**MCP tool:** `search_persons`

Filter-based person search. All query parameters are optional and ANDed together.

**Query parameters:**

| Parameter | Type | Description |
|---|---|---|
| `name` | string | Case-insensitive partial match on `full_name` or `preferred_name` |
| `gender` | `male` \| `female` | Exact match |
| `date_of_birth` | date | Exact match |
| `date_of_birth_from` | date | Range lower bound inclusive |
| `date_of_birth_to` | date | Range upper bound inclusive |
| `household_id` | UUID | Only persons who are members of this household |
| `limit` | int | Default `50` |
| `offset` | int | Default `0` |

**Response `200`:**
```json
{
  "items": [ /* person[] */ ],
  "total": 12,
  "limit": 50,
  "offset": 0
}
```

---

### `PATCH /api/v1/persons/{person_id}`

**MCP tool:** `update_person`

Update mutable fields. Only supplied fields are changed (PATCH semantics).

**Path parameter:** `person_id` — UUID

**Request body:** Any subset of:
```json
{
  "full_name": "Ravi K. Aggarwal",
  "preferred_name": "Ravi",
  "date_of_birth": "1990-03-15",
  "gender": "male",
  "user_identifier": "raaggarw"
}
```

**Response `200`:** Updated full person row.

**Errors:** `400`, `404`, `409` (duplicate `user_identifier`)

---

### `DELETE /api/v1/persons/{person_id}`

**MCP tool:** `delete_person`

Delete a person. Returns a structured blocking error if references exist.

**Path parameter:** `person_id` — UUID

**Response `204`:** Empty body on success.

**Response `422` (referential integrity):**
```json
{
  "error": "referenced",
  "message": "Cannot delete person with existing references",
  "details": {
    "documents": 4,
    "facts": 12,
    "relationships": 2
  }
}
```

---

## Group 1b — Household

### `POST /api/v1/households`

**MCP tool:** `create_household`

Create a new household.

**Request body:**
```json
{ "name": "Aggarwal Family" }
```

| Field | Type | Required |
|---|---|---|
| `name` | string | yes |

**Response `201`:**
```json
{
  "id": "uuid",
  "name": "Aggarwal Family",
  "created_at": "2026-04-25T10:00:00Z",
  "updated_at": "2026-04-25T10:00:00Z"
}
```

---

### `GET /api/v1/households/{household_id}`

**MCP tool:** `get_household`

Fetch a single household by UUID.

**Path parameter:** `household_id` — UUID

**Response `200`:** Full household row.

**Errors:** `404`

---

### `GET /api/v1/households`

**MCP tool:** `search_households`

Find households by name (case-insensitive partial match).

**Query parameters:**

| Parameter | Type | Required |
|---|---|---|
| `name` | string | yes |

**Response `200`:**
```json
{
  "items": [ /* household[] */ ],
  "total": 2
}
```

---

### `PATCH /api/v1/households/{household_id}`

**MCP tool:** `update_household`

Rename a household.

**Path parameter:** `household_id` — UUID

**Request body:**
```json
{ "name": "Aggarwal-London Household" }
```

| Field | Type | Required |
|---|---|---|
| `name` | string | yes |

**Response `200`:** Updated full household row.

**Errors:** `404`

---

### `DELETE /api/v1/households/{household_id}`

**MCP tool:** `delete_household`

Delete a household. Returns structured blocking error if references exist.

**Path parameter:** `household_id` — UUID

**Response `204`:** Empty body on success.

**Response `422`:**
```json
{
  "error": "referenced",
  "message": "Cannot delete household with existing references",
  "details": {
    "documents": 2,
    "facts": 6,
    "members": 3
  }
}
```

---

## Group 1c — Person-Household

### `PUT /api/v1/households/{household_id}/members/{person_id}`

**MCP tool:** `add_person_to_household`

Link an existing person to an existing household. Idempotent.

**Path parameters:** `household_id`, `person_id` — both UUID

**Request body:** Empty (`{}`)

**Response `204`:** Empty body.

**Errors:** `404` (person or household not found)

---

### `DELETE /api/v1/households/{household_id}/members/{person_id}`

**MCP tool:** `remove_person_from_household`

Remove a person's membership from a household. Does not delete the person or household.

**Path parameters:** `household_id`, `person_id` — both UUID

**Response `204`:** Empty body.

**Errors:** `404`

---

### `GET /api/v1/households/{household_id}/members`

**MCP tool:** `list_household_members`

Return all person IDs who are members of a household.

**Path parameter:** `household_id` — UUID

**Response `200`:**
```json
{
  "household_id": "uuid",
  "member_ids": ["uuid1", "uuid2"]
}
```

**Errors:** `404`

---

### `GET /api/v1/persons/{person_id}/households`

**MCP tool:** `list_person_households`

Return all household IDs that a person belongs to.

**Path parameter:** `person_id` — UUID

**Response `200`:**
```json
{
  "person_id": "uuid",
  "household_ids": ["uuid1"]
}
```

**Errors:** `404`

---

## Group 1d — Relationship

### `POST /api/v1/relationships`

**MCP tool:** `create_relationship`

Record a directed relationship between two persons. Both persons must exist.

**Request body:**
```json
{
  "from_person_id": "uuid",
  "to_person_id": "uuid",
  "relation_type": "father"
}
```

| Field | Type | Required |
|---|---|---|
| `from_person_id` | UUID | yes |
| `to_person_id` | UUID | yes |
| `relation_type` | enum | yes |

Valid `relation_type` values: `father`, `mother`, `son`, `daughter`, `brother`, `sister`, `husband`, `wife`.

**Response `201`:**
```json
{
  "from_person_id": "uuid",
  "to_person_id": "uuid",
  "relation_type": "father",
  "created_at": "2026-04-25T10:00:00Z",
  "updated_at": "2026-04-25T10:00:00Z"
}
```

**Errors:** `400` (invalid relation_type), `404` (person not found), `409` (relationship already exists)

---

### `GET /api/v1/relationships/{from_person_id}/{to_person_id}`

**MCP tool:** `get_relationship`

Fetch a single directed relationship between two persons.

**Path parameters:** `from_person_id`, `to_person_id` — both UUID

**Response `200`:** Full relationship row.

**Errors:** `404`

---

### `GET /api/v1/relationships`

**MCP tool:** `list_relationships`

Return all relationships where the given person appears as either subject or object.

**Query parameters:**

| Parameter | Type | Required |
|---|---|---|
| `person_id` | UUID | yes |

**Response `200`:**
```json
{
  "person_id": "uuid",
  "items": [
    { "from_person_id": "uuid", "to_person_id": "uuid", "relation_type": "father", "created_at": "...", "updated_at": "..." }
  ]
}
```

---

### `PATCH /api/v1/relationships/{from_person_id}/{to_person_id}`

**MCP tool:** `update_relationship`

Change the `relation_type` on an existing directed relationship.

**Path parameters:** `from_person_id`, `to_person_id` — both UUID

**Request body:**
```json
{ "relation_type": "brother" }
```

| Field | Type | Required |
|---|---|---|
| `relation_type` | enum | yes |

**Response `200`:** Updated relationship row.

**Errors:** `400`, `404`

---

### `DELETE /api/v1/relationships/{from_person_id}/{to_person_id}`

**MCP tool:** `delete_relationship`

Remove a directed relationship between two persons.

**Path parameters:** `from_person_id`, `to_person_id` — both UUID

**Response `204`:** Empty body.

**Errors:** `404`

---

### `GET /api/v1/relationships/{from_person_id}/{to_person_id}/kinship`

**MCP tool:** `resolve_kinship`

Derive the cultural name for the relationship between two persons via BFS graph traversal. Server-side traversal avoids N round trips by the caller.

**Path parameters:** `from_person_id`, `to_person_id` — both UUID

**Response `200`:**
```json
{
  "from_person_id": "uuid",
  "to_person_id": "uuid",
  "chain": ["father", "sister"],
  "alias": "bua",
  "language": "hindi"
}
```

**Response `404`:** Returned when no path exists between the two persons, or no alias is registered for the derived chain.

---

## Group 2a — Document

### `POST /api/v1/documents`

**MCP tool:** `create_document`

Persist a new immutable document. At least one of `person_id` or `household_id` must be provided. The embedding is generated externally by the caller.

**Request body:**
```json
{
  "content_text": "Received salary slip for March 2026...",
  "source_type_id": "uuid",
  "embedding": [0.123, -0.456, 0.789],
  "person_id": "uuid",
  "household_id": null,
  "supersedes_ids": [],
  "files": [
    { "file_path": "/data/files/slip-march-2026.pdf", "filename": "slip-march-2026.pdf", "mime_type": "application/pdf" }
  ]
}
```

| Field | Type | Required |
|---|---|---|
| `content_text` | string | yes |
| `source_type_id` | UUID | yes |
| `embedding` | float[] | yes |
| `person_id` | UUID | no (one of person/household required) |
| `household_id` | UUID | no (one of person/household required) |
| `supersedes_ids` | UUID[] | no |
| `files` | object[] | no |

Each `files` element: `{ file_path: string, filename: string, mime_type?: string }`.

**Response `201`:**
```json
{
  "id": "uuid",
  "content_text": "...",
  "source_type_id": "uuid",
  "person_id": "uuid",
  "household_id": null,
  "supersedes_ids": [],
  "files": [],
  "created_at": "2026-04-25T10:00:00Z"
}
```

Note: Embedding is not returned in responses to keep payloads small.

**Errors:** `400` (neither person_id nor household_id provided), `404` (person, household, or superseded document not found)

---

### `GET /api/v1/documents/{document_id}`

**MCP tool:** `get_document`

Fetch a single document by UUID (provenance lookup).

**Path parameter:** `document_id` — UUID

**Response `200`:** Full document row (without embedding).

**Errors:** `404`

---

### `GET /api/v1/documents`

**MCP tool:** `list_documents`

Filter-based document listing.

**Query parameters:**

| Parameter | Type | Description |
|---|---|---|
| `person_id` | UUID | |
| `household_id` | UUID | |
| `source_type_id` | UUID | |
| `created_after` | RFC 3339 timestamp | |
| `created_before` | RFC 3339 timestamp | |
| `limit` | int | Default `50` |
| `offset` | int | Default `0` |

**Response `200`:**
```json
{
  "items": [ /* document[] without embeddings */ ],
  "total": 42,
  "limit": 50,
  "offset": 0
}
```

---

### `POST /api/v1/documents/search`

**MCP tool:** `search_documents`

Vector similarity search over documents.

**Request body:**
```json
{
  "embedding": [0.123, -0.456, 0.789],
  "person_id": "uuid",
  "household_id": null,
  "source_type_id": null,
  "limit": 10,
  "similarity_threshold": 0.7
}
```

| Field | Type | Required |
|---|---|---|
| `embedding` | float[] | yes |
| `person_id` | UUID | no |
| `household_id` | UUID | no |
| `source_type_id` | UUID | no |
| `limit` | int | no — default `10` |
| `similarity_threshold` | float [0–1] | no — default `0.7` |

**Response `200`:**
```json
{
  "items": [
    { /* document fields */, "similarity_score": 0.93 }
  ]
}
```

---

## Group 2b — Fact

### `POST /api/v1/facts`

**MCP tool:** `create_fact`

Persist a single fact operation. For a new entity, the caller generates a fresh UUID for `entity_instance_id`. For updates/deletes, the caller first resolves the UUID via `POST /api/v1/facts/search`.

**Request body:**
```json
{
  "document_id": "uuid",
  "schema_id": "uuid",
  "entity_instance_id": "uuid",
  "operation_type": "create",
  "fields": {
    "title": "Passport renewal",
    "status": "pending",
    "due_date": "2026-05-01"
  },
  "embedding": [0.123, -0.456, 0.789]
}
```

| Field | Type | Required |
|---|---|---|
| `document_id` | UUID | yes |
| `schema_id` | UUID | yes |
| `entity_instance_id` | UUID | yes |
| `operation_type` | `"create"` \| `"update"` \| `"delete"` | yes |
| `fields` | object (JSONB) | yes |
| `embedding` | float[] | yes |

**Response `201`:**
```json
{
  "id": "uuid",
  "document_id": "uuid",
  "schema_id": "uuid",
  "entity_instance_id": "uuid",
  "operation_type": "create",
  "fields": { "title": "Passport renewal", "status": "pending", "due_date": "2026-05-01" },
  "created_at": "2026-04-25T10:00:00Z"
}
```

**Errors:** `404` (document or schema not found)

---

### `GET /api/v1/facts/{entity_instance_id}/history`

**MCP tool:** `get_fact_history`

Retrieve the full operation history for a single entity instance in chronological order.

**Path parameter:** `entity_instance_id` — UUID

**Response `200`:**
```json
{
  "entity_instance_id": "uuid",
  "items": [
    {
      "id": "uuid",
      "document_id": "uuid",
      "schema_id": "uuid",
      "operation_type": "create",
      "fields": { "title": "Passport renewal", "status": "pending" },
      "created_at": "2026-04-10T09:00:00Z"
    },
    {
      "id": "uuid",
      "document_id": "uuid",
      "schema_id": "uuid",
      "operation_type": "update",
      "fields": { "status": "done" },
      "created_at": "2026-04-25T10:00:00Z"
    }
  ]
}
```

---

### `GET /api/v1/facts/{entity_instance_id}/current`

**MCP tool:** `get_current_fact`

Retrieve the merged current state for a single entity instance. Returns `404` if the entity has been logically deleted.

**Path parameter:** `entity_instance_id` — UUID

**Response `200`:**
```json
{
  "entity_instance_id": "uuid",
  "schema_id": "uuid",
  "person_id": "uuid",
  "household_id": null,
  "fields": {
    "title": "Passport renewal",
    "status": "done",
    "due_date": "2026-05-01"
  },
  "last_updated_at": "2026-04-25T10:00:00Z"
}
```

**Errors:** `404` (not found or logically deleted)

---

### `GET /api/v1/facts/current`

**MCP tool:** `list_current_facts`

Filter-based listing of current entity states. Only returns active (non-deleted) entities.

**Query parameters:**

| Parameter | Type | Description |
|---|---|---|
| `person_id` | UUID | |
| `household_id` | UUID | |
| `domain_id` | UUID | |
| `entity_type` | string | e.g. `todo_item`, `insurance_card` |
| `limit` | int | Default `50` |
| `offset` | int | Default `0` |

**Response `200`:**
```json
{
  "items": [
    {
      "entity_instance_id": "uuid",
      "schema_id": "uuid",
      "person_id": "uuid",
      "household_id": null,
      "fields": { "title": "Passport renewal", "status": "done" },
      "last_updated_at": "2026-04-25T10:00:00Z"
    }
  ],
  "total": 7,
  "limit": 50,
  "offset": 0
}
```

---

### `POST /api/v1/facts/search`

**MCP tool:** `search_current_facts`

Vector similarity search over current entity states. Primary mechanism for resolving `entity_instance_id` from a natural language description.

**Request body:**
```json
{
  "embedding": [0.123, -0.456, 0.789],
  "person_id": "uuid",
  "household_id": null,
  "domain_id": null,
  "entity_type": "todo_item",
  "limit": 10,
  "similarity_threshold": 0.7
}
```

| Field | Type | Required |
|---|---|---|
| `embedding` | float[] | yes |
| `person_id` | UUID | no |
| `household_id` | UUID | no |
| `domain_id` | UUID | no |
| `entity_type` | string | no |
| `limit` | int | no — default `10` |
| `similarity_threshold` | float [0–1] | no — default `0.7` |

**Response `200`:**
```json
{
  "items": [
    {
      "entity_instance_id": "uuid",
      "schema_id": "uuid",
      "person_id": "uuid",
      "household_id": null,
      "fields": { "title": "Passport renewal", "status": "done" },
      "last_updated_at": "2026-04-25T10:00:00Z",
      "similarity_score": 0.91
    }
  ]
}
```

---

## Group 3 — Schema Governance

### `GET /api/v1/schemas`

**MCP tool:** `list_entity_type_schemas`

List schema definitions. Default returns only active schemas.

**Query parameters:**

| Parameter | Type | Description |
|---|---|---|
| `domain_id` | UUID | |
| `entity_type` | string | Exact match |
| `active_only` | boolean | Default `true` |

**Response `200`:**
```json
{
  "items": [
    {
      "id": "uuid",
      "domain_id": "uuid",
      "entity_type": "todo_item",
      "schema_version": 1,
      "is_active": true,
      "description": "A personal task or reminder",
      "field_definitions": [
        { "name": "title", "type": "text", "required": true, "description": "Task title" },
        { "name": "status", "type": "text", "required": true, "description": "pending | done | cancelled" },
        { "name": "due_date", "type": "date", "required": false, "description": "ISO 8601 date" },
        { "name": "priority", "type": "text", "required": false, "description": "low | medium | high" }
      ],
      "mandatory_fields": ["title", "status"],
      "created_at": "2026-04-25T10:00:00Z",
      "updated_at": "2026-04-25T10:00:00Z"
    }
  ]
}
```

Note: `mandatory_fields` is a generated column — the server derives it from `field_definitions` where `required: true`.

---

### `GET /api/v1/schemas/{schema_id}`

**MCP tool:** `get_entity_type_schema`

Fetch a specific schema version by UUID (provenance lookup).

**Path parameter:** `schema_id` — UUID

**Response `200`:** Full schema row (same shape as list items above).

**Errors:** `404`

---

### `GET /api/v1/schemas/current`

**MCP tool:** `get_current_entity_type_schema`

Fetch the latest active schema for a (domain, entity_type) pair. Uses the `current_entity_type_schema` view.

**Query parameters:**

| Parameter | Type | Required |
|---|---|---|
| `domain_id` | UUID | yes |
| `entity_type` | string | yes |

**Response `200`:** Full schema row.

**Response `404`:** No active schema found for this (domain, entity_type) pair.

---

### `POST /api/v1/schemas`

**MCP tool:** `create_entity_type_schema`

Create a new schema for a (domain, entity_type) pair that does not yet exist. The schema is immediately active (`is_active = true`, `schema_version = 1`). Called in the write turn after user approval.

**Request body:**
```json
{
  "domain_id": "uuid",
  "entity_type": "blood_pressure_reading",
  "field_definitions": [
    { "name": "systolic", "type": "number", "required": true, "description": "Systolic pressure mmHg" },
    { "name": "diastolic", "type": "number", "required": true, "description": "Diastolic pressure mmHg" },
    { "name": "recorded_at", "type": "date", "required": false, "description": "Date of measurement" }
  ],
  "description": "A blood pressure measurement"
}
```

| Field | Type | Required |
|---|---|---|
| `domain_id` | UUID | yes |
| `entity_type` | string | yes |
| `field_definitions` | object[] | yes |
| `description` | string | no |

**Response `201`:** Full schema row with `is_active = true` and `schema_version = 1`.

**Errors:** `400`, `404` (domain not found), `409` (schema already exists for this domain/entity_type pair)

---

### `POST /api/v1/schemas/{domain_id}/{entity_type}/versions`

**MCP tool:** `update_entity_type_schema`

Create a new version of an existing active schema. Increments `schema_version`, activates the new row, and deactivates the previous version. The caller must provide the **full field list** for the new version. Called in the write turn after user approval.

**Path parameters:** `domain_id` (UUID), `entity_type` (string)

**Request body:**
```json
{
  "field_definitions": [
    { "name": "systolic", "type": "number", "required": true, "description": "Systolic pressure mmHg" },
    { "name": "diastolic", "type": "number", "required": true, "description": "Diastolic pressure mmHg" },
    { "name": "recorded_at", "type": "date", "required": false, "description": "Date of measurement" },
    { "name": "notes", "type": "text", "required": false, "description": "Free-text notes" }
  ],
  "description": "A blood pressure measurement with optional notes"
}
```

| Field | Type | Required |
|---|---|---|
| `field_definitions` | object[] | yes — full list, not a diff |
| `description` | string | no |

**Response `201`:** New schema row with incremented `schema_version` and `is_active = true`.

**Errors:** `404` (no active schema found for this domain/entity_type pair)

---

### `DELETE /api/v1/schemas/{domain_id}/{entity_type}/active`

**MCP tool:** `deactivate_entity_type_schema`

Mark the current active schema for a (domain, entity_type) pair as inactive. All historical schema versions and extracted facts are retained.

**Path parameters:** `domain_id` (UUID), `entity_type` (string)

**Response `204`:** Empty body on success.

**Errors:** `404` (no active schema found)

---

## Group 4 — Reference

### `GET /api/v1/reference/domains`

**MCP tool:** `list_domains`

Return all life domains.

**Parameters:** none

**Response `200`:**
```json
{
  "items": [
    { "id": "uuid", "name": "health" },
    { "id": "uuid", "name": "finance" },
    { "id": "uuid", "name": "employment" },
    { "id": "uuid", "name": "personal_details" },
    { "id": "uuid", "name": "todo" },
    { "id": "uuid", "name": "household" },
    { "id": "uuid", "name": "news_preferences" }
  ]
}
```

---

### `GET /api/v1/reference/source-types`

**MCP tool:** `list_source_types`

Return all registered source types.

**Parameters:** none

**Response `200`:**
```json
{
  "items": [
    { "id": "uuid", "name": "user_input" },
    { "id": "uuid", "name": "file_upload" },
    { "id": "uuid", "name": "ai_extracted" },
    { "id": "uuid", "name": "plaid_poll" },
    { "id": "uuid", "name": "gmail_poll" }
  ]
}
```

---

### `GET /api/v1/reference/kinship-aliases`

**MCP tool:** `list_kinship_aliases`

Return cultural kinship name mappings.

**Query parameters:**

| Parameter | Type | Description |
|---|---|---|
| `language` | string | Optional filter e.g. `hindi`, `english` |

**Response `200`:**
```json
{
  "items": [
    { "id": "uuid", "chain": ["father", "sister"], "alias": "bua", "language": "hindi" },
    { "id": "uuid", "chain": ["mother", "brother"], "alias": "mama", "language": "hindi" }
  ]
}
```

---

## Group 5 — Audit

### `POST /api/v1/audit/interactions`

**MCP tool:** `log_interaction`

Persist one interaction turn to the audit log. Exactly one of `person_id` or `job_type` must be set.

**Request body:**
```json
{
  "message_text": "Mark my passport renewal as done",
  "response_text": "Done — I've marked your passport renewal task as completed.",
  "status": "success",
  "person_id": "uuid",
  "job_type": null,
  "tool_calls_json": [
    { "tool": "search_current_facts", "params": { "embedding": [0.1, 0.2], "entity_type": "todo_item" } },
    { "tool": "create_fact", "params": { "entity_instance_id": "uuid", "operation_type": "update", "fields": { "status": "done" } } }
  ],
  "error_message": null
}
```

| Field | Type | Required |
|---|---|---|
| `message_text` | string | yes |
| `response_text` | string | yes |
| `status` | `"success"` \| `"error"` \| `"partial"` | yes |
| `person_id` | UUID | no (one of person_id/job_type required) |
| `job_type` | string | no (one of person_id/job_type required) |
| `tool_calls_json` | object[] | no |
| `error_message` | string | no |

**Response `201`:**
```json
{
  "id": "uuid",
  "message_text": "...",
  "response_text": "...",
  "status": "success",
  "person_id": "uuid",
  "job_type": null,
  "tool_calls_json": [],
  "error_message": null,
  "created_at": "2026-04-25T10:00:00Z"
}
```

**Errors:** `400` (both person_id and job_type present, or neither present; status is error but error_message is null)

---

## Group 6 — File Handling

### `POST /api/v1/files`

**MCP tool:** `save_file`

Persist a file to local disk and return its path. The caller submits base64-encoded content. The returned `file_path` is opaque — pass it to `create_document` and/or `POST /api/v1/files/extract-text`.

**Request body:**
```json
{
  "content_base64": "JVBERi0xLjQK...",
  "filename": "slip-march-2026.pdf",
  "mime_type": "application/pdf"
}
```

| Field | Type | Required |
|---|---|---|
| `content_base64` | string | yes |
| `filename` | string | yes |
| `mime_type` | string | no |

**Response `201`:**
```json
{
  "file_path": "/data/files/2026/04/25/slip-march-2026-abc123.pdf",
  "filename": "slip-march-2026.pdf",
  "mime_type": "application/pdf",
  "size_bytes": 48210
}
```

**Errors:** `400` (invalid base64), `500` (disk write failure)

---

### `POST /api/v1/files/extract-text`

**MCP tool:** `extract_text_from_file`

Extract plain text from a previously saved file. Dispatches to PDF parser, OCR, or plain-text reader based on file type. The extracted text is what gets stored in `document.content_text`.

**Request body:**
```json
{ "file_path": "/data/files/2026/04/25/slip-march-2026-abc123.pdf" }
```

| Field | Type | Required |
|---|---|---|
| `file_path` | string | yes |

**Response `200`:**
```json
{
  "file_path": "/data/files/2026/04/25/slip-march-2026-abc123.pdf",
  "text": "March 2026 Salary Slip\nEmployer: Acme Corp\nGross: 5000.00\n...",
  "extraction_method": "pdf_parser"
}
```

`extraction_method` is one of: `pdf_parser`, `ocr`, `plain_text`.

**Errors:** `404` (file not found), `422` (unsupported file type for extraction)

---

### `GET /api/v1/files`

**MCP tool:** `get_file`

Retrieve a previously saved file by its path. Returns base64-encoded content.

**Query parameters:**

| Parameter | Type | Required |
|---|---|---|
| `path` | string | yes |

**Response `200`:**
```json
{
  "file_path": "/data/files/2026/04/25/slip-march-2026-abc123.pdf",
  "content_base64": "JVBERi0xLjQK...",
  "mime_type": "application/pdf",
  "filename": "slip-march-2026.pdf"
}
```

**Errors:** `404`

---

### `DELETE /api/v1/files`

**MCP tool:** `delete_file`

Delete a file from the filesystem. Rejected if the path is still referenced in any document's `files` array.

**Query parameters:**

| Parameter | Type | Required |
|---|---|---|
| `path` | string | yes |

**Response `204`:** Empty body on success.

**Response `422`:**
```json
{
  "error": "referenced",
  "message": "File is still referenced by existing documents",
  "details": {
    "documents": ["uuid1", "uuid2"]
  }
}
```

**Errors:** `404`

---

## Health Check

### `GET /health`

Not under `/api/v1` — no auth required. Returns service liveness and database connectivity.

**Response `200`:**
```json
{
  "status": "ok",
  "db": "ok",
  "version": "1.0.0"
}
```

**Response `503`:**
```json
{
  "status": "degraded",
  "db": "unreachable",
  "version": "1.0.0"
}
```

---

## Endpoint Summary

| # | Method | Path | MCP tool |
|---|---|---|---|
| 1 | POST | `/api/v1/persons` | `create_person` |
| 2 | GET | `/api/v1/persons/{person_id}` | `get_person` |
| 3 | GET | `/api/v1/persons` | `search_persons` |
| 4 | PATCH | `/api/v1/persons/{person_id}` | `update_person` |
| 5 | DELETE | `/api/v1/persons/{person_id}` | `delete_person` |
| 6 | POST | `/api/v1/households` | `create_household` |
| 7 | GET | `/api/v1/households/{household_id}` | `get_household` |
| 8 | GET | `/api/v1/households` | `search_households` |
| 9 | PATCH | `/api/v1/households/{household_id}` | `update_household` |
| 10 | DELETE | `/api/v1/households/{household_id}` | `delete_household` |
| 11 | PUT | `/api/v1/households/{household_id}/members/{person_id}` | `add_person_to_household` |
| 12 | DELETE | `/api/v1/households/{household_id}/members/{person_id}` | `remove_person_from_household` |
| 13 | GET | `/api/v1/households/{household_id}/members` | `list_household_members` |
| 14 | GET | `/api/v1/persons/{person_id}/households` | `list_person_households` |
| 15 | POST | `/api/v1/relationships` | `create_relationship` |
| 16 | GET | `/api/v1/relationships/{from_person_id}/{to_person_id}` | `get_relationship` |
| 17 | GET | `/api/v1/relationships` | `list_relationships` |
| 18 | PATCH | `/api/v1/relationships/{from_person_id}/{to_person_id}` | `update_relationship` |
| 19 | DELETE | `/api/v1/relationships/{from_person_id}/{to_person_id}` | `delete_relationship` |
| 20 | GET | `/api/v1/relationships/{from_person_id}/{to_person_id}/kinship` | `resolve_kinship` |
| 21 | POST | `/api/v1/documents` | `create_document` |
| 22 | GET | `/api/v1/documents/{document_id}` | `get_document` |
| 23 | GET | `/api/v1/documents` | `list_documents` |
| 24 | POST | `/api/v1/documents/search` | `search_documents` |
| 25 | POST | `/api/v1/facts` | `create_fact` |
| 26 | GET | `/api/v1/facts/{entity_instance_id}/history` | `get_fact_history` |
| 27 | GET | `/api/v1/facts/{entity_instance_id}/current` | `get_current_fact` |
| 28 | GET | `/api/v1/facts/current` | `list_current_facts` |
| 29 | POST | `/api/v1/facts/search` | `search_current_facts` |
| 30 | GET | `/api/v1/schemas` | `list_entity_type_schemas` |
| 31 | GET | `/api/v1/schemas/{schema_id}` | `get_entity_type_schema` |
| 32 | GET | `/api/v1/schemas/current` | `get_current_entity_type_schema` |
| 33 | POST | `/api/v1/schemas` | `create_entity_type_schema` |
| 34 | POST | `/api/v1/schemas/{domain_id}/{entity_type}/versions` | `update_entity_type_schema` |
| 35 | DELETE | `/api/v1/schemas/{domain_id}/{entity_type}/active` | `deactivate_entity_type_schema` |
| 36 | GET | `/api/v1/reference/domains` | `list_domains` |
| 37 | GET | `/api/v1/reference/source-types` | `list_source_types` |
| 38 | GET | `/api/v1/reference/kinship-aliases` | `list_kinship_aliases` |
| 39 | POST | `/api/v1/audit/interactions` | `log_interaction` |
| 40 | POST | `/api/v1/files` | `save_file` |
| 41 | POST | `/api/v1/files/extract-text` | `extract_text_from_file` |
| 42 | GET | `/api/v1/files` | `get_file` |
| 43 | DELETE | `/api/v1/files` | `delete_file` |
| — | GET | `/health` | (health check, no auth) |
