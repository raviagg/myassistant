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
| [7 — Scheduled Jobs](#group-7--scheduled-jobs) | 8 |
| [8 — Source Connections](#group-8--source-connections) | 15 |
| [9 — Plaid Native Tables](#group-9--plaid-native-tables) | 3 |
| **Total** | **69** |

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

Each element of `fieldDefinitions` has this shape:

```json
{
  "name": "premium",
  "type": "number",
  "mandatory": false,
  "description": "Monthly premium amount"
}
```

Valid `type` values: `text`, `number`, `date`, `boolean`, `file`, `entity_ref`.

For `entity_ref` fields: the value stored in a fact is a UUID referencing another entity's `entity_instance_id`. The MCP server validates that the referenced entity exists in `current_facts` before the write succeeds — writes with dangling references are rejected with an error.

---

## Group 1a — Person

### `POST /api/v1/persons`

**MCP tool:** `create_person`

Register a new person.

**Request body:**
```json
{
  "fullName": "Ravi Aggarwal",
  "gender": "male",
  "dateOfBirth": "1990-03-15",
  "preferredName": "Ravi",
  "userIdentifier": "raaggarw"
}
```

| Field | Type | Required |
|---|---|---|
| `fullName` | string | yes |
| `gender` | `"male"` \| `"female"` | yes |
| `dateOfBirth` | date (ISO 8601 `YYYY-MM-DD`) | no |
| `preferredName` | string | no |
| `userIdentifier` | string | no |

**Response `201`:**
```json
{
  "id": "uuid",
  "fullName": "Ravi Aggarwal",
  "gender": "male",
  "dateOfBirth": "1990-03-15",
  "preferredName": "Ravi",
  "userIdentifier": "raaggarw",
  "createdAt": "2026-04-25T10:00:00Z",
  "updatedAt": "2026-04-25T10:00:00Z"
}
```

**Errors:** `400`, `409` (duplicate `userIdentifier`)

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
| `name` | string | Case-insensitive partial match on `fullName` or `preferredName` |
| `gender` | `male` \| `female` | Exact match |
| `dateOfBirth` | date | Exact match |
| `dateOfBirthFrom` | date | Range lower bound inclusive |
| `dateOfBirthTo` | date | Range upper bound inclusive |
| `householdId` | UUID | Only persons who are members of this household |
| `userIdentifier` | string | Exact match on `user_identifier` column |
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
  "fullName": "Ravi K. Aggarwal",
  "preferredName": "Ravi",
  "dateOfBirth": "1990-03-15",
  "gender": "male",
  "userIdentifier": "raaggarw"
}
```

**Response `200`:** Updated full person row.

**Errors:** `400`, `404`, `409` (duplicate `userIdentifier`)

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
  "createdAt": "2026-04-25T10:00:00Z",
  "updatedAt": "2026-04-25T10:00:00Z"
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
  "total": 2,
  "limit": 2,
  "offset": 0
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
  "householdId": "uuid",
  "memberIds": ["uuid1", "uuid2"]
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
  "personId": "uuid",
  "householdIds": ["uuid1"]
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
  "fromPersonId": "uuid",
  "toPersonId": "uuid",
  "relationType": "father"
}
```

| Field | Type | Required |
|---|---|---|
| `fromPersonId` | UUID | yes |
| `toPersonId` | UUID | yes |
| `relationType` | enum | yes |

Valid `relationType` values: `father`, `mother`, `son`, `daughter`, `brother`, `sister`, `husband`, `wife`.

**Response `201`:**
```json
{
  "id": "uuid",
  "fromPersonId": "uuid",
  "toPersonId": "uuid",
  "relationType": "father",
  "createdAt": "2026-04-25T10:00:00Z",
  "updatedAt": "2026-04-25T10:00:00Z"
}
```

**Errors:** `400` (invalid relationType), `404` (person not found), `409` (relationship already exists)

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
| `personId` | UUID | yes |

**Response `200`:**
```json
{
  "items": [
    { "id": "uuid", "fromPersonId": "uuid", "toPersonId": "uuid", "relationType": "father", "createdAt": "...", "updatedAt": "..." }
  ],
  "total": 1,
  "limit": 1,
  "offset": 0
}
```

---

### `PATCH /api/v1/relationships/{from_person_id}/{to_person_id}`

**MCP tool:** `update_relationship`

Change the `relationType` on an existing directed relationship.

**Path parameters:** `from_person_id`, `to_person_id` — both UUID

**Request body:**
```json
{ "relationType": "brother" }
```

| Field | Type | Required |
|---|---|---|
| `relationType` | enum | yes |

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
  "fromPersonId": "uuid",
  "toPersonId": "uuid",
  "chain": ["father", "sister"],
  "alias": "bua",
  "description": "Father's sister"
}
```

**Response `404`:** Returned when no path exists between the two persons, or no alias is registered for the derived chain.

---

## Group 2a — Document

### `POST /api/v1/documents`

**MCP tool:** `create_document`

Persist a new immutable document. At least one of `personId` or `householdId` must be provided. The embedding is generated externally by the caller.

**Request body:**
```json
{
  "contentText": "Received salary slip for March 2026...",
  "sourceTypeId": "uuid",
  "embedding": [0.123, -0.456, 0.789],
  "personId": "uuid",
  "householdId": null,
  "sourceConnectionId": null,
  "supersedesIds": [],
  "files": [
    { "filePath": "/data/files/slip-march-2026.pdf", "filename": "slip-march-2026.pdf", "mimeType": "application/pdf" }
  ]
}
```

| Field | Type | Required |
|---|---|---|
| `contentText` | string | yes |
| `sourceTypeId` | UUID | yes |
| `embedding` | float[] | yes |
| `personId` | UUID | no (one of personId/householdId required) |
| `householdId` | UUID | no (one of personId/householdId required) |
| `sourceConnectionId` | UUID | no | FK to `source_connections` |
| `supersedesIds` | UUID[] | no |
| `files` | object[] | no |

Each `files` element: `{ filePath: string, filename: string, mimeType?: string }`.

**Response `201`:**
```json
{
  "id": "uuid",
  "contentText": "...",
  "sourceTypeId": "uuid",
  "personId": "uuid",
  "householdId": null,
  "sourceConnectionId": null,
  "supersedesIds": [],
  "files": [],
  "createdAt": "2026-04-25T10:00:00Z"
}
```

Note: Embedding is not returned in responses to keep payloads small.

**Errors:** `400` (neither personId nor householdId provided), `404` (person, household, or superseded document not found)

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
| `personId` | UUID | |
| `householdId` | UUID | |
| `sourceTypeId` | UUID | |
| `createdAfter` | RFC 3339 timestamp | |
| `createdBefore` | RFC 3339 timestamp | |
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
  "personId": "uuid",
  "householdId": null,
  "sourceTypeId": null,
  "limit": 10,
  "similarityThreshold": 0.7
}
```

| Field | Type | Required |
|---|---|---|
| `embedding` | float[] | yes |
| `personId` | UUID | no |
| `householdId` | UUID | no |
| `sourceTypeId` | UUID | no |
| `limit` | int | no — default `10` |
| `similarityThreshold` | float [0–1] | no — default `0.7` |

**Response `200`:**
```json
{
  "items": [
    { /* document fields */, "similarityScore": 0.93 }
  ]
}
```

---

## Group 2b — Fact

### `POST /api/v1/facts`

**MCP tool:** `create_fact`

Persist a single fact operation. For a new entity, the caller generates a fresh UUID for `entityInstanceId`. For updates/deletes, the caller first resolves the UUID via `POST /api/v1/facts/search`.

**Request body:**
```json
{
  "documentId": "uuid",
  "schemaId": "uuid",
  "entityInstanceId": "uuid",
  "operationType": "create",
  "fields": {
    "title": "Passport renewal",
    "status": "pending",
    "dueDate": "2026-05-01"
  },
  "embedding": [0.123, -0.456, 0.789],
  "sourceConnectionId": null
}
```

| Field | Type | Required |
|---|---|---|
| `documentId` | UUID | yes |
| `schemaId` | UUID | yes |
| `entityInstanceId` | UUID | yes |
| `operationType` | `"create"` \| `"update"` \| `"delete"` | yes |
| `fields` | object (JSONB) | yes |
| `embedding` | float[] | yes |
| `sourceConnectionId` | UUID | no | FK to `source_connections` |

**Response `201`:**
```json
{
  "id": "uuid",
  "documentId": "uuid",
  "schemaId": "uuid",
  "sourceConnectionId": null,
  "entityInstanceId": "uuid",
  "operationType": "create",
  "fields": { "title": "Passport renewal", "status": "pending", "dueDate": "2026-05-01" },
  "createdAt": "2026-04-25T10:00:00Z"
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
  "entityInstanceId": "uuid",
  "items": [
    {
      "id": "uuid",
      "documentId": "uuid",
      "schemaId": "uuid",
      "operationType": "create",
      "fields": { "title": "Passport renewal", "status": "pending" },
      "createdAt": "2026-04-10T09:00:00Z"
    },
    {
      "id": "uuid",
      "documentId": "uuid",
      "schemaId": "uuid",
      "operationType": "update",
      "fields": { "status": "done" },
      "createdAt": "2026-04-25T10:00:00Z"
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
  "entityInstanceId": "uuid",
  "schemaId": "uuid",
  "personId": "uuid",
  "householdId": null,
  "fields": {
    "title": "Passport renewal",
    "status": "done",
    "dueDate": "2026-05-01"
  },
  "lastUpdatedAt": "2026-04-25T10:00:00Z"
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
| `personId` | UUID | |
| `householdId` | UUID | |
| `domainId` | UUID | |
| `entityType` | string | e.g. `todo_item`, `insurance_card` |
| `limit` | int | Default `50` |
| `offset` | int | Default `0` |

**Response `200`:**
```json
{
  "items": [
    {
      "entityInstanceId": "uuid",
      "schemaId": "uuid",
      "personId": "uuid",
      "householdId": null,
      "fields": { "title": "Passport renewal", "status": "done" },
      "lastUpdatedAt": "2026-04-25T10:00:00Z"
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

Vector similarity search over current entity states. Primary mechanism for resolving `entityInstanceId` from a natural language description.

**Request body:**
```json
{
  "embedding": [0.123, -0.456, 0.789],
  "personId": "uuid",
  "householdId": null,
  "domainId": null,
  "entityType": "todo_item",
  "limit": 10,
  "similarityThreshold": 0.7
}
```

| Field | Type | Required |
|---|---|---|
| `embedding` | float[] | yes |
| `personId` | UUID | no |
| `householdId` | UUID | no |
| `domainId` | UUID | no |
| `entityType` | string | no |
| `limit` | int | no — default `10` |
| `similarityThreshold` | float [0–1] | no — default `0.7` |

**Response `200`:**
```json
{
  "items": [
    {
      "entityInstanceId": "uuid",
      "schemaId": "uuid",
      "personId": "uuid",
      "householdId": null,
      "fields": { "title": "Passport renewal", "status": "done" },
      "lastUpdatedAt": "2026-04-25T10:00:00Z",
      "similarityScore": 0.91
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
| `domainId` | UUID | |
| `entityType` | string | Exact match |
| `activeOnly` | boolean | Default `true` |

**Response `200`:**
```json
{
  "items": [
    {
      "id": "uuid",
      "domainId": "uuid",
      "entityType": "todo_item",
      "schemaVersion": 1,
      "isActive": true,
      "description": "A personal task or reminder",
      "fieldDefinitions": [
        { "name": "title", "type": "text", "mandatory": true, "description": "Task title" },
        { "name": "status", "type": "text", "mandatory": true, "description": "pending | done | cancelled" },
        { "name": "dueDate", "type": "date", "mandatory": false, "description": "ISO 8601 date" },
        { "name": "priority", "type": "text", "mandatory": false, "description": "low | medium | high" }
      ],
      "mandatoryFields": ["title", "status"],
      "createdAt": "2026-04-25T10:00:00Z",
      "updatedAt": "2026-04-25T10:00:00Z"
    }
  ]
}
```

Note: `mandatoryFields` is a generated column — the server derives it from `fieldDefinitions` where `mandatory: true`.

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

Fetch the latest active schema for a (domain, entityType) pair. Uses the `current_entity_type_schema` view.

**Query parameters:**

| Parameter | Type | Required |
|---|---|---|
| `domainId` | UUID | yes |
| `entityType` | string | yes |

**Response `200`:** Full schema row.

**Response `404`:** No active schema found for this (domainId, entityType) pair.

---

### `POST /api/v1/schemas`

**MCP tool:** `create_entity_type_schema`

Create a new schema for a (domain, entityType) pair that does not yet exist. The schema is immediately active (`isActive = true`, `schemaVersion = 1`). Called in the write turn after user approval.

**Request body:**
```json
{
  "domainId": "uuid",
  "entityType": "blood_pressure_reading",
  "fieldDefinitions": [
    { "name": "systolic", "type": "number", "mandatory": true, "description": "Systolic pressure mmHg" },
    { "name": "diastolic", "type": "number", "mandatory": true, "description": "Diastolic pressure mmHg" },
    { "name": "recordedAt", "type": "date", "mandatory": false, "description": "Date of measurement" }
  ],
  "description": "A blood pressure measurement"
}
```

| Field | Type | Required |
|---|---|---|
| `domainId` | UUID | yes |
| `entityType` | string | yes |
| `fieldDefinitions` | object[] | yes |
| `description` | string | no |

**Response `201`:** Full schema row with `isActive = true` and `schemaVersion = 1`.

**Errors:** `400`, `404` (domain not found), `409` (schema already exists for this domainId/entityType pair)

---

### `POST /api/v1/schemas/{domain_id}/{entity_type}/versions`

**MCP tool:** `update_entity_type_schema`

Create a new version of an existing active schema. Increments `schemaVersion`, activates the new row, and deactivates the previous version. The caller must provide the **full field list** for the new version. Called in the write turn after user approval.

**Path parameters:** `domain_id` (UUID), `entity_type` (string)

**Request body:**
```json
{
  "fieldDefinitions": [
    { "name": "systolic", "type": "number", "mandatory": true, "description": "Systolic pressure mmHg" },
    { "name": "diastolic", "type": "number", "mandatory": true, "description": "Diastolic pressure mmHg" },
    { "name": "recordedAt", "type": "date", "mandatory": false, "description": "Date of measurement" },
    { "name": "notes", "type": "text", "mandatory": false, "description": "Free-text notes" }
  ],
  "description": "A blood pressure measurement with optional notes"
}
```

| Field | Type | Required |
|---|---|---|
| `fieldDefinitions` | object[] | yes — full list, not a diff |
| `description` | string | no |

**Response `201`:** New schema row with incremented `schemaVersion` and `isActive = true`.

**Errors:** `404` (no active schema found for this domain/entityType pair)

---

### `DELETE /api/v1/schemas/{domain_id}/{entity_type}/active`

**MCP tool:** `deactivate_entity_type_schema`

Mark the current active schema for a (domain, entityType) pair as inactive. All historical schema versions and extracted facts are retained.

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
    { "id": "uuid", "name": "health", "description": "Health-related information", "createdAt": "2026-04-25T10:00:00Z" },
    { "id": "uuid", "name": "finance", "description": "Financial information", "createdAt": "2026-04-25T10:00:00Z" },
    { "id": "uuid", "name": "employment", "description": "Employment and career", "createdAt": "2026-04-25T10:00:00Z" },
    { "id": "uuid", "name": "personal_details", "description": "Personal contact and address", "createdAt": "2026-04-25T10:00:00Z" },
    { "id": "uuid", "name": "todo", "description": "Tasks and reminders", "createdAt": "2026-04-25T10:00:00Z" },
    { "id": "uuid", "name": "household", "description": "Household shared information", "createdAt": "2026-04-25T10:00:00Z" },
    { "id": "uuid", "name": "news_preferences", "description": "News topics and content preferences", "createdAt": "2026-04-25T10:00:00Z" }
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
    { "id": "uuid", "name": "user_input", "description": "Typed directly in chat", "createdAt": "2026-04-25T10:00:00Z" },
    { "id": "uuid", "name": "file_upload", "description": "PDF, image etc. uploaded by user", "createdAt": "2026-04-25T10:00:00Z" },
    { "id": "uuid", "name": "ai_extracted", "description": "Extracted by AI from another document", "createdAt": "2026-04-25T10:00:00Z" },
    { "id": "uuid", "name": "plaid_poll", "description": "Plaid banking API", "createdAt": "2026-04-25T10:00:00Z" },
    { "id": "uuid", "name": "gmail_poll", "description": "Gmail", "createdAt": "2026-04-25T10:00:00Z" }
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
    { "id": 1, "relationChain": ["father", "sister"], "alias": "bua", "language": "hindi", "description": "Father's sister", "createdAt": "2026-04-25T10:00:00Z" },
    { "id": 2, "relationChain": ["mother", "brother"], "alias": "mama", "language": "hindi", "description": "Mother's brother", "createdAt": "2026-04-25T10:00:00Z" }
  ]
}
```

---

## Group 5 — Audit

### `POST /api/v1/audit/interactions`

**MCP tool:** `log_interaction`

Persist one interaction turn to the audit log. Exactly one of `personId` or `jobType` must be set.

**Request body:**
```json
{
  "messageText": "Mark my passport renewal as done",
  "responseText": "Done — I've marked your passport renewal task as completed.",
  "status": "success",
  "personId": "uuid",
  "jobType": null,
  "toolCallsJson": [
    { "tool": "search_current_facts", "params": { "embedding": [0.1, 0.2], "entityType": "todo_item" } },
    { "tool": "create_fact", "params": { "entityInstanceId": "uuid", "operationType": "update", "fields": { "status": "done" } } }
  ],
  "errorMessage": null
}
```

| Field | Type | Required |
|---|---|---|
| `messageText` | string | yes |
| `responseText` | string | yes |
| `status` | `"success"` \| `"error"` \| `"partial"` | yes |
| `personId` | UUID | no (one of personId/jobType required) |
| `jobType` | string | no (one of personId/jobType required) |
| `toolCallsJson` | object[] | no |
| `errorMessage` | string | no |

**Response `201`:**
```json
{
  "id": "uuid",
  "messageText": "...",
  "responseText": "...",
  "status": "success",
  "personId": "uuid",
  "jobType": null,
  "toolCallsJson": [],
  "errorMessage": null,
  "createdAt": "2026-04-25T10:00:00Z"
}
```

**Errors:** `400` (both personId and jobType present, or neither present; status is error but errorMessage is null)

---

## Group 6 — File Handling

### `POST /api/v1/files`

**MCP tool:** `save_file`

Persist a file to local disk and return its path. The caller submits base64-encoded content. The returned `filePath` is opaque — pass it to `create_document` and/or `POST /api/v1/files/extract-text`.

**Request body:**
```json
{
  "contentBase64": "JVBERi0xLjQK...",
  "filename": "slip-march-2026.pdf",
  "mimeType": "application/pdf"
}
```

| Field | Type | Required |
|---|---|---|
| `contentBase64` | string | yes |
| `filename` | string | yes |
| `mimeType` | string | no — defaults to `application/octet-stream` |

**Response `201`:**
```json
{
  "filePath": "/data/files/2026/04/25/slip-march-2026-abc123.pdf",
  "filename": "slip-march-2026.pdf",
  "mimeType": "application/pdf",
  "sizeBytes": 48210
}
```

**Errors:** `400` (invalid base64), `500` (disk write failure)

---

### `POST /api/v1/files/extract-text`

**MCP tool:** `extract_text_from_file`

Extract plain text from a previously saved file. Dispatches to PDF parser, OCR, or plain-text reader based on file type. The extracted text is what gets stored in `document.contentText`.

**Request body:**
```json
{ "filePath": "/data/files/2026/04/25/slip-march-2026-abc123.pdf" }
```

| Field | Type | Required |
|---|---|---|
| `filePath` | string | yes |

**Response `200`:**
```json
{
  "filePath": "/data/files/2026/04/25/slip-march-2026-abc123.pdf",
  "text": "March 2026 Salary Slip\nEmployer: Acme Corp\nGross: 5000.00\n...",
  "extractionMethod": "pdf_parser"
}
```

`extractionMethod` is one of: `pdf_parser`, `ocr`, `plain_text`.

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
  "filePath": "/data/files/2026/04/25/slip-march-2026-abc123.pdf",
  "contentBase64": "JVBERi0xLjQK...",
  "mimeType": "application/pdf",
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

## Group 7 — Scheduled Jobs

Scheduled jobs drive periodic background work such as news fetching. Each job has a cron expression, an optional config object, and a run history. The scheduler service reads due jobs via `GET /api/v1/scheduled-jobs/due`, executes them, and records results via `POST /api/v1/scheduled-jobs/{id}/runs`.

### Response shapes

**`ScheduledJobResponse`:**

| Field | Type | Description |
|---|---|---|
| `id` | UUID | |
| `sourceType` | string | e.g. `newsapi`, `gmail_poll` |
| `personId` | UUID \| null | |
| `householdId` | UUID \| null | |
| `cronExpression` | string | Standard 5-field cron syntax |
| `config` | object \| null | Provider-specific configuration (JSONB) |
| `enabled` | boolean | Whether the job runs on schedule |
| `nextRunAt` | timestamp \| null | Next scheduled execution time |
| `createdAt` | timestamp | |

**`ScheduledJobRunResponse`:**

| Field | Type | Description |
|---|---|---|
| `id` | UUID | |
| `jobId` | UUID | FK to scheduled job |
| `startedAt` | timestamp | |
| `finishedAt` | timestamp \| null | |
| `status` | `"success"` \| `"error"` \| `"running"` | |
| `error` | string \| null | |
| `articlesStored` | int \| null | Count of articles stored in this run |

---

### `POST /api/v1/scheduled-jobs`

**MCP tool:** `create_scheduled_job`

Create a new scheduled job.

**Request body:**
```json
{
  "sourceType": "newsapi",
  "cronExpression": "0 * * * *",
  "personId": "uuid",
  "householdId": null,
  "config": { "topics": ["technology", "finance"] },
  "enabled": true
}
```

| Field | Type | Required |
|---|---|---|
| `sourceType` | string | yes |
| `cronExpression` | string | yes |
| `personId` | UUID | no (one of personId/householdId required) |
| `householdId` | UUID | no |
| `config` | object | no |
| `enabled` | boolean | no — defaults to `true` |

**Response `201`:** `ScheduledJobResponse`

**Errors:** `400`, `404` (person or household not found)

---

### `GET /api/v1/scheduled-jobs`

**MCP tool:** `list_scheduled_jobs`

List scheduled jobs for a person or household.

**Query parameters:**

| Parameter | Type | Required |
|---|---|---|
| `personId` | UUID | no (one of personId/householdId required) |
| `householdId` | UUID | no |

**Response `200`:**
```json
{ "items": [ /* ScheduledJobResponse[] */ ] }
```

**Errors:** `400` (neither personId nor householdId supplied)

---

### `GET /api/v1/scheduled-jobs/due`

List all enabled jobs whose `nextRunAt` is in the past or null. Used by the scheduler process to discover work to execute.

**Parameters:** none

**Response `200`:**
```json
{ "items": [ /* ScheduledJobResponse[] */ ] }
```

---

### `GET /api/v1/scheduled-jobs/{id}`

Fetch a single scheduled job by UUID.

**Path parameter:** `id` — UUID

**Response `200`:** `ScheduledJobResponse`

**Errors:** `404`

---

### `PATCH /api/v1/scheduled-jobs/{id}`

**MCP tool:** `update_scheduled_job`

Update mutable fields on a scheduled job. Only supplied fields are changed (PATCH semantics).

**Path parameter:** `id` — UUID

**Request body:** Any subset of:
```json
{
  "cronExpression": "0 */6 * * *",
  "config": { "topics": ["health"] },
  "enabled": false,
  "nextRunAt": "2026-05-11T00:00:00Z"
}
```

| Field | Type | Required |
|---|---|---|
| `cronExpression` | string | no |
| `config` | object | no |
| `enabled` | boolean | no |
| `nextRunAt` | timestamp | no |

**Response `200`:** Updated `ScheduledJobResponse`

**Errors:** `400`, `404`

---

### `DELETE /api/v1/scheduled-jobs/{id}`

**MCP tool:** `delete_scheduled_job`

Delete a scheduled job and its run history.

**Path parameter:** `id` — UUID

**Response `204`:** Empty body on success.

**Errors:** `404`

---

### `GET /api/v1/scheduled-jobs/{id}/runs`

Retrieve the run history for a scheduled job, newest first.

**Path parameter:** `id` — UUID

**Response `200`:**
```json
{ "items": [ /* ScheduledJobRunResponse[] */ ] }
```

**Errors:** `404`

---

### `POST /api/v1/scheduled-jobs/{id}/runs`

Record the result of a scheduled job execution.

**Path parameter:** `id` — UUID

**Request body:**
```json
{
  "status": "success",
  "finishedAt": "2026-05-10T01:02:03Z",
  "error": null,
  "articlesStored": 12
}
```

| Field | Type | Required |
|---|---|---|
| `status` | `"success"` \| `"error"` \| `"running"` | yes |
| `finishedAt` | timestamp | no |
| `error` | string | no |
| `articlesStored` | int | no |

**Response `201`:** `ScheduledJobRunResponse`

**Errors:** `404` (job not found)

---

## Group 8 — Source Connections

A `source_connection` is the unified registry for every external data source that feeds the assistant — Plaid bank feeds, Gmail polling, news polling, chatbot re-extractions, bulk file/image imports, etc. Each connection is owned by exactly one person OR one household. Sync can be triggered two independent ways: `sync_scheduled` (cron-driven) and `sync_adhoc` (user-initiated "Refresh Now"). Both flags may be true simultaneously.

Sensitive credentials (OAuth tokens, API keys) are stored AES-256-GCM encrypted in the `secrets` column. The plaintext is **NEVER** returned by any API endpoint — it is decrypted only at sync time inside the connector worker process. Non-secret configuration (institution name, account filters, topics, etc.) lives in `config` as plain JSONB.

Each sync run inserts one row into `sync_runs` with `status='running'`; the connector worker writes the terminal status, `stats`, and `log_lines` on completion.

### Response shapes

**`SourceConnectionResponse`** (the `secrets` field is NEVER included in any response):

| Field | Type | Description |
|---|---|---|
| `id` | UUID | |
| `sourceType` | string | One of: `plaid_poll`, `gmail_poll`, `news_poll`, `chatbot`, `bulk_file`, `bulk_image` |
| `connectionName` | string | Human-readable label shown in the UI |
| `personId` | UUID \| null | Set when the connection is owned by an individual |
| `householdId` | UUID \| null | Set when the connection is owned by a household |
| `config` | object | Non-secret connector configuration (JSONB) |
| `syncScheduled` | boolean | Whether cron-driven polling is enabled |
| `syncAdhoc` | boolean | Whether the user can trigger on-demand syncs |
| `syncSchedule` | string \| null | Cron expression (required when `syncScheduled=true`) |
| `nextRunAt` | timestamp \| null | When the scheduler should next trigger this connection |
| `lastSyncedAt` | timestamp \| null | Timestamp of the most recently completed run |
| `status` | `"active"` \| `"paused"` \| `"error"` | Lifecycle state |
| `createdAt` | timestamp | |
| `updatedAt` | timestamp | Auto-maintained by the `source_connections_updated_at` trigger |

**`SyncRunResponse`:**

| Field | Type | Description |
|---|---|---|
| `id` | UUID | |
| `sourceConnectionId` | UUID | FK to `source_connections` |
| `runType` | `"scheduled"` \| `"adhoc"` \| `"re_extract"` | How the run was triggered |
| `status` | `"running"` \| `"success"` \| `"warning"` \| `"failed"` | Lifecycle state |
| `startedAt` | timestamp | Set on insert |
| `completedAt` | timestamp \| null | Null while the run is in progress |
| `stats` | object \| null | Connector-agnostic JSONB summary, e.g. `{ "added": 23, "modified": 3, "removed": 0, "errors": 0 }` |
| `logLines` | array of objects | Structured log entries, each `{ "time": "ISO-8601", "level": "info\|warn\|error", "msg": "..." }`. Defaults to `[]` |

---

### `POST /api/v1/source-connections`

Create a new source connection. If `secrets` is supplied it is AES-256-GCM encrypted before storage; the plaintext is never persisted and never echoed back.

**Request body:**
```json
{
  "sourceType": "plaid_poll",
  "connectionName": "Chase Checking",
  "personId": "uuid",
  "householdId": null,
  "config": { "institutionName": "Chase", "institutionId": "ins_3" },
  "secrets": "{\"access_token\":\"access-sandbox-xxx\",\"item_id\":\"yyy\"}",
  "syncScheduled": false,
  "syncAdhoc": true,
  "syncSchedule": null
}
```

| Field | Type | Required |
|---|---|---|
| `sourceType` | string | yes |
| `connectionName` | string | yes |
| `personId` | UUID | no (exactly one of `personId`/`householdId` required) |
| `householdId` | UUID | no |
| `config` | object | no — defaults to `{}` |
| `secrets` | string | no — plaintext credentials to encrypt; null/omitted = no secrets |
| `syncScheduled` | boolean | no — defaults to `false` |
| `syncAdhoc` | boolean | no — defaults to `true` |
| `syncSchedule` | string | required when `syncScheduled=true`, otherwise must be null |

**Response `201`:** `SourceConnectionResponse` (no `secrets` field).

**Errors:** `400`, `422` (e.g. neither/both of personId/householdId set, or `syncScheduled` without `syncSchedule`), `404` (person or household not found).

---

### `GET /api/v1/source-connections`

List source connections for a person or household.

**Query parameters:**

| Parameter | Type | Required |
|---|---|---|
| `personId` | UUID | no (one of `personId`/`householdId` required) |
| `householdId` | UUID | no |

**Response `200`:**
```json
{ "items": [ /* SourceConnectionResponse[] */ ] }
```

**Errors:** `400` (neither `personId` nor `householdId` supplied).

---

### `GET /api/v1/source-connections/{id}`

Fetch a single source connection by UUID. The response never includes the `secrets` field.

**Path parameter:** `id` — UUID

**Response `200`:** `SourceConnectionResponse`

**Errors:** `400`, `404`

---

### `PUT /api/v1/source-connections/{id}`

Full update of a source connection. All fields in the request body replace the stored values.

Special handling of `secrets`:
- If `secrets` is omitted, `null`, or an empty string → the existing encrypted blob is preserved (no overwrite).
- If `secrets` is a non-empty string → it is re-encrypted with AES-256-GCM and overwrites the stored blob.

**Path parameter:** `id` — UUID

**Request body:** identical shape to `POST /api/v1/source-connections`.

**Response `200`:** updated `SourceConnectionResponse` (no `secrets` field).

**Errors:** `400`, `404`, `422`

---

### `DELETE /api/v1/source-connections/{id}`

Delete a source connection. ON DELETE CASCADE removes the connection's `sync_runs` history as well.

**Path parameter:** `id` — UUID

**Response `204`:** Empty body on success.

**Errors:** `400`, `404`

---

### `POST /api/v1/source-connections/{id}/sync`

Trigger an adhoc sync run for a source connection. Inserts a row into `sync_runs` with `runType='adhoc'` and `status='running'`; the actual fetch + ingest work is performed asynchronously by the connector worker.

**Path parameter:** `id` — UUID

**Request body:** `{}` (empty object).

**Response `202 Accepted`:**
```json
{ "message": "sync queued", "connectionId": "uuid" }
```

**Errors:** `400`, `404`, `409` (connection not eligible — e.g. `syncAdhoc=false`).

---

### `GET /api/v1/source-connections/{id}/runs`

List run history for a source connection, newest first.

**Path parameter:** `id` — UUID

**Query parameters:**

| Parameter | Type | Required | Default |
|---|---|---|---|
| `limit` | int | no | `20` |

**Response `200`:**
```json
{ "items": [ /* SyncRunResponse[] */ ] }
```

**Errors:** `400`, `404`

---

### `GET /api/v1/source-connections/{id}/runs/latest`

Convenience endpoint returning the most recent adhoc run and the most recent scheduled run for a connection. Either field is null when no run of that type has ever occurred.

**Path parameter:** `id` — UUID

**Response `200`:**
```json
{
  "lastAdhoc":     { /* SyncRunResponse */ } /* | null */,
  "lastScheduled": { /* SyncRunResponse */ } /* | null */
}
```

**Errors:** `400`, `404`

---

### `GET /api/v1/source-connections/{id}/runs/{run_id}`

Fetch a single sync run by its UUID, including the full `logLines` array.

**Path parameters:** `id` — UUID, `run_id` — UUID

**Response `200`:** `SyncRunResponse`

**Errors:** `400`, `404`

---

### `GET /api/v1/source-connections/due`

Scheduler-internal endpoint. Returns the connections whose cron-driven sync is due —
i.e. `sync_scheduled = true AND status = 'active' AND (next_run_at IS NULL OR next_run_at <= now())`.
Used by the Python scheduler worker to discover work; not intended for client UIs.

**Request body:** none.

**Response `200`:**
```json
{ "items": [ /* SourceConnectionResponse[] */ ] }
```

**Errors:** `500` on database failure.

---

### `GET /api/v1/source-connections/{id}/secrets`

Scheduler-internal endpoint. Returns the decrypted `secrets` JSON object for a given
connection so the connector worker can authenticate against the upstream provider.
Never called from client UIs. The HTTP server decrypts the AES-256-GCM blob inline
and returns the resulting JSON object (or `null` if the column is empty).

**Path parameter:** `id` — UUID

**Response `200`:**
```json
{ "secrets": { "access_token": "access-sandbox-xxx", "item_id": "yyy" } }
```
or, when the secrets column is NULL:
```json
{ "secrets": null }
```

**Errors:** `400` (bad UUID), `404` (connection not found), `500`.

---

### `PATCH /api/v1/source-connections/{id}/runs/{run_id}`

Scheduler-internal endpoint. Updates a `sync_runs` row in place — typically used by
the connector worker on completion to record terminal status, stats, completion
timestamp, and the structured log lines emitted during the run.

All fields are optional (PATCH semantics). Only the supplied fields are written;
omitted fields are left unchanged.

**Path parameters:** `id` — UUID (connection), `run_id` — UUID (run)

**Request body:**
```json
{
  "status":      "success",
  "completedAt": "2026-05-18T06:01:23.456Z",
  "stats":       { "added": 23, "modified": 3, "removed": 0, "errors": 0 },
  "logLines":    [
    { "time": "06:00:01", "level": "info", "msg": "..." }
  ]
}
```

| Field | Type | Required |
|---|---|---|
| `status` | `"running"` \| `"success"` \| `"warning"` \| `"failed"` | no |
| `completedAt` | ISO-8601 | no |
| `stats` | object | no |
| `logLines` | array of objects | no |

**Response `200`:** updated `SyncRunResponse`.

**Errors:** `400`, `404`, `422`.

---

### `POST /api/v1/source-connections/{id}/advance`

Scheduler-internal endpoint. Sets `next_run_at` on the source connection so the
scheduler advances past the just-completed run. Equivalent to calling `PUT` with
the full body, but exists as a focused endpoint to avoid round-tripping all fields
through the scheduler.

**Path parameter:** `id` — UUID

**Request body:**
```json
{ "nextRunAt": "2026-05-19T02:00:00Z" }
```

**Response `204`:** empty body.

**Errors:** `400`, `404`.

---

### `POST /api/v1/source-connections/{id}/mark-synced`

Scheduler-internal endpoint. Sets `last_synced_at` on the source connection after
a successful sync run.

**Path parameter:** `id` — UUID

**Request body:**
```json
{ "lastSyncedAt": "2026-05-18T06:01:23.456Z" }
```

**Response `204`:** empty body.

**Errors:** `400`, `404`.

---

### `POST /api/v1/source-connections/{id}/runs/create-scheduled`

Scheduler-internal endpoint. Inserts a `sync_runs` row with `runType='scheduled'`
and `status='running'` so the connector worker has a row to PATCH on completion.
Mirrors the adhoc `POST /api/v1/source-connections/{id}/sync` flow but for cron-driven
runs.

**Path parameter:** `id` — UUID

**Request body:** `{}` (empty object).

**Response `201`:** `SyncRunResponse` — the newly inserted row.

**Errors:** `400`, `404`.

---

## Group 9 — Plaid Native Tables

The Plaid connector stores its data in dedicated `plaid.*` PostgreSQL tables rather than the generic `document`/`fact` store — see schema `13_plaid_schema.sql`. These endpoints are scheduler/connector-internal upsert helpers; the chatbot reads from facts/views as usual, and the UI uses the unified view builder.

### `POST /api/v1/plaid/connections/upsert`

Upsert a `plaid.connections` row. Insert if `plaid_item_id` does not exist; otherwise
overwrite `institution_name` and `cursor` on the existing row.

**Request body:**
```json
{
  "sourceConnectionId": "uuid",
  "plaidItemId":        "eVBnVMp7zdTJLkRNr35Rs6zs4H9b2Y8Kx5GRDN",
  "institutionName":    "Chase",
  "cursor":             "opaque-bookmark-or-null"
}
```

| Field | Type | Required |
|---|---|---|
| `sourceConnectionId` | UUID | yes |
| `plaidItemId` | string | yes |
| `institutionName` | string | yes |
| `cursor` | string \| null | no |

**Response `200`:**
```json
{
  "id":                 "uuid",
  "sourceConnectionId": "uuid",
  "plaidItemId":        "...",
  "institutionName":    "...",
  "cursor":             "...|null",
  "createdAt":          "ISO-8601",
  "updatedAt":          "ISO-8601"
}
```

**Errors:** `400`, `404` (source connection not found), `500`.

---

### `POST /api/v1/plaid/accounts/upsert`

Upsert a `plaid.bank_accounts` row. Insert if `plaid_account_id` does not exist;
otherwise overwrite `name`, `account_type`, and `current_balance` on the existing row.

**Request body:**
```json
{
  "sourceConnectionId": "uuid",
  "connectionId":       "uuid",
  "plaidAccountId":     "BxBXxLj1m4HMXBm9WZZmCWVbPjX16EHwv99vp",
  "name":               "Plaid Checking",
  "accountType":        "depository",
  "currentBalance":     1234.56
}
```

| Field | Type | Required |
|---|---|---|
| `sourceConnectionId` | UUID | yes |
| `connectionId` | UUID | yes (FK to `plaid.connections.id`) |
| `plaidAccountId` | string | yes |
| `name` | string | yes |
| `accountType` | string | yes |
| `currentBalance` | number \| null | no |

**Response `200`:** the upserted bank account row including its UUID and timestamps.

**Errors:** `400`, `404`, `500`.

---

### `POST /api/v1/plaid/transactions/batch`

Batch upsert + delete transactions for an account. Each entry in `added` and `modified`
is upserted into `plaid.transactions` on `plaid_transaction_id`. Each id in
`removedPlaidTransactionIds` is deleted (hard delete — Plaid issues stable IDs that are
never reused).

**Request body:**
```json
{
  "sourceConnectionId": "uuid",
  "accountId":          "uuid",
  "added":              [ /* PlaidTransactionWrite */ ],
  "modified":           [ /* PlaidTransactionWrite */ ],
  "removedPlaidTransactionIds": ["txn-id-1", "txn-id-2"]
}
```

`PlaidTransactionWrite`:
```json
{
  "plaidTransactionId": "lPNjeW1nR6CDn5okmGQ6hEpMo4lLNoSrzqDje",
  "amount":             12.34,
  "date":               "2026-05-15",
  "merchantName":       "Starbucks",
  "category":           ["Food and Drink", "Coffee Shop"],
  "paymentChannel":     "in store",
  "pending":            false
}
```

| Field | Type | Required |
|---|---|---|
| `plaidTransactionId` | string | yes |
| `amount` | number | yes |
| `date` | ISO date | yes |
| `merchantName` | string \| null | no |
| `category` | string[] | no — defaults to `[]` |
| `paymentChannel` | string \| null | no |
| `pending` | boolean | no — defaults to `false` |

**Response `200`:**
```json
{ "added": 23, "modified": 3, "removed": 0 }
```

**Errors:** `400`, `404`, `500`.

---

## Unified Schemas

Unified schemas are LLM-proposed, user-tunable cross-source schema definitions. Each schema belongs to exactly one person OR one household. `field_definitions` is a JSONB array defining unified fields with per-source mappings.

### GET /api/v1/unified-schemas

List all unified schemas for a person or household.

**Query parameters:**
- `personId` (UUID, optional)
- `householdId` (UUID, optional)

**Response 200:**
```json
{
  "items": [
    {
      "id": "uuid",
      "personId": "uuid | null",
      "householdId": "uuid | null",
      "name": "transaction",
      "description": "string | null",
      "status": "proposed | approved",
      "fieldDefinitions": [
        {
          "name": "merchant",
          "type": "text",
          "status": "approved | pending | rejected",
          "sources": [
            { "source_connection_id": "uuid", "source_table": "plaid.transactions", "source_field": "merchant_name" }
          ]
        }
      ],
      "createdAt": "2026-05-22T00:00:00Z",
      "updatedAt": "2026-05-22T00:00:00Z"
    }
  ]
}
```

### GET /api/v1/unified-schemas/source-schemas

Returns all source schemas for the pivot (person or household). **Must be registered before `/{id}` in the router** to avoid the literal "source-schemas" being parsed as an ID.

**Query parameters:** `personId` or `householdId`

**Response 200:**
```json
{
  "profile": {
    "sourceConnectionId": null,
    "sourceType": "profile",
    "connectionName": "Profile",
    "tables": [
      {
        "tableName": "person",
        "columns": [{ "name": "id", "dataType": "UUID" }, { "name": "display_name", "dataType": "TEXT" }],
        "foreignKeys": []
      }
    ]
  },
  "sources": [
    {
      "sourceConnectionId": "uuid",
      "sourceType": "plaid_poll",
      "connectionName": "Chase Checking",
      "tables": [
        {
          "tableName": "plaid.transactions",
          "columns": [{ "name": "amount", "dataType": "DECIMAL(15,2)" }],
          "foreignKeys": [{ "column": "account_id", "refTable": "plaid.bank_accounts", "refColumn": "id" }]
        }
      ]
    }
  ]
}
```

### GET /api/v1/unified-schemas/{id}

Fetch a single unified schema by ID.

**Response 200:** UnifiedSchema object (see GET list for shape).
**Response 404:** `{"error":"not_found","message":"unified_schema <id> not found"}`

### POST /api/v1/unified-schemas

Create a unified schema (typically called to store an LLM-proposed schema).

**Request body:**
```json
{
  "personId": "uuid | null",
  "householdId": "uuid | null",
  "name": "transaction",
  "description": "optional string",
  "fieldDefinitions": [
    {
      "name": "merchant",
      "type": "text",
      "status": "pending",
      "sources": [
        { "source_connection_id": "uuid", "source_table": "plaid.transactions", "source_field": "merchant_name" }
      ]
    }
  ]
}
```

Exactly one of `personId` or `householdId` must be set. `status` defaults to `"proposed"` if omitted.

**Response 201:** Created UnifiedSchema object.
**Response 400:** Validation error (neither or both owner IDs set, invalid status).

### PATCH /api/v1/unified-schemas/{id}

Update field statuses (accept/reject individual fields) or approve the overall schema. All body fields are optional.

**Request body:**
```json
{
  "name": "string",
  "description": "string",
  "status": "proposed | approved",
  "fieldDefinitions": [...]
}
```

**Response 200:** Updated UnifiedSchema object.
**Response 404:** Not found.

### DELETE /api/v1/unified-schemas/{id}

Remove a unified schema permanently.

**Response 204:** No content.
**Response 404:** Not found.

### GET /api/v1/unified-schemas/{id}/data

Read-time UNION query — returns rows from all contributing sources mapped to unified fields. Queries Plaid native tables and entity_type_schema sources at query time (no materialization). Each row is tagged with source provenance.

**Query parameters:**
- `limit` (int, default 50, max 200)
- `offset` (int, default 0)

**Response 200:**
```json
{
  "items": [
    {
      "sourceConnectionId": "uuid | null",
      "sourceType": "plaid_poll",
      "fields": {
        "id": "uuid",
        "amount": "42.50",
        "date": "2026-05-22",
        "merchant_name": "Starbucks",
        "category": "Food and Drink",
        "payment_channel": "in store",
        "pending": "false"
      }
    }
  ],
  "total": 1,
  "limit": 50,
  "offset": 0
}
```

Only approved fields are included in query results. Returns empty if no approved fields exist.

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
| 44 | POST | `/api/v1/scheduled-jobs` | `create_scheduled_job` |
| 45 | GET | `/api/v1/scheduled-jobs` | `list_scheduled_jobs` |
| 46 | GET | `/api/v1/scheduled-jobs/due` | (scheduler internal) |
| 47 | GET | `/api/v1/scheduled-jobs/{id}` | — |
| 48 | PATCH | `/api/v1/scheduled-jobs/{id}` | `update_scheduled_job` |
| 49 | DELETE | `/api/v1/scheduled-jobs/{id}` | `delete_scheduled_job` |
| 50 | GET | `/api/v1/scheduled-jobs/{id}/runs` | — |
| 51 | POST | `/api/v1/scheduled-jobs/{id}/runs` | (scheduler internal) |
| 52 | POST | `/api/v1/source-connections` | — |
| 53 | GET | `/api/v1/source-connections` | — |
| 54 | GET | `/api/v1/source-connections/{id}` | — |
| 55 | PUT | `/api/v1/source-connections/{id}` | — |
| 56 | DELETE | `/api/v1/source-connections/{id}` | — |
| 57 | POST | `/api/v1/source-connections/{id}/sync` | — |
| 58 | GET | `/api/v1/source-connections/{id}/runs` | — |
| 59 | GET | `/api/v1/source-connections/{id}/runs/latest` | — |
| 60 | GET | `/api/v1/source-connections/{id}/runs/{run_id}` | — |
| 61 | GET | `/api/v1/source-connections/due` | (scheduler internal) |
| 62 | GET | `/api/v1/source-connections/{id}/secrets` | (scheduler internal) |
| 63 | PATCH | `/api/v1/source-connections/{id}/runs/{run_id}` | (scheduler internal) |
| 64 | POST | `/api/v1/source-connections/{id}/advance` | (scheduler internal) |
| 65 | POST | `/api/v1/source-connections/{id}/mark-synced` | (scheduler internal) |
| 66 | POST | `/api/v1/source-connections/{id}/runs/create-scheduled` | (scheduler internal) |
| 67 | POST | `/api/v1/plaid/connections/upsert` | (connector internal) |
| 68 | POST | `/api/v1/plaid/accounts/upsert` | (connector internal) |
| 69 | POST | `/api/v1/plaid/transactions/batch` | (connector internal) |
| — | GET | `/health` | (health check, no auth) |
