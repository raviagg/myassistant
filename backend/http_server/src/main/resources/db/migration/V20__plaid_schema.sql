-- V20__plaid_schema.sql
-- Native relational tables for the Plaid banking connector.
-- All Plaid-sourced data lives in the plaid.* schema for type
-- safety and efficient SQL aggregations (balances, categories).

CREATE SCHEMA IF NOT EXISTS plaid;

-- ── plaid.connections ─────────────────────────────────────────────────────────
-- One row per Plaid Item (one authenticated bank link).

CREATE TABLE plaid.connections (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_connection_id  UUID        NOT NULL REFERENCES source_connections(id) ON DELETE CASCADE,
    plaid_item_id         TEXT        NOT NULL UNIQUE,
    institution_name      TEXT        NOT NULL,
    cursor                TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_plaid_connections_source_connection
    ON plaid.connections(source_connection_id);

CREATE TRIGGER plaid_connections_updated_at
    BEFORE UPDATE ON plaid.connections
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ── plaid.bank_accounts ───────────────────────────────────────────────────────
-- One row per Plaid account within an Item.

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

CREATE INDEX idx_plaid_bank_accounts_embedding
    ON plaid.bank_accounts USING hnsw(embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

CREATE TRIGGER plaid_bank_accounts_updated_at
    BEFORE UPDATE ON plaid.bank_accounts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ── plaid.transactions ────────────────────────────────────────────────────────
-- One row per Plaid transaction; upserted on each sync run.

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

CREATE INDEX idx_plaid_transactions_embedding
    ON plaid.transactions USING hnsw(embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;
