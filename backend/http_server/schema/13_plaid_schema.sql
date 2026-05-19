-- ============================================================
-- 13_plaid_schema.sql
-- Plaid banking connector — native first-class tables
--
-- WHY A SEPARATE SCHEMA?
--   Bank account and transaction data from Plaid is highly
--   structured and relational. Cramming it into the generic
--   document/fact store would lose type safety, prevent efficient
--   SQL aggregations (balances, spending by category, date-range
--   queries), and make joins awkward. Instead, Plaid data gets
--   its own "plaid.*" schema with proper columns, foreign keys,
--   and indexes.
--
-- RELATIONSHIP TO source_connections
--   Every table carries source_connection_id as the universal
--   scope key. This ties each Plaid item, account, and
--   transaction back to the owning connection record in
--   source_connections — enabling cascaded deletes, access
--   control checks, and audit queries at the connection level.
--
-- VECTOR COLUMNS
--   bank_accounts.embedding and transactions.embedding are
--   VECTOR(1536) columns holding OpenAI text-embedding-3-small
--   embeddings. These enable semantic search over account
--   descriptions and transaction narratives via pgvector
--   operators (<=> cosine distance, <-> L2 distance).
--
-- UUID GENERATION
--   Uses gen_random_uuid() (pgcrypto / PostgreSQL 13+) consistent
--   with schemas 10-12. The earlier uuid_generate_v4() style
--   from uuid-ossp is not used here.
--
-- DELETE SEMANTICS
--   All FK references use ON DELETE CASCADE. Plaid tables are
--   connector-specific data — when the source_connection is
--   removed, all associated items, accounts, and transactions
--   are removed with it. Same logic cascades from connection
--   to bank_accounts to transactions.
-- ============================================================


-- ------------------------------------------------------------
-- SCHEMA
-- ------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS plaid;

COMMENT ON SCHEMA plaid IS
  'Native relational tables for the Plaid banking connector.
   All Plaid-sourced data lives here rather than in the generic
   document/fact store so that bank accounts and transactions
   retain full relational structure, type safety, and efficient
   SQL query patterns (balance aggregations, category roll-ups,
   date-range scans).

   Every table is scoped by source_connection_id (FK to
   source_connections) so that data is always traceable to its
   owning connection and is cleaned up automatically when the
   connection is deleted.

   Relationships:
     source_connections  1──* plaid.connections
     plaid.connections   1──* plaid.bank_accounts
     plaid.bank_accounts 1──* plaid.transactions';


-- ------------------------------------------------------------
-- TABLE: plaid.connections
-- One row per Plaid Item (one bank link == one Item).
-- ------------------------------------------------------------

CREATE TABLE plaid.connections (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_connection_id  UUID        NOT NULL REFERENCES source_connections(id) ON DELETE CASCADE,
    plaid_item_id         TEXT        NOT NULL,
    institution_name      TEXT        NOT NULL,
    cursor                TEXT,                          -- incremental sync cursor (transactions/sync API)
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_plaid_connections_source_connection
    ON plaid.connections(source_connection_id);

COMMENT ON TABLE plaid.connections IS
  'One row per Plaid Item — a single authenticated bank link.
   A user may have multiple Plaid connections (e.g. one for Chase,
   one for Vanguard). Each connection maps to one source_connections
   row and carries the Plaid-assigned item_id plus the institution
   name resolved during the Link flow.

   The cursor column is the opaque bookmark returned by the
   Plaid /transactions/sync endpoint. It advances forward with
   each incremental fetch and is stored here so the next sync
   run can resume from where the previous one stopped.';

COMMENT ON COLUMN plaid.connections.id IS
  'Internal UUID for this Plaid connection row.
   Referenced by plaid.bank_accounts.connection_id.';

COMMENT ON COLUMN plaid.connections.source_connection_id IS
  'FK to source_connections.id — the universal scope key.
   Cascades deletes: removing the source_connection removes
   this row and all child bank_accounts and transactions.';

COMMENT ON COLUMN plaid.connections.plaid_item_id IS
  'Plaid-assigned item identifier returned during the Link
   exchange. Globally unique within the Plaid platform.
   Used when calling Plaid management endpoints (e.g.
   /item/get, /item/remove).
   Example: "eVBnVMp7zdTJLkRNr35Rs6zs4H9b2Y8Kx5GRDN"';

COMMENT ON COLUMN plaid.connections.institution_name IS
  'Human-readable name of the financial institution resolved
   during the Link flow (e.g. "Chase", "Vanguard", "Bank of
   America"). Stored for display purposes; authoritative name
   comes from Plaid''s institution registry.';

COMMENT ON COLUMN plaid.connections.cursor IS
  'Opaque pagination cursor returned by the Plaid
   /transactions/sync API. The connector worker stores the
   cursor after each successful incremental fetch and uses it
   as the starting point for the next run.
   Null until the first successful sync has completed.';

COMMENT ON COLUMN plaid.connections.created_at IS
  'Timestamp when this Plaid item was first linked.
   Never updated — reflects original link time.';

COMMENT ON COLUMN plaid.connections.updated_at IS
  'Last modified timestamp, maintained by plaid_connections_updated_at trigger. Advances when cursor or institution_name changes.';


-- ------------------------------------------------------------
-- TABLE: plaid.bank_accounts
-- One row per Plaid account within an Item.
-- ------------------------------------------------------------

CREATE TABLE plaid.bank_accounts (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_connection_id  UUID        NOT NULL REFERENCES source_connections(id) ON DELETE CASCADE,
    connection_id         UUID        NOT NULL REFERENCES plaid.connections(id) ON DELETE CASCADE,
    plaid_account_id      TEXT        NOT NULL UNIQUE,
    name                  TEXT        NOT NULL,
    account_type          TEXT        NOT NULL,
    current_balance       DECIMAL(15,2),
    embedding             VECTOR(1536),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_plaid_bank_accounts_source_connection
    ON plaid.bank_accounts(source_connection_id);

CREATE INDEX idx_plaid_bank_accounts_connection
    ON plaid.bank_accounts(connection_id);

COMMENT ON TABLE plaid.bank_accounts IS
  'One row per Plaid account within an Item. A single bank link
   (connection) often surfaces multiple accounts — e.g. a Chase
   Item may include a checking account, a savings account, and
   a credit card.

   current_balance reflects the balance at the time of the most
   recent sync run; it is overwritten on each sync, not versioned.
   Historical balance tracking (if needed) should use the
   transactions table or a dedicated balance-history table.

   embedding holds a VECTOR(1536) OpenAI text-embedding-3-small
   representation of the account name and type, enabling semantic
   search queries like "find my investment account".';

COMMENT ON COLUMN plaid.bank_accounts.id IS
  'Internal UUID for this bank account row.
   Referenced by plaid.transactions.account_id.';

COMMENT ON COLUMN plaid.bank_accounts.source_connection_id IS
  'FK to source_connections.id — the universal scope key.
   Denormalised here (also reachable via connection_id →
   plaid.connections.source_connection_id) to allow efficient
   single-table scans scoped by connection.';

COMMENT ON COLUMN plaid.bank_accounts.connection_id IS
  'FK to plaid.connections.id — the Plaid Item that owns this
   account. Cascades deletes: removing the connection removes
   all its accounts and their transactions.';

COMMENT ON COLUMN plaid.bank_accounts.plaid_account_id IS
  'Plaid-assigned account identifier. Globally unique within
   Plaid (UNIQUE constraint enforced). Used when calling Plaid
   account endpoints and when matching incoming transaction
   records to the correct account row.
   Example: "BxBXxLj1m4HMXBm9WZZmCWVbPjX16EHwv99vp"';

COMMENT ON COLUMN plaid.bank_accounts.name IS
  'Account display name as returned by Plaid (e.g. "Plaid
   Checking", "Plaid Saving", "Plaid Credit Card"). May be
   customised by the user inside their bank''s interface.';

COMMENT ON COLUMN plaid.bank_accounts.account_type IS
  'High-level Plaid account type. Common values:
     "depository"  — checking, savings, money market
     "credit"      — credit card, line of credit
     "investment"  — brokerage, retirement
     "loan"        — mortgage, student loan, auto
     "other"       — anything not categorised above
   Sourced from Plaid''s account.type field.';

COMMENT ON COLUMN plaid.bank_accounts.current_balance IS
  'Account balance as of the most recent sync run, in the
   account''s native currency (USD for US-based accounts).
   Overwritten on each sync — not a historical record.
   Null if Plaid did not return balance data for this account
   (common for some investment account sub-types).';

COMMENT ON COLUMN plaid.bank_accounts.embedding IS
  'VECTOR(1536) OpenAI text-embedding-3-small embedding of a
   short text representation of this account (name + type).
   Used by the semantic search layer to answer natural-language
   queries like "show me my savings accounts".
   Populated asynchronously by the embed service after sync;
   null until the first embed pass completes.';

COMMENT ON COLUMN plaid.bank_accounts.created_at IS
  'Timestamp when this account row was first inserted.
   Not updated on subsequent syncs — balance changes do not
   alter this timestamp.';

COMMENT ON COLUMN plaid.bank_accounts.updated_at IS
  'Last modified timestamp, maintained by plaid_bank_accounts_updated_at trigger. Advances on every balance refresh.';


-- ------------------------------------------------------------
-- TABLE: plaid.transactions
-- One row per Plaid transaction.
-- ------------------------------------------------------------

CREATE TABLE plaid.transactions (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_connection_id  UUID        NOT NULL REFERENCES source_connections(id) ON DELETE CASCADE,
    account_id            UUID        NOT NULL REFERENCES plaid.bank_accounts(id) ON DELETE CASCADE,
    plaid_transaction_id  TEXT        NOT NULL UNIQUE,
    amount                DECIMAL(15,2) NOT NULL,
    date                  DATE        NOT NULL,
    merchant_name         TEXT,
    category              TEXT[],
    payment_channel       TEXT,
    pending               BOOLEAN     NOT NULL DEFAULT false,
    embedding             VECTOR(1536),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_plaid_transactions_source_connection
    ON plaid.transactions(source_connection_id);

CREATE INDEX idx_plaid_transactions_account
    ON plaid.transactions(account_id);

CREATE INDEX idx_plaid_transactions_date
    ON plaid.transactions(date DESC);

-- HNSW indexes for semantic search on embeddings.
-- Partial (WHERE embedding IS NOT NULL) avoids indexing rows
-- before the embed service has run.
CREATE INDEX idx_plaid_bank_accounts_embedding
    ON plaid.bank_accounts USING hnsw(embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

CREATE INDEX idx_plaid_transactions_embedding
    ON plaid.transactions USING hnsw(embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

COMMENT ON TABLE plaid.transactions IS
  'One row per Plaid transaction fetched via /transactions/sync.
   Transactions are upserted on each sync run using
   plaid_transaction_id as the conflict key — Plaid may update
   pending transactions (merchant name resolution, final amount)
   after initial posting.

   amount follows Plaid''s sign convention: positive values are
   debits (money leaving the account); negative values are credits
   (money entering the account, e.g. a refund or direct deposit).

   embedding holds a VECTOR(1536) OpenAI text-embedding-3-small
   representation of the transaction narrative (merchant name,
   amount, date, category) for semantic search queries like
   "how much did I spend at coffee shops last month".';

COMMENT ON COLUMN plaid.transactions.id IS
  'Internal UUID for this transaction row.';

COMMENT ON COLUMN plaid.transactions.source_connection_id IS
  'FK to source_connections.id — the universal scope key.
   Denormalised here (also reachable via account_id →
   plaid.bank_accounts.source_connection_id) to allow efficient
   single-table scans scoped by connection without joins.';

COMMENT ON COLUMN plaid.transactions.account_id IS
  'FK to plaid.bank_accounts.id — the account this transaction
   belongs to. Cascades deletes: removing the account removes
   all its transactions.';

COMMENT ON COLUMN plaid.transactions.plaid_transaction_id IS
  'Plaid-assigned transaction identifier. Globally unique within
   Plaid (UNIQUE constraint enforced). Used as the upsert key
   during incremental sync runs — Plaid reuses the same
   transaction_id when updating a pending transaction.
   Example: "lPNjeW1nR6CDn5okmGQ6hEpMo4lLNoSrzqDje"';

COMMENT ON COLUMN plaid.transactions.amount IS
  'Transaction amount in the account''s native currency (USD).
   Sign convention follows Plaid:
     positive — debit (e.g. $42.50 purchase → 42.50)
     negative — credit (e.g. $20.00 refund → -20.00)
   Stored as DECIMAL(15,2) for exact monetary arithmetic.';

COMMENT ON COLUMN plaid.transactions.date IS
  'ISO date on which the transaction posted (or is expected to
   post for pending transactions). Used as the primary sort
   dimension; idx_plaid_transactions_date covers DESC order
   for the common "recent transactions" query pattern.';

COMMENT ON COLUMN plaid.transactions.merchant_name IS
  'Cleaned merchant name as enriched by Plaid (e.g. "Starbucks",
   "Amazon", "Netflix"). May be null for transactions where
   Plaid cannot identify the merchant (e.g. ACH transfers,
   ATM withdrawals). Prefer this over the raw description for
   display and categorisation.';

COMMENT ON COLUMN plaid.transactions.category IS
  'Plaid category hierarchy as a text array, most specific last.
   Example: ["Food and Drink", "Restaurants", "Coffee Shop"]
   Null if Plaid did not return category data.
   Stored as TEXT[] for easy containment queries:
     WHERE ''Coffee Shop'' = ANY(category)';

COMMENT ON COLUMN plaid.transactions.payment_channel IS
  'How the transaction was initiated. Common Plaid values:
     "online"      — e-commerce / card-not-present
     "in store"    — physical POS terminal
     "other"       — ACH, wire, check, ATM
   Null if Plaid does not report this field.';

COMMENT ON COLUMN plaid.transactions.pending IS
  'Whether this transaction is still pending authorisation.
   Plaid returns pending=true for transactions that have been
   authorised but not yet settled. When the transaction settles,
   Plaid issues an updated record (same plaid_transaction_id)
   with pending=false and potentially a different amount or
   merchant name — the upsert logic handles this automatically.';

COMMENT ON COLUMN plaid.transactions.embedding IS
  'VECTOR(1536) OpenAI text-embedding-3-small embedding of a
   short text representation of this transaction (merchant name,
   amount, date, category). Enables semantic search queries such
   as "show me grocery spending this month" or "find the Netflix
   charge".
   Populated asynchronously by the embed service after sync;
   null until the first embed pass completes.';

COMMENT ON COLUMN plaid.transactions.created_at IS
  'Timestamp when this transaction row was first inserted into
   the local database. Not updated on subsequent upserts
   (amount/merchant updates on pending→settled transitions).';


-- ------------------------------------------------------------
-- TRIGGERS
-- Keep updated_at current on plaid.connections and
-- plaid.bank_accounts using the shared update_updated_at()
-- function defined in 01_spine.sql.
-- ------------------------------------------------------------

CREATE TRIGGER plaid_connections_updated_at
    BEFORE UPDATE ON plaid.connections
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER plaid_bank_accounts_updated_at
    BEFORE UPDATE ON plaid.bank_accounts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
