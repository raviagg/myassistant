-- plaid.connections stays: it is a credentials/cursor store (OAuth access_token, sync cursor), not user data.
-- plaid.bank_accounts and plaid.transactions are retired: all future writes go through the document/fact pipeline.
DROP TABLE IF EXISTS plaid.transactions;
DROP TABLE IF EXISTS plaid.bank_accounts;
