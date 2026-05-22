package com.myassistant.api.schemas

import com.myassistant.api.models.{ForeignKeyResponse, SourceColumnResponse, SourceTableResponse}

/** Hardcoded schema definitions for native connector tables and the identity
 *  spine (Profile). These are our own migrations so no live information_schema
 *  introspection is needed or safe.
 */
object NativeSchemaRegistry:

  val profileTables: List[SourceTableResponse] = List(
    SourceTableResponse(
      tableName   = "person",
      columns     = List(
        SourceColumnResponse("id",           "UUID"),
        SourceColumnResponse("display_name", "TEXT"),
        SourceColumnResponse("full_name",    "TEXT"),
        SourceColumnResponse("created_at",   "TIMESTAMPTZ"),
      ),
      foreignKeys = Nil,
    ),
    SourceTableResponse(
      tableName   = "household",
      columns     = List(
        SourceColumnResponse("id",         "UUID"),
        SourceColumnResponse("name",       "TEXT"),
        SourceColumnResponse("created_at", "TIMESTAMPTZ"),
      ),
      foreignKeys = Nil,
    ),
    SourceTableResponse(
      tableName   = "relationship",
      columns     = List(
        SourceColumnResponse("id",            "UUID"),
        SourceColumnResponse("from_person_id","UUID"),
        SourceColumnResponse("to_person_id",  "UUID"),
        SourceColumnResponse("relation_type", "TEXT"),
      ),
      foreignKeys = List(
        ForeignKeyResponse("from_person_id", "person", "id"),
        ForeignKeyResponse("to_person_id",   "person", "id"),
      ),
    ),
    SourceTableResponse(
      tableName   = "person_household",
      columns     = List(
        SourceColumnResponse("person_id",    "UUID"),
        SourceColumnResponse("household_id", "UUID"),
        SourceColumnResponse("role",         "TEXT"),
      ),
      foreignKeys = List(
        ForeignKeyResponse("person_id",    "person",    "id"),
        ForeignKeyResponse("household_id", "household", "id"),
      ),
    ),
  )

  /** Tables defined by the Plaid connector (13_plaid_schema.sql / V20__plaid_schema.sql). */
  val plaidTables: List[SourceTableResponse] = List(
    SourceTableResponse(
      tableName   = "plaid.connections",
      columns     = List(
        SourceColumnResponse("id",                   "UUID"),
        SourceColumnResponse("source_connection_id", "UUID"),
        SourceColumnResponse("plaid_item_id",        "TEXT"),
        SourceColumnResponse("institution_name",     "TEXT"),
        SourceColumnResponse("cursor",               "TEXT"),
      ),
      foreignKeys = List(
        ForeignKeyResponse("source_connection_id", "source_connections", "id"),
      ),
    ),
    SourceTableResponse(
      tableName   = "plaid.bank_accounts",
      columns     = List(
        SourceColumnResponse("id",                   "UUID"),
        SourceColumnResponse("source_connection_id", "UUID"),
        SourceColumnResponse("connection_id",        "UUID"),
        SourceColumnResponse("plaid_account_id",     "TEXT"),
        SourceColumnResponse("name",                 "TEXT"),
        SourceColumnResponse("account_type",         "TEXT"),
        SourceColumnResponse("current_balance",      "DECIMAL(15,2)"),
      ),
      foreignKeys = List(
        ForeignKeyResponse("source_connection_id", "source_connections", "id"),
        ForeignKeyResponse("connection_id",        "plaid.connections",  "id"),
      ),
    ),
    SourceTableResponse(
      tableName   = "plaid.transactions",
      columns     = List(
        SourceColumnResponse("id",                   "UUID"),
        SourceColumnResponse("source_connection_id", "UUID"),
        SourceColumnResponse("account_id",           "UUID"),
        SourceColumnResponse("plaid_transaction_id", "TEXT"),
        SourceColumnResponse("amount",               "DECIMAL(15,2)"),
        SourceColumnResponse("date",                 "DATE"),
        SourceColumnResponse("merchant_name",        "TEXT"),
        SourceColumnResponse("category",             "TEXT[]"),
        SourceColumnResponse("payment_channel",      "TEXT"),
        SourceColumnResponse("pending",              "BOOLEAN"),
      ),
      foreignKeys = List(
        ForeignKeyResponse("source_connection_id", "source_connections", "id"),
        ForeignKeyResponse("account_id",           "plaid.bank_accounts","id"),
      ),
    ),
  )
