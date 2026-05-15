-- V15__plaid_finance.sql
-- Mark plaid_poll source_type as scheduled (it was seeded before is_scheduled column existed)
UPDATE source_type SET is_scheduled = true WHERE name = 'plaid_poll';

-- plaid_connection: one fact per connected Plaid item (institution)
INSERT INTO entity_type_schema (domain_id, entity_type, schema_version, description, field_definitions)
SELECT id, 'plaid_connection', 1,
    'A Plaid-connected bank institution. One fact per connected institution per person.',
    '[
        {"name": "item_id",          "type": "text", "mandatory": true,  "description": "Plaid item_id — stable identifier for the connected institution"},
        {"name": "institution_id",   "type": "text", "mandatory": false, "description": "Plaid institution_id. Example: ins_3"},
        {"name": "institution_name", "type": "text", "mandatory": true,  "description": "Human-readable institution name. Example: Chase"},
        {"name": "access_token",     "type": "text", "mandatory": true,  "description": "Plaid access_token — treat as a secret"},
        {"name": "sync_cursor",      "type": "text", "mandatory": false, "description": "Cursor from /transactions/sync for incremental fetching. Empty string on initial connection."},
        {"name": "last_synced_at",   "type": "text", "mandatory": false, "description": "ISO-8601 timestamp of last successful sync. Example: 2026-05-15T02:00:00Z"}
    ]'::jsonb
FROM domain WHERE name = 'finance';

-- transaction: one fact per Plaid transaction
INSERT INTO entity_type_schema (domain_id, entity_type, schema_version, description, field_definitions)
SELECT id, 'transaction', 1,
    'A financial transaction from Plaid /transactions/sync. Created on added, updated on modified, deleted on removed.',
    '[
        {"name": "transaction_id",    "type": "text",    "mandatory": true,  "description": "Plaid transaction_id — stable identifier"},
        {"name": "account_id",        "type": "text",    "mandatory": true,  "description": "Plaid account_id this transaction belongs to"},
        {"name": "amount",            "type": "number",  "mandatory": true,  "description": "Amount in account currency. Positive = debit (money out), negative = credit (money in)."},
        {"name": "iso_currency_code", "type": "text",    "mandatory": false, "description": "ISO 4217 currency code. Example: USD"},
        {"name": "merchant_name",     "type": "text",    "mandatory": false, "description": "Cleaned merchant name if available. Example: Starbucks"},
        {"name": "name",              "type": "text",    "mandatory": true,  "description": "Transaction name from bank"},
        {"name": "category",          "type": "text",    "mandatory": false, "description": "Plaid personal_finance_category joined string. Example: Food and Drink > Coffee Shop"},
        {"name": "date",              "type": "date",    "mandatory": true,  "description": "Transaction date YYYY-MM-DD"},
        {"name": "pending",           "type": "boolean", "mandatory": false, "description": "True if not yet settled"}
    ]'::jsonb
FROM domain WHERE name = 'finance';

-- bank_account: one fact per Plaid account, updated on each sync with latest balance
INSERT INTO entity_type_schema (domain_id, entity_type, schema_version, description, field_definitions)
SELECT id, 'bank_account', 1,
    'A bank account from Plaid /accounts/get. Balance fields updated on every sync run.',
    '[
        {"name": "account_id",        "type": "text",   "mandatory": true,  "description": "Plaid account_id — stable identifier"},
        {"name": "item_id",           "type": "text",   "mandatory": true,  "description": "Plaid item_id of the parent plaid_connection fact"},
        {"name": "name",              "type": "text",   "mandatory": true,  "description": "Account name from bank. Example: Plaid Checking"},
        {"name": "official_name",     "type": "text",   "mandatory": false, "description": "Official account name if provided by bank"},
        {"name": "type",              "type": "text",   "mandatory": true,  "description": "Account type. Example: depository, credit, loan"},
        {"name": "subtype",           "type": "text",   "mandatory": false, "description": "Account subtype. Example: checking, savings, credit card"},
        {"name": "mask",              "type": "text",   "mandatory": false, "description": "Last 4 digits of account number. Example: 0000"},
        {"name": "current_balance",   "type": "number", "mandatory": false, "description": "Current balance in account currency"},
        {"name": "available_balance", "type": "number", "mandatory": false, "description": "Available balance (current minus pending charges)"},
        {"name": "iso_currency_code", "type": "text",   "mandatory": false, "description": "ISO 4217 currency code. Example: USD"},
        {"name": "institution_name",  "type": "text",   "mandatory": false, "description": "Institution name for display. Example: Chase"}
    ]'::jsonb
FROM domain WHERE name = 'finance';
