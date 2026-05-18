# Plaid Integration — Design Spec
**Date:** 2026-05-15
**Branch:** feature/implement-plaid
**Status:** Approved for implementation

---

## Overview

Integrate Plaid to pull financial data (transactions, account balances, account metadata) into the personal assistant. A dedicated Finance tab in the UI handles one-time account setup. A scheduled poller (same pattern as the news poller) fetches data on a user-defined schedule and stores everything as documents + facts, making it queryable via chat.

---

## Scope

**In (v1):**
- Accounts — name, type, subtype, mask, institution
- Balances — current and available, per account
- Transactions — amount, merchant, category, date, pending status

**Out (v1, future iterations):**
- Investments (holdings, securities)
- Liabilities (credit card APR, mortgage, student loans)
- Webhooks (polling is sufficient)
- Multiple Plaid accounts per person (start with one)

**Plaid environment:** Sandbox first (no items consumed from Development quota). Switching to Development is a single env var change (`PLAID_ENV=development`).

---

## Architecture

```
Frontend
  ├── Chat tab     → chatbot_server  (LLM / MCP / streaming — unchanged)
  └── Finance tab  → Scala HTTP server (CRUD + Plaid OAuth endpoints)

Scala HTTP server
  └── 2 new Plaid endpoints + stores facts/documents as always

Plaid poller (backend/scheduler/)
  └── Calls Plaid API directly → stores via existing Scala HTTP endpoints

Plaid API (sandbox)
  └── /link/token/create
  └── /item/public_token/exchange
  └── /transactions/sync
  └── /accounts/get
```

No new DB tables. Everything stored via existing document + fact system.

---

## Data Model

### New entity type schemas (seeded via migration)

| domain  | entity_type       | version | fields |
|---------|-------------------|---------|--------|
| finance | plaid_connection  | v1      | item_id, institution_id, institution_name, access_token, sync_cursor, last_synced_at |
| finance | transaction       | v1      | transaction_id, account_id, amount, iso_currency_code, merchant_name, name, category, date, pending |
| finance | bank_account      | v1      | account_id, item_id, name, official_name, type, subtype, mask, current_balance, available_balance, iso_currency_code, institution_name |

### plaid_connection fact
- One fact per connected bank (one Plaid item = one institution)
- `entity_instance_id`: derived from `item_id` (stable, unique per connection)
- `access_token`: stored as a fact field — sensitive but consistent with how all personal data is stored (DB is already behind auth)
- `sync_cursor`: updated via fact update operation after each sync run. Append-only creates a new operation each sync; `current_facts` view always reflects latest state.

### transaction fact
- `entity_instance_id`: derived from Plaid `transaction_id` (stable across syncs)
- Created on `added`, updated on `modified`, delete operation on `removed` (from `/transactions/sync` response)

### bank_account fact
- `entity_instance_id`: derived from Plaid `account_id`
- Updated on each sync with latest balance

---

## Plaid Link OAuth Flow

Plaid Link is a browser widget that requires a `link_token` created server-side (needs `PLAID_SECRET`). Flow:

```
Finance tab                    Scala HTTP server              Plaid
    │                                │                          │
    │  POST /api/v1/plaid/link-token        │                          │
    │──────────────────────────────▶│                          │
    │                                │  /link/token/create      │
    │                                │─────────────────────────▶│
    │                                │◀─────────────────────────│
    │◀──────────────────────────────│  { link_token }           │
    │                                │                          │
    │  [Plaid Link widget opens]     │                          │
    │  [user selects bank, auths]    │                          │
    │  onSuccess(public_token)       │                          │
    │                                │                          │
    │  POST /api/v1/plaid/exchange          │                          │
    │  { public_token, personId }    │                          │
    │──────────────────────────────▶│                          │
    │                                │  /item/public_token/     │
    │                                │  exchange                │
    │                                │─────────────────────────▶│
    │                                │◀─────────────────────────│
    │                                │  { access_token,         │
    │                                │    item_id }             │
    │                                │                          │
    │                                │  /accounts/get           │
    │                                │─────────────────────────▶│
    │                                │◀─────────────────────────│
    │                                │  { institution, accounts}│
    │                                │                          │
    │                                │  POST /api/v1/documents  │
    │                                │  POST /api/v1/facts      │
    │                                │  (plaid_connection fact) │
    │◀──────────────────────────────│  200 OK                  │
```

---

## Scala HTTP Server — New Endpoints

### `POST /api/v1/plaid/link-token`
**Request:**
```json
{ "personId": "uuid" }
```
**Action:** Calls Plaid `/link/token/create` with `user.client_user_id = personId`, products `["transactions"]`.
**Response:**
```json
{ "linkToken": "link-sandbox-..." }
```

### `POST /api/v1/plaid/exchange`
**Request:**
```json
{ "personId": "uuid", "publicToken": "public-sandbox-..." }
```
**Action:**
1. Calls Plaid `/item/public_token/exchange` → gets `access_token`, `item_id`
2. Calls Plaid `/accounts/get` → gets institution name, account metadata
3. Creates a document (source_type=`user_input`, content="Connected [institution] via Plaid on [date]")
4. Creates a `plaid_connection` fact with all fields
**Response:**
```json
{ "itemId": "...", "institutionName": "Chase" }
```

### Existing endpoints reused by Finance tab
- `GET /api/v1/facts?domain=finance&entityType=plaid_connection&personId=uuid` — list connected accounts
- `POST /api/v1/facts` with `operation=delete` on the `plaid_connection` entity — disconnect account

---

## Plaid Poller

**File:** `backend/scheduler/handlers/plaid_poll.py`
**sourceType:** `plaid_poll` (already seeded in source_type table)

### Schedule
No default schedule. User creates it via chat: *"Run Plaid sync every night at 2am"*. Same as news.

### Run sequence per job execution
```
1. Fetch all plaid_connection facts for the person
   GET /api/v1/facts?domain=finance&entityType=plaid_connection

2. For each connection:
   a. Call Plaid /transactions/sync with stored cursor
      → response: { added[], modified[], removed[], next_cursor }

   b. Call Plaid /accounts/get
      → response: { accounts[] with balances }

   c. Store sync batch as one document (source_type=plaid_poll)

   d. For each added transaction   → POST fact (operation=create)
      For each modified transaction → POST fact (operation=update)
      For each removed transaction  → POST fact (operation=delete)

   e. For each account → POST fact bank_account (operation=update, upsert semantics)

   f. Update plaid_connection fact: new cursor + last_synced_at
      POST fact (operation=update, entity_instance_id=same as connection)
```

### Registration in main.py
```python
from handlers.plaid_poll import PlaidPollHandler

def build_handler_map(http):
    return {
        "news_poll": NewsPollHandler(http),
        "plaid_poll": PlaidPollHandler(http),
    }
```

---

## Frontend — Finance Tab

### Tab navigation
Add a second tab to the existing single-page layout:
```
[ Chat ]  [ Finance ]
```

### Finance tab layout
```
Connected Accounts
┌──────────────────────────────────────────────┐
│ 🏦 Chase                                     │
│    Checking ····1234                         │
│    Savings  ····5678          [Disconnect]   │
├──────────────────────────────────────────────┤
│ 💳 American Express                          │
│    Credit   ····9012          [Disconnect]   │
└──────────────────────────────────────────────┘
[ + Connect account ]
```

### Plaid Link integration
- Package: `react-plaid-link`
- On "Connect account": call `POST /api/v1/plaid/link-token` → open Plaid Link
- `onSuccess(public_token, metadata)`: call `POST /api/v1/plaid/exchange` → refresh list
- `onExit`: close widget, no-op

### Data flow
- On mount: `GET /api/v1/facts?domain=finance&entityType=plaid_connection` → render list
- Institution name + account list: from the `plaid_connection` fact fields
- Disconnect: `POST /api/v1/facts` with delete operation → remove from list

---

## Configuration

### New env vars
```
PLAID_CLIENT_ID=        # from Plaid dashboard
PLAID_SECRET=           # from Plaid dashboard (sandbox secret)
PLAID_ENV=sandbox       # sandbox | development | production
```

Added to:
- `.env.tmp` (local dev)
- `.env.synology.example` (production)
- Scala HTTP server environment in `docker-compose.yml`

---

## Implementation Order

1. **Scala HTTP server** — Plaid client, 2 new endpoints, entity type schema migration
2. **Plaid poller** — `plaid_poll.py` handler, register in `main.py`
3. **Frontend** — tab nav, Finance tab component, `react-plaid-link` integration
4. **Config** — env vars across `.env.tmp`, `.env.synology.example`, `docker-compose.yml`

---

## Out of Scope (future)

- Multiple Plaid accounts per person
- Investments / Liabilities Plaid products
- Webhook-based real-time updates
- Transaction categorisation / enrichment beyond what Plaid provides
- Cloudflare tunnel route for Finance tab (already served under same domain)
