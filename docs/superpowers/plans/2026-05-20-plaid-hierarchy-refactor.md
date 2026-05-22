# Plaid Hierarchy Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reshape Plaid from one `source_connections` row per bank item to a two-level model — one `source_connections` row per person/type (holds Plaid API credentials), with `plaid.connections` rows as children (one per linked bank holding `access_token`).

**Architecture:** V21 migration adds uniqueness constraints and `access_token` column. `PlaidClient` gains per-call `clientId`/`secret` params (removed from `PlaidConfig`). New `PlaidItemsRoutes` provides source-connection-scoped link-token, exchange, list, and disconnect endpoints replacing the old flat `PlaidRoutes`. The Python poller iterates over `plaid.connections` items per source_connection using integration-level credentials. `SourceConnectionForm` create flow accepts Plaid API creds; edit screen manages linked banks via Plaid Link.

**Tech Stack:** Scala 3 / ZIO 2 / zio-http, Python 3 / httpx, React 18 / TypeScript / react-plaid-link, Flyway, PostgreSQL.

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `backend/http_server/src/main/resources/db/migration/V21__plaid_source_conn_constraints.sql` | Create | Unique indexes on `source_connections` + `access_token` on `plaid.connections` |
| `backend/http_server/src/main/scala/com/myassistant/config/PlaidConfig.scala` | Modify | Remove `clientId`/`secret` (now per-person in DB) |
| `backend/http_server/src/main/resources/application.conf` | Modify | Remove plaid `clientId`/`secret` env bindings |
| `backend/http_server/src/main/scala/com/myassistant/api/plaid/PlaidClient.scala` | Modify | Add `clientId`/`secret` per-call params; remove global auth from constructor |
| `backend/http_server/src/main/scala/com/myassistant/db/repositories/PlaidSyncRepository.scala` | Modify | `accessToken` on row/upsert; add `listBySourceConnectionId`; add `deleteConnection` |
| `backend/http_server/src/main/scala/com/myassistant/api/models/PlaidSyncModels.scala` | Modify | Add `accessToken` to upsert request; add `PlaidItemResponse` |
| `backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidItemsRoutes.scala` | Create | `link-token`, `exchange`, `GET items`, `DELETE item` under `/source-connections/{id}/plaid/` |
| `backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidSyncRoutes.scala` | Modify | Pass `accessToken` from request to `upsertConnection` |
| `backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidRoutes.scala` | Delete | Old flat link-token/exchange routes superseded by PlaidItemsRoutes |
| `backend/http_server/src/main/scala/com/myassistant/api/Router.scala` | Modify | Replace `PlaidRoutes` with `PlaidItemsRoutes`; add `SecretsConfig` to env |
| `backend/scheduler/handlers/plaid_poll.py` | Modify | Fetch integration creds from `/secrets`; loop over items per source_connection |
| `frontend/src/api.ts` | Modify | Add 4 source-connection-scoped Plaid functions; remove old flat ones |
| `frontend/src/components/SourceConnectionForm.tsx` | Modify | Plaid create: `client_id`/`secret` fields; Plaid edit: linked banks panel with Add/Disconnect |
| `frontend/src/components/FinanceTab.tsx` | Modify | Remove connect/disconnect flow (now in SourceConnectionForm); read-only bank display |

---

## Task 1: DB Migration V21

**Files:**
- Create: `backend/http_server/src/main/resources/db/migration/V21__plaid_source_conn_constraints.sql`

- [ ] **Step 1: Create the migration**

```sql
-- V21__plaid_source_conn_constraints.sql
-- 1. Unique partial indexes on source_connections.
--    One connection per (source_type, owner) — enforced now, droppable later.
--    Names must be unique per owner regardless of source_type.

CREATE UNIQUE INDEX uq_source_conn_type_person
    ON source_connections (source_type, person_id)
    WHERE person_id IS NOT NULL;

CREATE UNIQUE INDEX uq_source_conn_type_household
    ON source_connections (source_type, household_id)
    WHERE household_id IS NOT NULL;

CREATE UNIQUE INDEX uq_source_conn_name_person
    ON source_connections (connection_name, person_id)
    WHERE person_id IS NOT NULL;

CREATE UNIQUE INDEX uq_source_conn_name_household
    ON source_connections (connection_name, household_id)
    WHERE household_id IS NOT NULL;

-- 2. Per-item access_token on plaid.connections.
--    Each Plaid item has its own access_token (one per linked bank).
--    Stored as an AES-256-GCM encrypted blob (same scheme as source_connections.secrets).
--    COALESCE in upsert preserves existing value when caller passes NULL.

ALTER TABLE plaid.connections
    ADD COLUMN access_token TEXT;
```

- [ ] **Step 2: Verify migration applies cleanly**

```bash
cd backend/http_server
sbt "flywayMigrate"
```
Expected: `Successfully applied 1 migration to schema "public"`

- [ ] **Step 3: Commit**

```bash
git add backend/http_server/src/main/resources/db/migration/V21__plaid_source_conn_constraints.sql
git commit -m "feat(db): V21 — unique source_connection indexes + plaid.connections.access_token"
```

---

## Task 2: Slim PlaidConfig — Remove Global Credentials

**Files:**
- Modify: `backend/http_server/src/main/scala/com/myassistant/config/PlaidConfig.scala`
- Modify: `backend/http_server/src/main/resources/application.conf`

- [ ] **Step 1: Remove `clientId` and `secret` from PlaidConfig**

Replace the entire file `backend/http_server/src/main/scala/com/myassistant/config/PlaidConfig.scala`:

```scala
package com.myassistant.config

final case class PlaidConfig(
    env:         String,
    redirectUri: String,
)
```

- [ ] **Step 2: Remove clientId/secret from application.conf**

In `backend/http_server/src/main/resources/application.conf`, replace:

```hocon
  plaid {
    clientId = ""
    clientId = ${?PLAID_CLIENT_ID}

    secret = ""
    secret = ${?PLAID_SECRET}

    # "sandbox" | "development" | "production"
    env = "sandbox"
    env = ${?PLAID_ENV}

    # OAuth redirect URI — must be registered in Plaid dashboard.
    # Leave empty to disable OAuth support (non-OAuth banks still work).
    # Local dev: http://localhost:5173  |  Production: https://your-domain.com
    redirectUri = ""
    redirectUri = ${?PLAID_REDIRECT_URI}
  }
```

With:

```hocon
  plaid {
    # "sandbox" | "development" | "production"
    env = "sandbox"
    env = ${?PLAID_ENV}

    # OAuth redirect URI — must be registered in Plaid dashboard.
    # Leave empty to disable OAuth support (non-OAuth banks still work).
    # Local dev: http://localhost:5173  |  Production: https://your-domain.com
    redirectUri = ""
    redirectUri = ${?PLAID_REDIRECT_URI}
  }
```

- [ ] **Step 3: Verify AppConfig still compiles**

```bash
cd backend/http_server
sbt "compile"
```

Expected: compile errors only in `PlaidClient.scala` referencing `cfg.clientId`/`cfg.secret` — those are fixed in the next task.

- [ ] **Step 4: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/config/PlaidConfig.scala \
        backend/http_server/src/main/resources/application.conf
git commit -m "feat(config): remove global Plaid clientId/secret — now per-person in DB"
```

---

## Task 3: PlaidClient — Per-Call Credentials

**Files:**
- Modify: `backend/http_server/src/main/scala/com/myassistant/api/plaid/PlaidClient.scala`

- [ ] **Step 1: Update the trait and Live implementation**

Replace entire file:

```scala
package com.myassistant.api.plaid

import com.myassistant.config.PlaidConfig
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*

import java.net.URI
import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

trait PlaidClient:
  def createLinkToken(clientUserId: String, clientId: String, secret: String): Task[String]
  def exchangePublicToken(publicToken: String, clientId: String, secret: String): Task[(String, String)]
  def getAccounts(accessToken: String, clientId: String, secret: String): Task[PlaidAccountsResp]
  def getInstitutionName(institutionId: String, clientId: String, secret: String): Task[String]
  def syncTransactions(accessToken: String, cursor: Option[String], clientId: String, secret: String): Task[PlaidSyncResp]

object PlaidClient:

  final class Live(cfg: PlaidConfig) extends PlaidClient:

    private val http = JHttpClient.newHttpClient()

    private val baseUrl: String = cfg.env match
      case "production"  => "https://production.plaid.com"
      case "development" => "https://development.plaid.com"
      case _             => "https://sandbox.plaid.com"

    private def withAuth(body: Json, clientId: String, secret: String): Json =
      Json.fromJsonObject(
        body.asObject.getOrElse(io.circe.JsonObject.empty)
          .add("client_id", Json.fromString(clientId))
          .add("secret",    Json.fromString(secret))
      )

    private def post[A: io.circe.Decoder](path: String, body: Json): Task[A] =
      ZIO.attemptBlocking {
        val req = HttpRequest.newBuilder()
          .uri(URI.create(s"$baseUrl$path"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body.noSpaces, StandardCharsets.UTF_8))
          .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if resp.statusCode() >= 400 then
          throw RuntimeException(s"Plaid API error ${resp.statusCode()}: ${resp.body().take(500)}")
        decode[A](resp.body()) match
          case Right(v) => v
          case Left(e)  => throw RuntimeException(s"Plaid decode error for $path: $e")
      }

    def createLinkToken(clientUserId: String, clientId: String, secret: String): Task[String] =
      val base = Json.obj(
        "user"          -> Json.obj("client_user_id" -> Json.fromString(clientUserId)),
        "client_name"   -> Json.fromString("myassistant"),
        "products"      -> Json.arr(Json.fromString("transactions")),
        "country_codes" -> Json.arr(Json.fromString("US")),
        "language"      -> Json.fromString("en"),
      )
      val body = if cfg.redirectUri.nonEmpty then
        Json.fromJsonObject(
          base.asObject.getOrElse(io.circe.JsonObject.empty)
            .add("redirect_uri", Json.fromString(cfg.redirectUri))
        )
      else base
      post[PlaidLinkTokenResp]("/link/token/create", withAuth(body, clientId, secret))
        .map(_.link_token)

    def exchangePublicToken(publicToken: String, clientId: String, secret: String): Task[(String, String)] =
      post[PlaidExchangeResp]("/item/public_token/exchange",
        withAuth(Json.obj("public_token" -> Json.fromString(publicToken)), clientId, secret)
      ).map(r => (r.access_token, r.item_id))

    def getAccounts(accessToken: String, clientId: String, secret: String): Task[PlaidAccountsResp] =
      post[PlaidAccountsResp]("/accounts/get",
        withAuth(Json.obj("access_token" -> Json.fromString(accessToken)), clientId, secret)
      )

    def getInstitutionName(institutionId: String, clientId: String, secret: String): Task[String] =
      post[PlaidInstitutionResp]("/institutions/get_by_id",
        withAuth(Json.obj(
          "institution_id" -> Json.fromString(institutionId),
          "country_codes"  -> Json.arr(Json.fromString("US")),
        ), clientId, secret)
      ).map(_.institution.name)

    def syncTransactions(accessToken: String, cursor: Option[String], clientId: String, secret: String): Task[PlaidSyncResp] =
      val base = Json.obj("access_token" -> Json.fromString(accessToken))
      val withCursor = cursor.foldLeft(base) { (b, c) =>
        Json.fromJsonObject(b.asObject.getOrElse(io.circe.JsonObject.empty).add("cursor", Json.fromString(c)))
      }
      post[PlaidSyncResp]("/transactions/sync", withAuth(withCursor, clientId, secret))

  val live: ZLayer[PlaidConfig, Nothing, PlaidClient] =
    ZLayer.fromFunction(new Live(_))
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend/http_server
sbt "compile"
```

Expected: errors only in callers of old method signatures (`PlaidRoutes.scala`) — those get replaced in Task 6.

- [ ] **Step 3: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/api/plaid/PlaidClient.scala
git commit -m "feat(plaid): per-call clientId/secret on PlaidClient — remove global auth config"
```

---

## Task 4: PlaidSyncRepository — accessToken + list + delete

**Files:**
- Modify: `backend/http_server/src/main/scala/com/myassistant/db/repositories/PlaidSyncRepository.scala`

- [ ] **Step 1: Update `PlaidConnectionRow`, `upsertConnection`, add `listBySourceConnectionId`, add `deleteConnection`**

Replace the entire file:

```scala
package com.myassistant.db.repositories

import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.time.{Instant, LocalDate}
import java.util.UUID

final case class PlaidConnectionRow(
    id:                 UUID,
    sourceConnectionId: UUID,
    plaidItemId:        String,
    institutionName:    String,
    cursor:             Option[String],
    accessToken:        Option[String],  // encrypted ciphertext; None if not yet set
    createdAt:          Instant,
    updatedAt:          Instant,
)

final case class PlaidBankAccountRow(
    id:                 UUID,
    sourceConnectionId: UUID,
    connectionId:       UUID,
    plaidAccountId:     String,
    name:               String,
    accountType:        String,
    currentBalance:     Option[BigDecimal],
    createdAt:          Instant,
    updatedAt:          Instant,
)

final case class PlaidTransactionInput(
    plaidTransactionId: String,
    amount:             BigDecimal,
    date:               LocalDate,
    merchantName:       Option[String],
    category:           List[String],
    paymentChannel:     Option[String],
    pending:            Boolean,
)

trait PlaidSyncRepository:

  def upsertConnection(
      sourceConnectionId: UUID,
      plaidItemId:        String,
      institutionName:    String,
      cursor:             Option[String],
      accessToken:        Option[String],  // encrypted; None = preserve existing via COALESCE
  ): ZIO[ZConnectionPool, AppError, PlaidConnectionRow]

  def listBySourceConnectionId(
      sourceConnectionId: UUID,
  ): ZIO[ZConnectionPool, AppError, List[PlaidConnectionRow]]

  def deleteConnection(
      sourceConnectionId: UUID,
      itemId:             UUID,
  ): ZIO[ZConnectionPool, AppError, Boolean]

  def upsertBankAccount(
      sourceConnectionId: UUID,
      connectionId:       UUID,
      plaidAccountId:     String,
      name:               String,
      accountType:        String,
      currentBalance:     Option[BigDecimal],
  ): ZIO[ZConnectionPool, AppError, PlaidBankAccountRow]

  def upsertTransactionsBatch(
      sourceConnectionId: UUID,
      accountId:          UUID,
      added:              List[PlaidTransactionInput],
      modified:           List[PlaidTransactionInput],
      removed:            List[String],
  ): ZIO[ZConnectionPool, AppError, (Int, Int, Int)]

object PlaidSyncRepository:

  // id, source_connection_id, plaid_item_id, institution_name,
  // cursor, access_token, created_at, updated_at
  private type ConnRow =
    (String, String, String, String, Option[String], Option[String],
     java.sql.Timestamp, java.sql.Timestamp)

  private val connCols = SqlFragment(
    """id::text, source_connection_id::text, plaid_item_id, institution_name,
       cursor, access_token, created_at, updated_at"""
  )

  private def rowToConn(row: ConnRow): PlaidConnectionRow =
    val (id, scid, itemId, instName, cursor, accessToken, createdAt, updatedAt) = row
    PlaidConnectionRow(
      id                 = UUID.fromString(id),
      sourceConnectionId = UUID.fromString(scid),
      plaidItemId        = itemId,
      institutionName    = instName,
      cursor             = cursor,
      accessToken        = accessToken,
      createdAt          = createdAt.toInstant,
      updatedAt          = updatedAt.toInstant,
    )

  private type AcctRow =
    (String, String, String, String, String, String,
     Option[BigDecimal], java.sql.Timestamp, java.sql.Timestamp)

  private val acctCols = SqlFragment(
    """id::text, source_connection_id::text, connection_id::text,
       plaid_account_id, name, account_type, current_balance,
       created_at, updated_at"""
  )

  private def rowToAcct(row: AcctRow): PlaidBankAccountRow =
    val (id, scid, cid, plaidAcctId, name, accountType, balance, createdAt, updatedAt) = row
    PlaidBankAccountRow(
      id                 = UUID.fromString(id),
      sourceConnectionId = UUID.fromString(scid),
      connectionId       = UUID.fromString(cid),
      plaidAccountId     = plaidAcctId,
      name               = name,
      accountType        = accountType,
      currentBalance     = balance,
      createdAt          = createdAt.toInstant,
      updatedAt          = updatedAt.toInstant,
    )

  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  final class Live extends PlaidSyncRepository:

    def upsertConnection(
        sourceConnectionId: UUID,
        plaidItemId:        String,
        institutionName:    String,
        cursor:             Option[String],
        accessToken:        Option[String],
    ): ZIO[ZConnectionPool, AppError, PlaidConnectionRow] =
      val q =
        sql"INSERT INTO plaid.connections(source_connection_id, plaid_item_id, institution_name, cursor, access_token) " ++
        sql"VALUES (${sourceConnectionId.toString}::uuid, $plaidItemId, $institutionName, $cursor, $accessToken) " ++
        sql"ON CONFLICT (plaid_item_id) DO UPDATE SET " ++
        sql"  institution_name = EXCLUDED.institution_name, " ++
        sql"  cursor           = COALESCE(EXCLUDED.cursor, plaid.connections.cursor), " ++
        sql"  access_token     = COALESCE(EXCLUDED.access_token, plaid.connections.access_token), " ++
        sql"  updated_at       = now() " ++
        sql"RETURNING " ++ connCols
      transaction(q.query[ConnRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("UPSERT plaid.connections returned no row"))))
        .map(rowToConn)

    def listBySourceConnectionId(
        sourceConnectionId: UUID,
    ): ZIO[ZConnectionPool, AppError, List[PlaidConnectionRow]] =
      val q =
        sql"SELECT " ++ connCols ++
        sql" FROM plaid.connections" ++
        sql" WHERE source_connection_id = ${sourceConnectionId.toString}::uuid" ++
        sql" ORDER BY created_at ASC"
      transaction(q.query[ConnRow].selectAll)
        .mapError(mapSqlError)
        .map(_.map(rowToConn))

    def deleteConnection(
        sourceConnectionId: UUID,
        itemId:             UUID,
    ): ZIO[ZConnectionPool, AppError, Boolean] =
      val q =
        sql"DELETE FROM plaid.connections" ++
        sql" WHERE id = ${itemId.toString}::uuid" ++
        sql"   AND source_connection_id = ${sourceConnectionId.toString}::uuid"
      transaction(q.delete)
        .mapError(mapSqlError)
        .map(_ > 0L)

    def upsertBankAccount(
        sourceConnectionId: UUID,
        connectionId:       UUID,
        plaidAccountId:     String,
        name:               String,
        accountType:        String,
        currentBalance:     Option[BigDecimal],
    ): ZIO[ZConnectionPool, AppError, PlaidBankAccountRow] =
      val q =
        sql"INSERT INTO plaid.bank_accounts(source_connection_id, connection_id, plaid_account_id, name, account_type, current_balance) " ++
        sql"VALUES (${sourceConnectionId.toString}::uuid, ${connectionId.toString}::uuid, $plaidAccountId, $name, $accountType, $currentBalance) " ++
        sql"ON CONFLICT (plaid_account_id) DO UPDATE SET " ++
        sql"  name            = EXCLUDED.name, " ++
        sql"  account_type    = EXCLUDED.account_type, " ++
        sql"  current_balance = EXCLUDED.current_balance, " ++
        sql"  updated_at      = now() " ++
        sql"RETURNING " ++ acctCols
      transaction(q.query[AcctRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("UPSERT plaid.bank_accounts returned no row"))))
        .map(rowToAcct)

    private def toJsonArray(values: List[String]): String =
      values
        .map(v => "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .mkString("[", ",", "]")

    private def upsertOneTxnOp(
        sourceConnectionId: UUID,
        accountId:          UUID,
        txn:                PlaidTransactionInput,
    ): ZIO[ZConnection, Throwable, Unit] =
      val dateSqlStr = txn.date.toString
      val categoryFrag =
        if txn.category.isEmpty then sql"NULL::text[]"
        else
          val json = toJsonArray(txn.category)
          sql"ARRAY(SELECT jsonb_array_elements_text(${json}::jsonb))"
      val q =
        sql"INSERT INTO plaid.transactions(source_connection_id, account_id, plaid_transaction_id, amount, date, merchant_name, category, payment_channel, pending) " ++
        sql"VALUES (${sourceConnectionId.toString}::uuid, ${accountId.toString}::uuid, ${txn.plaidTransactionId}, ${txn.amount}, ${dateSqlStr}::date, ${txn.merchantName}, " ++
        categoryFrag ++
        sql", ${txn.paymentChannel}, ${txn.pending}) " ++
        sql"ON CONFLICT (plaid_transaction_id) DO UPDATE SET " ++
        sql"  amount = EXCLUDED.amount, " ++
        sql"  date = EXCLUDED.date, " ++
        sql"  merchant_name = EXCLUDED.merchant_name, " ++
        sql"  category = EXCLUDED.category, " ++
        sql"  payment_channel = EXCLUDED.payment_channel, " ++
        sql"  pending = EXCLUDED.pending"
      q.update.unit

    def upsertTransactionsBatch(
        sourceConnectionId: UUID,
        accountId:          UUID,
        added:              List[PlaidTransactionInput],
        modified:           List[PlaidTransactionInput],
        removed:            List[String],
    ): ZIO[ZConnectionPool, AppError, (Int, Int, Int)] =
      transaction {
        for
          _ <- ZIO.foreachDiscard(added)(upsertOneTxnOp(sourceConnectionId, accountId, _))
          _ <- ZIO.foreachDiscard(modified)(upsertOneTxnOp(sourceConnectionId, accountId, _))
          removedCount <-
            if removed.isEmpty then ZIO.succeed(0L)
            else
              ZIO.foldLeft(removed)(0L) { (acc, txnId) =>
                val q =
                  sql"DELETE FROM plaid.transactions " ++
                  sql" WHERE plaid_transaction_id = $txnId " ++
                  sql"   AND source_connection_id = ${sourceConnectionId.toString}::uuid"
                q.delete.map(acc + _)
              }
        yield (added.size, modified.size, removedCount.toInt)
      }.mapError(mapSqlError)

  val live: ZLayer[Any, Nothing, PlaidSyncRepository] =
    ZLayer.succeed(new Live)
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend/http_server
sbt "compile"
```

Expected: errors in `PlaidSyncRoutes.scala` (missing `accessToken` arg) and `PlaidRoutes.scala` (wrong `upsertConnection` call) — both fixed in later tasks.

- [ ] **Step 3: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/db/repositories/PlaidSyncRepository.scala
git commit -m "feat(db): PlaidSyncRepository — accessToken on rows, listBySourceConnectionId, deleteConnection"
```

---

## Task 5: PlaidSyncModels — Add accessToken + PlaidItemResponse

**Files:**
- Modify: `backend/http_server/src/main/scala/com/myassistant/api/models/PlaidSyncModels.scala`

- [ ] **Step 1: Add `accessToken` to upsert request; add `PlaidItemResponse`**

Read the full file first, then replace just these two sections.

In `UpsertPlaidConnectionRequest` add `accessToken: Option[String]`:

```scala
final case class UpsertPlaidConnectionRequest(
    sourceConnectionId: UUID,
    plaidItemId:        String,
    institutionName:    String,
    cursor:             Option[String],
    accessToken:        Option[String],
) derives Codec.AsObject
```

Replace `PlaidConnectionResponse` and its companion with:

```scala
/** Response for the internal `POST /api/v1/plaid/connections/upsert` endpoint.
 *  Does NOT include accessToken (write-only path from the scheduler).
 */
final case class PlaidConnectionResponse(
    id:                 UUID,
    sourceConnectionId: UUID,
    plaidItemId:        String,
    institutionName:    String,
    cursor:             Option[String],
    createdAt:          Instant,
    updatedAt:          Instant,
) derives Codec.AsObject

object PlaidConnectionResponse:
  def fromDomain(c: PlaidConnectionRow): PlaidConnectionResponse =
    PlaidConnectionResponse(
      id                 = c.id,
      sourceConnectionId = c.sourceConnectionId,
      plaidItemId        = c.plaidItemId,
      institutionName    = c.institutionName,
      cursor             = c.cursor,
      createdAt          = c.createdAt,
      updatedAt          = c.updatedAt,
    )

/** Response for `GET /api/v1/source-connections/{id}/plaid/items`.
 *  Includes decrypted accessToken for the Python scheduler worker.
 */
final case class PlaidItemResponse(
    id:                 UUID,
    sourceConnectionId: UUID,
    plaidItemId:        String,
    institutionName:    String,
    cursor:             Option[String],
    accessToken:        Option[String],
    createdAt:          Instant,
    updatedAt:          Instant,
) derives Codec.AsObject

object PlaidItemResponse:
  def fromDomain(c: PlaidConnectionRow, decryptedToken: Option[String]): PlaidItemResponse =
    PlaidItemResponse(
      id                 = c.id,
      sourceConnectionId = c.sourceConnectionId,
      plaidItemId        = c.plaidItemId,
      institutionName    = c.institutionName,
      cursor             = c.cursor,
      accessToken        = decryptedToken,
      createdAt          = c.createdAt,
      updatedAt          = c.updatedAt,
    )
```

- [ ] **Step 2: Compile**

```bash
cd backend/http_server && sbt "compile"
```

Expected: errors only in `PlaidSyncRoutes.scala` (missing `accessToken` arg to `upsertConnection`) — fixed in Task 7.

- [ ] **Step 3: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/api/models/PlaidSyncModels.scala
git commit -m "feat(models): PlaidSyncModels — accessToken on upsert request; PlaidItemResponse for item list"
```

---

## Task 6: PlaidItemsRoutes — New Source-Connection-Scoped Endpoints

**Files:**
- Create: `backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidItemsRoutes.scala`

These four routes replace the old flat `PlaidRoutes`. They all live under `/api/v1/source-connections/{connId}/plaid/`.

- [ ] **Step 1: Create the file**

```scala
package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{PlaidItemResponse, PlaidConnectionResponse}
import com.myassistant.api.plaid.*
import com.myassistant.config.SecretsConfig
import com.myassistant.db.repositories.PlaidSyncRepository
import com.myassistant.errors.AppError
import com.myassistant.services.{SecretsService, SourceConnectionService}
import io.circe.{Json, parser}
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID

/** Source-connection-scoped Plaid endpoints.
 *
 *  All routes are keyed on `connId` — the `source_connections.id` of the
 *  Plaid integration row (one per person). The Plaid API credentials
 *  (client_id + secret) are decrypted from that row's `secrets` blob at
 *  request time.
 *
 *  POST link-token    — create a Plaid Link token using this connection's creds
 *  POST exchange      — exchange a public token, store access_token on plaid.connections
 *  GET  items         — list linked banks with decrypted access_tokens (for scheduler)
 *  DELETE items/{id}  — disconnect one bank (cascades to accounts + transactions)
 */
object PlaidItemsRoutes:

  type Env = SourceConnectionService & PlaidClient & PlaidSyncRepository & SecretsConfig & ZConnectionPool

  val routes: Routes[Env, Nothing] =
    Routes(

      // ── POST /api/v1/source-connections/{connId}/plaid/link-token ─────────
      Method.POST / "api" / "v1" / "source-connections" / string("connId") / "plaid" / "link-token" ->
        handler { (connId: String, req: Request) =>
          (for
            scId    <- parseUUID(connId)
            creds   <- resolveCreds(scId)
            token   <- ZIO.serviceWithZIO[PlaidClient](
                         _.createLinkToken(connId, creds._1, creds._2))
                         .mapError(AppError.InternalError(_))
          yield Response.json(Json.obj("linkToken" -> Json.fromString(token)).noSpaces))
            .foldZIO(
              err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              ZIO.succeed(_),
            )
        },

      // ── POST /api/v1/source-connections/{connId}/plaid/exchange ──────────
      Method.POST / "api" / "v1" / "source-connections" / string("connId") / "plaid" / "exchange" ->
        handler { (connId: String, req: Request) =>
          (for
            scId       <- parseUUID(connId)
            bodyStr    <- req.body.asString.orDie
            bodyJson   <- ZIO.fromEither(parser.parse(bodyStr))
                            .mapError(e => AppError.ValidationError(e.message))
            publicToken <- ZIO.fromOption(bodyJson.hcursor.get[String]("publicToken").toOption)
                             .mapError(_ => AppError.ValidationError("publicToken is required"))
            creds       <- resolveCreds(scId)
            (clientId, secret) = creds

            (accessToken, itemId) <- ZIO.serviceWithZIO[PlaidClient](
                                       _.exchangePublicToken(publicToken, clientId, secret))
                                       .mapError(AppError.InternalError(_))

            accountsResp <- ZIO.serviceWithZIO[PlaidClient](
                              _.getAccounts(accessToken, clientId, secret))
                              .mapError(AppError.InternalError(_))

            institutionName <- accountsResp.item.institution_id match
              case Some(instId) =>
                ZIO.serviceWithZIO[PlaidClient](_.getInstitutionName(instId, clientId, secret))
                  .mapError(AppError.InternalError(_))
                  .orElse(ZIO.succeed("Unknown Institution"))
              case None => ZIO.succeed("Unknown Institution")

            encryptedToken <- ZIO.serviceWith[SecretsConfig] { cfg =>
                                SecretsService.encrypt(accessToken, cfg)
                              }.flatMap(ZIO.fromEither(_))
                               .mapError(AppError.InternalError(_))

            plaidConn <- ZIO.serviceWithZIO[PlaidSyncRepository](_.upsertConnection(
                           sourceConnectionId = scId,
                           plaidItemId        = itemId,
                           institutionName    = institutionName,
                           cursor             = None,
                           accessToken        = Some(encryptedToken),
                         ))

            _ <- ZIO.foreachDiscard(accountsResp.accounts) { account =>
                   val balance = account.balances.current.map(BigDecimal.apply)
                   ZIO.serviceWithZIO[PlaidSyncRepository](_.upsertBankAccount(
                     sourceConnectionId = scId,
                     connectionId       = plaidConn.id,
                     plaidAccountId     = account.account_id,
                     name               = account.name,
                     accountType        = account.`type`,
                     currentBalance     = balance,
                   ))
                 }

          yield Response.json(
            Json.obj(
              "plaidItemId"     -> Json.fromString(itemId),
              "institutionName" -> Json.fromString(institutionName),
            ).noSpaces
          ).status(Status.Created))
            .foldZIO(
              err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              ZIO.succeed(_),
            )
        },

      // ── GET /api/v1/source-connections/{connId}/plaid/items ──────────────
      Method.GET / "api" / "v1" / "source-connections" / string("connId") / "plaid" / "items" ->
        handler { (connId: String, _: Request) =>
          (for
            scId  <- parseUUID(connId)
            rows  <- ZIO.serviceWithZIO[PlaidSyncRepository](_.listBySourceConnectionId(scId))
            items <- ZIO.foreach(rows) { row =>
                       row.accessToken match
                         case None => ZIO.succeed(PlaidItemResponse.fromDomain(row, None))
                         case Some(ciphertext) =>
                           ZIO.serviceWith[SecretsConfig] { cfg =>
                             SecretsService.decrypt(ciphertext, cfg)
                           }.flatMap(ZIO.fromEither(_))
                            .mapError(AppError.InternalError(_))
                            .map(pt => PlaidItemResponse.fromDomain(row, Some(pt)))
                     }
          yield Response.json(items.asJson.noSpaces))
            .foldZIO(
              err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              ZIO.succeed(_),
            )
        },

      // ── DELETE /api/v1/source-connections/{connId}/plaid/items/{itemId} ──
      Method.DELETE / "api" / "v1" / "source-connections" / string("connId") / "plaid" / "items" / string("itemId") ->
        handler { (connId: String, itemId: String, _: Request) =>
          (for
            scId   <- parseUUID(connId)
            iId    <- parseUUID(itemId)
            found  <- ZIO.serviceWithZIO[PlaidSyncRepository](_.deleteConnection(scId, iId))
            resp    = if found then Response.status(Status.NoContent)
                      else ErrorMiddleware.appErrorToResponse(
                             AppError.NotFound("plaid_connection", itemId))
          yield resp)
            .foldZIO(
              err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              ZIO.succeed(_),
            )
        },
    )

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def parseUUID(s: String): IO[AppError, UUID] =
    ZIO.attempt(UUID.fromString(s))
      .mapError(_ => AppError.ValidationError(s"Invalid UUID: $s"))

  /** Decrypt source_connection.secrets → (clientId, secret). */
  private def resolveCreds(connId: UUID): ZIO[SourceConnectionService & SecretsConfig, AppError, (String, String)] =
    for
      rawOpt <- ZIO.serviceWithZIO[SourceConnectionService](_.getSecrets(connId))
      raw    <- ZIO.fromOption(rawOpt).mapError(_ =>
                  AppError.ValidationError(s"No Plaid credentials stored for connection $connId"))
      json   <- ZIO.fromEither(parser.parse(raw))
                  .mapError(e => AppError.InternalError(new RuntimeException(s"Invalid secrets JSON: $e")))
      clientId <- ZIO.fromOption(json.hcursor.get[String]("client_id").toOption)
                    .mapError(_ => AppError.ValidationError("Missing client_id in connection secrets"))
      secret   <- ZIO.fromOption(json.hcursor.get[String]("secret").toOption)
                    .mapError(_ => AppError.ValidationError("Missing secret in connection secrets"))
    yield (clientId, secret)
```

- [ ] **Step 2: Compile**

```bash
cd backend/http_server && sbt "compile"
```

Expected: compiles cleanly (PlaidRoutes still exists so Router is still valid; we clean it up in Task 8).

- [ ] **Step 3: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidItemsRoutes.scala
git commit -m "feat(routes): PlaidItemsRoutes — source-connection-scoped link-token/exchange/list/delete"
```

---

## Task 7: PlaidSyncRoutes — Pass accessToken to upsertConnection

**Files:**
- Modify: `backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidSyncRoutes.scala`

- [ ] **Step 1: Update the upsert handler to forward `accessToken`**

In `PlaidSyncRoutes.scala`, update the `POST /api/v1/plaid/connections/upsert` handler's `upsertConnection` call from:

```scala
ZIO.serviceWithZIO[PlaidSyncRepository](_.upsertConnection(
  r.sourceConnectionId, r.plaidItemId, r.institutionName, r.cursor,
))
```

To:

```scala
ZIO.serviceWithZIO[PlaidSyncRepository](_.upsertConnection(
  r.sourceConnectionId, r.plaidItemId, r.institutionName, r.cursor, r.accessToken,
))
```

(The `accessToken` field is now on `UpsertPlaidConnectionRequest` from Task 5. COALESCE in the SQL preserves the existing encrypted value when the caller passes `None` — the scheduler uses this for cursor-only updates.)

- [ ] **Step 2: Compile**

```bash
cd backend/http_server && sbt "compile"
```

Expected: clean compile (PlaidRoutes still has old method calls — fixed in next task).

- [ ] **Step 3: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidSyncRoutes.scala
git commit -m "feat(routes): PlaidSyncRoutes — forward accessToken to upsertConnection"
```

---

## Task 8: Router — Replace PlaidRoutes with PlaidItemsRoutes

**Files:**
- Modify: `backend/http_server/src/main/scala/com/myassistant/api/Router.scala`
- Delete: `backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidRoutes.scala`

- [ ] **Step 1: Update Router.scala**

In `Router.scala`:

1. Add `SecretsConfig` to the imports:
```scala
import com.myassistant.config.{AuthConfig, SecretsConfig}
```

2. Replace `PlaidRoutes.routes` with `PlaidItemsRoutes.routes` in the routes composition (line ~63):
```scala
          SourceConnectionRoutes.routes ++
          PlaidItemsRoutes.routes ++
          PlaidSyncRoutes.routes) @@ AuthMiddleware(authCfg.token)
```

3. Add `SecretsConfig` to the `AppEnv` type (the environment union type for protected routes). Find the `type AppEnv` or the inline `&` chain and add `& SecretsConfig`.

The full updated env type union (around line 34) should include:
```scala
      & SourceConnectionService
      & PlaidClient
      & PlaidSyncRepository
      & SecretsConfig
      & EmbedClient
      & ZConnectionPool
      & AuthConfig
```

- [ ] **Step 2: Delete old PlaidRoutes.scala**

```bash
rm backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidRoutes.scala
```

- [ ] **Step 3: Check Main.scala wires SecretsConfig to the router**

```bash
grep -n "SecretsConfig\|secretsConfig" backend/http_server/src/main/scala/com/myassistant/Main.scala
```

If `SecretsConfig` is already in the layer composition, no change needed. If not, add it. (It is already used for `SourceConnectionService`, so it should be present.)

- [ ] **Step 4: Compile and test**

```bash
cd backend/http_server && sbt "compile"
```

Expected: clean compile.

```bash
cd backend/http_server && sbt "test"
```

Expected: all existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/api/Router.scala
git rm backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidRoutes.scala
git commit -m "feat(router): replace PlaidRoutes with PlaidItemsRoutes; add SecretsConfig to env"
```

---

## Task 9: Python plaid_poll.py — Item-Level Sync Loop

**Files:**
- Modify: `backend/scheduler/handlers/plaid_poll.py`

The poller now treats each `source_connections` row as a Plaid integration (one per person). It fetches the integration's Plaid credentials from `/secrets`, lists all linked banks from `/plaid/items`, and syncs each bank independently.

- [ ] **Step 1: Replace the file**

```python
"""Plaid scheduler handler — two-level source_connections architecture.

Flow:
  source_connections row = one Plaid integration per person (holds client_id + secret)
  plaid.connections rows = one per linked bank (holds access_token)

Per source_connection run:
  1. Create a sync_run (scheduled, running).
  2. Fetch integration credentials from /api/v1/source-connections/{id}/secrets
     → { client_id, secret }
  3. List linked banks from /api/v1/source-connections/{id}/plaid/items
     → [{ id, plaid_item_id, institution_name, cursor, access_token }]
  4. For each bank item: run /transactions/sync + /accounts/get, upsert results.
  5. Patch sync_run with terminal status and stats.
  6. Mark source_connection synced; advance next_run_at.
"""

import os
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import httpx

from handlers.base import BaseHandler

_PLAID_ENV = os.environ.get("PLAID_ENV", "sandbox")

_PLAID_BASE = {
    "production":  "https://production.plaid.com",
    "development": "https://development.plaid.com",
}.get(_PLAID_ENV, "https://sandbox.plaid.com")

_SCHEDULER_TZ = ZoneInfo(os.environ.get("SCHEDULER_TIMEZONE", "UTC"))


def _plaid_post(path: str, body: dict, client_id: str, secret: str) -> dict:
    body = {**body, "client_id": client_id, "secret": secret}
    resp = httpx.post(f"{_PLAID_BASE}{path}", json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _log_entry(level: str, msg: str) -> dict:
    return {
        "time":  datetime.now(timezone.utc).strftime("%H:%M:%S"),
        "level": level,
        "msg":   msg,
    }


class PlaidPollHandler(BaseHandler):
    """Plaid scheduled handler — one run per Plaid integration connection."""

    def __init__(self, http: httpx.Client):
        self.http = http

    # ── Sync run lifecycle ────────────────────────────────────────────────

    def _create_scheduled_run(self, connection_id: str) -> str:
        resp = self.http.post(
            f"/api/v1/source-connections/{connection_id}/runs/create-scheduled",
            json={},
        )
        resp.raise_for_status()
        return resp.json()["id"]

    def _patch_run(
        self,
        connection_id: str,
        run_id: str,
        status: str,
        stats: dict,
        log_lines: list[dict],
    ) -> None:
        body = {
            "status":      status,
            "completedAt": _now_iso(),
            "stats":       stats,
            "logLines":    log_lines,
        }
        resp = self.http.patch(
            f"/api/v1/source-connections/{connection_id}/runs/{run_id}",
            json=body,
        )
        if not resp.is_success:
            print(f"[plaid_poll] WARNING: PATCH run failed: {resp.status_code} {resp.text[:200]}")

    def _mark_synced(self, connection_id: str) -> None:
        resp = self.http.post(
            f"/api/v1/source-connections/{connection_id}/mark-synced",
            json={"lastSyncedAt": _now_iso()},
        )
        if not resp.is_success:
            print(f"[plaid_poll] WARNING: mark-synced failed: {resp.status_code} {resp.text[:200]}")

    def _advance_next_run(self, connection_id: str, cron_expression: str | None) -> None:
        try:
            from croniter import croniter
            cron_expr = cron_expression or "0 2 * * *"
            cron = croniter(cron_expr, datetime.now(_SCHEDULER_TZ))
            next_run = cron.get_next(datetime).astimezone(timezone.utc)
        except Exception:
            next_run = datetime.now(timezone.utc) + timedelta(hours=24)
        resp = self.http.post(
            f"/api/v1/source-connections/{connection_id}/advance",
            json={"nextRunAt": next_run.isoformat()},
        )
        if not resp.is_success:
            print(f"[plaid_poll] WARNING: advance failed: {resp.status_code} {resp.text[:200]}")

    # ── Data fetchers ─────────────────────────────────────────────────────

    def _fetch_plaid_creds(self, connection_id: str) -> tuple[str, str]:
        """Return (client_id, secret) from integration source_connection secrets."""
        resp = self.http.get(f"/api/v1/source-connections/{connection_id}/secrets")
        resp.raise_for_status()
        secrets = resp.json().get("secrets") or {}
        client_id = secrets.get("client_id", "")
        secret    = secrets.get("secret", "")
        if not client_id or not secret:
            raise ValueError(f"Missing Plaid credentials in source_connection {connection_id}")
        return client_id, secret

    def _list_plaid_items(self, connection_id: str) -> list[dict]:
        """Return list of plaid.connections items with decrypted access_token."""
        resp = self.http.get(f"/api/v1/source-connections/{connection_id}/plaid/items")
        resp.raise_for_status()
        return resp.json()

    # ── plaid.* upserts (via Scala API) ──────────────────────────────────

    def _upsert_plaid_connection(
        self,
        source_connection_id: str,
        plaid_item_id: str,
        institution_name: str,
        cursor: str | None,
    ) -> dict:
        resp = self.http.post("/api/v1/plaid/connections/upsert", json={
            "sourceConnectionId": source_connection_id,
            "plaidItemId":        plaid_item_id,
            "institutionName":    institution_name,
            "cursor":             cursor,
            "accessToken":        None,  # preserve existing via COALESCE
        })
        resp.raise_for_status()
        return resp.json()

    def _upsert_plaid_account(
        self,
        source_connection_id: str,
        connection_id: str,
        plaid_account_id: str,
        name: str,
        account_type: str,
        current_balance: float | None,
    ) -> dict:
        resp = self.http.post("/api/v1/plaid/accounts/upsert", json={
            "sourceConnectionId": source_connection_id,
            "connectionId":       connection_id,
            "plaidAccountId":     plaid_account_id,
            "name":               name,
            "accountType":        account_type,
            "currentBalance":     current_balance,
        })
        resp.raise_for_status()
        return resp.json()

    def _send_transactions_batch(
        self,
        source_connection_id: str,
        account_id: str,
        added: list[dict],
        modified: list[dict],
        removed_ids: list[str],
    ) -> dict:
        resp = self.http.post("/api/v1/plaid/transactions/batch", json={
            "sourceConnectionId":         source_connection_id,
            "accountId":                  account_id,
            "added":                      added,
            "modified":                   modified,
            "removedPlaidTransactionIds": removed_ids,
        })
        resp.raise_for_status()
        return resp.json()

    @staticmethod
    def _txn_to_payload(txn: dict) -> dict:
        cat = txn.get("personal_finance_category") or {}
        category_list = [c for c in [cat.get("primary"), cat.get("detailed")] if c]
        return {
            "plaidTransactionId": txn["transaction_id"],
            "amount":             txn["amount"],
            "date":               txn["date"],
            "merchantName":       txn.get("merchant_name") or txn.get("name"),
            "category":           category_list,
            "paymentChannel":     txn.get("payment_channel"),
            "pending":            txn.get("pending", False),
        }

    # ── Per-item sync ─────────────────────────────────────────────────────

    def _sync_item(
        self,
        source_connection_id: str,
        item: dict,
        client_id: str,
        secret: str,
        stats: dict,
        log_lines: list[dict],
    ) -> None:
        """Sync one plaid.connections item (one bank)."""
        access_token     = item.get("accessToken", "")
        plaid_item_id    = item.get("plaidItemId", "")
        institution_name = item.get("institutionName", "Unknown")
        cursor           = item.get("cursor")
        plaid_conn_id    = item["id"]

        if not access_token:
            log_lines.append(_log_entry("error", f"No access_token for item {plaid_item_id}"))
            stats["errors"] += 1
            return

        log_lines.append(_log_entry("info", f"Syncing {institution_name} (item {plaid_item_id})"))

        all_added:   list[dict] = []
        all_modified: list[dict] = []
        all_removed: list[dict] = []
        next_cursor = cursor

        while True:
            body: dict = {"access_token": access_token}
            if next_cursor:
                body["cursor"] = next_cursor
            sync_resp  = _plaid_post("/transactions/sync", body, client_id, secret)
            batch_added    = sync_resp.get("added", [])
            batch_modified = sync_resp.get("modified", [])
            batch_removed  = sync_resp.get("removed", [])
            has_more       = sync_resp.get("has_more", False)
            all_added.extend(batch_added)
            all_modified.extend(batch_modified)
            all_removed.extend(batch_removed)
            next_cursor = sync_resp.get("next_cursor", "") or next_cursor
            log_lines.append(_log_entry(
                "info",
                f"  /transactions/sync: +{len(batch_added)} ~{len(batch_modified)} "
                f"-{len(batch_removed)} has_more={has_more}",
            ))
            if not has_more:
                break

        accounts_resp = _plaid_post("/accounts/get", {"access_token": access_token}, client_id, secret)
        accounts      = accounts_resp.get("accounts", [])

        if next_cursor and next_cursor != cursor:
            updated = self._upsert_plaid_connection(
                source_connection_id=source_connection_id,
                plaid_item_id=plaid_item_id,
                institution_name=institution_name,
                cursor=next_cursor,
            )
            plaid_conn_id = updated["id"]

        plaid_acct_to_row_id: dict[str, str] = {}
        for account in accounts:
            balances = account.get("balances") or {}
            row = self._upsert_plaid_account(
                source_connection_id=source_connection_id,
                connection_id=plaid_conn_id,
                plaid_account_id=account["account_id"],
                name=account["name"],
                account_type=account.get("type", "other"),
                current_balance=balances.get("current"),
            )
            plaid_acct_to_row_id[account["account_id"]] = row["id"]
            stats["accounts_checked"] += 1

        added_by_acct:    dict[str, list[dict]] = {}
        modified_by_acct: dict[str, list[dict]] = {}
        removed_by_acct:  dict[str, list[str]]  = {}

        for txn in all_added:
            added_by_acct.setdefault(txn["account_id"], []).append(self._txn_to_payload(txn))
        for txn in all_modified:
            modified_by_acct.setdefault(txn["account_id"], []).append(self._txn_to_payload(txn))
        for r in all_removed:
            acct_plaid_id = r.get("account_id")
            txn_plaid_id  = r.get("transaction_id")
            if not txn_plaid_id:
                continue
            if acct_plaid_id and acct_plaid_id in plaid_acct_to_row_id:
                removed_by_acct.setdefault(acct_plaid_id, []).append(txn_plaid_id)
            else:
                fallback = next(iter(plaid_acct_to_row_id.keys()), None)
                if fallback:
                    removed_by_acct.setdefault(fallback, []).append(txn_plaid_id)

        for acct_plaid_id in set(added_by_acct) | set(modified_by_acct) | set(removed_by_acct):
            row_id = plaid_acct_to_row_id.get(acct_plaid_id)
            if not row_id:
                log_lines.append(_log_entry("warn", f"Skipping {acct_plaid_id}: no matching row"))
                stats["errors"] += 1
                continue
            batch_resp = self._send_transactions_batch(
                source_connection_id=source_connection_id,
                account_id=row_id,
                added=added_by_acct.get(acct_plaid_id, []),
                modified=modified_by_acct.get(acct_plaid_id, []),
                removed_ids=removed_by_acct.get(acct_plaid_id, []),
            )
            stats["added"]    += batch_resp.get("added", 0)
            stats["modified"] += batch_resp.get("modified", 0)
            stats["removed"]  += batch_resp.get("removed", 0)

    # ── Main entry point ──────────────────────────────────────────────────

    def run(self, source_connection: dict) -> None:
        """Execute one scheduled Plaid sync for the given integration source_connection."""
        connection_id   = source_connection["id"]
        cron_expression = source_connection.get("syncSchedule")

        log_lines: list[dict] = []
        stats = {"added": 0, "modified": 0, "removed": 0, "accounts_checked": 0, "errors": 0}
        terminal_status = "running"
        run_id: str | None = None

        try:
            run_id = self._create_scheduled_run(connection_id)
            log_lines.append(_log_entry("info", "Starting Plaid integration sync"))
            print(f"[plaid_poll] connection {connection_id}: started run {run_id}")

            client_id, secret = self._fetch_plaid_creds(connection_id)

            items = self._list_plaid_items(connection_id)
            if not items:
                log_lines.append(_log_entry("warn", "No linked bank items found — nothing to sync"))
                terminal_status = "success"
                return

            log_lines.append(_log_entry("info", f"Found {len(items)} linked bank(s)"))

            for item in items:
                self._sync_item(connection_id, item, client_id, secret, stats, log_lines)

            log_lines.append(_log_entry(
                "info",
                f"Sync complete: +{stats['added']} ~{stats['modified']} -{stats['removed']} "
                f"across {stats['accounts_checked']} account(s)",
            ))

            terminal_status = "warning" if stats["errors"] > 0 else "success"

        except Exception as e:
            log_lines.append(_log_entry("error", f"Fatal: {e}"))
            stats["errors"] += 1
            terminal_status = "failed"
            print(f"[plaid_poll] connection {connection_id}: fatal error: {e}")

        finally:
            if run_id is not None:
                self._patch_run(connection_id, run_id, terminal_status, stats, log_lines)
            if terminal_status in ("success", "warning"):
                self._mark_synced(connection_id)
            if run_id is not None:
                self._advance_next_run(connection_id, cron_expression)
            print(f"[plaid_poll] connection {connection_id}: status={terminal_status} stats={stats}")
```

- [ ] **Step 2: Remove PLAID_CLIENT_ID and PLAID_SECRET env var references from scheduler/main.py**

```bash
grep -n "PLAID_CLIENT_ID\|PLAID_SECRET" backend/scheduler/main.py
```

Remove or comment out any `os.environ` lookups for `PLAID_CLIENT_ID` and `PLAID_SECRET` in `main.py` (credentials now come from DB per connection, not from global env vars).

- [ ] **Step 3: Commit**

```bash
git add backend/scheduler/handlers/plaid_poll.py backend/scheduler/main.py
git commit -m "feat(scheduler): plaid_poll — iterate items per integration; creds from DB secrets"
```

---

## Task 10: frontend api.ts — Source-Connection-Scoped Plaid Functions

**Files:**
- Modify: `frontend/src/api.ts`

- [ ] **Step 1: Add new Plaid API functions**

In `api.ts`, find the `// ── Finance / Plaid ──` section. Replace the entire Plaid section (everything from the `PlaidConnectionFields` interface to `disconnectPlaidAccount`) with:

```typescript
// ── Finance / Plaid ──────────────────────────────────────────────────────────

export interface PlaidItem {
  id: string
  sourceConnectionId: string
  plaidItemId: string
  institutionName: string
  cursor: string | null
  accessToken: string | null  // decrypted; only used by scheduler — ignore in UI
  createdAt: string
  updatedAt: string
}

export interface PlaidBankAccount {
  id: string
  sourceConnectionId: string
  connectionId: string
  plaidAccountId: string
  name: string
  accountType: string
  currentBalance: number | null
}

/** Fetch a Plaid Link token using the credentials stored on this source_connection. */
export async function fetchLinkTokenForConnection(connectionId: string): Promise<string> {
  const resp = await fetch(`/api/v1/source-connections/${connectionId}/plaid/link-token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({}),
  })
  if (!resp.ok) throw new Error(`link-token failed: ${await resp.text()}`)
  const data = await resp.json()
  return data.linkToken
}

/** Exchange a Plaid public token; creates a plaid.connections row under this source_connection. */
export async function exchangeTokenForConnection(
  connectionId: string,
  publicToken: string,
): Promise<{ plaidItemId: string; institutionName: string }> {
  const resp = await fetch(`/api/v1/source-connections/${connectionId}/plaid/exchange`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ publicToken }),
  })
  if (!resp.ok) throw new Error(`exchange failed: ${await resp.text()}`)
  return resp.json()
}

/** List linked bank items under a source_connection. */
export async function listPlaidItems(connectionId: string): Promise<PlaidItem[]> {
  const resp = await fetch(`/api/v1/source-connections/${connectionId}/plaid/items`, {
    headers: authHeaders(),
  })
  if (!resp.ok) throw new Error(`list items failed: ${await resp.text()}`)
  return resp.json()
}

/** Disconnect (delete) one linked bank item. */
export async function disconnectPlaidItem(connectionId: string, itemId: string): Promise<void> {
  const resp = await fetch(`/api/v1/source-connections/${connectionId}/plaid/items/${itemId}`, {
    method: 'DELETE',
    headers: authHeaders(),
  })
  if (!resp.ok) throw new Error(`disconnect failed: ${await resp.text()}`)
}
```

Note: `authHeaders()` should be whatever the existing auth helper is in `api.ts` (check the existing pattern).

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend && npm run build 2>&1 | head -30
```

Expected: errors only in `FinanceTab.tsx` referencing removed functions — fixed in Task 12.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/api.ts
git commit -m "feat(api): replace flat Plaid API functions with source-connection-scoped equivalents"
```

---

## Task 11: SourceConnectionForm.tsx — Plaid Credentials on Create; Linked Banks on Edit

**Files:**
- Modify: `frontend/src/components/SourceConnectionForm.tsx`

**Plaid create flow:** Show `client_id` and `secret` input fields. On save, serialize them as `secrets: '{"client_id":"...","secret":"..."}'`. No Plaid Link during create.

**Plaid edit flow:** Show a "Linked Banks" section listing `PlaidItem[]` fetched from `listPlaidItems(editingId)`. Show an "Add Bank" button that triggers Plaid Link using `fetchLinkTokenForConnection` + `exchangeTokenForConnection`. Show a "Disconnect" button per bank that calls `disconnectPlaidItem`.

- [ ] **Step 1: Update imports at top of file**

Replace the Plaid-related imports:
```typescript
import { usePlaidLink } from 'react-plaid-link'
import { fetchLinkToken, exchangeToken, createSourceConnection, updateSourceConnection, getSourceConnection } from '../api'
```

With:
```typescript
import { usePlaidLink } from 'react-plaid-link'
import {
  fetchLinkTokenForConnection,
  exchangeTokenForConnection,
  listPlaidItems,
  disconnectPlaidItem,
  createSourceConnection,
  updateSourceConnection,
  getSourceConnection,
  type PlaidItem,
} from '../api'
```

- [ ] **Step 2: Add state for Plaid credentials (create flow) and linked banks (edit flow)**

After the existing state declarations (around line 154), add:

```typescript
  // Plaid credentials — only used during create
  const [plaidClientId, setPlaidClientId] = useState('')
  const [plaidSecret, setPlaidSecret]     = useState('')

  // Plaid linked banks — loaded during edit
  const [plaidItems, setPlaidItems]       = useState<PlaidItem[]>([])
  const [loadingItems, setLoadingItems]   = useState(false)
  const [disconnecting, setDisconnecting] = useState<string | null>(null)

  // Plaid Link state — used during edit to add a new bank
  const [linkToken, setLinkToken]         = useState<string | null>(null)
  const [addingBank, setAddingBank]       = useState(false)
  const [addBankError, setAddBankError]   = useState<string | null>(null)
```

- [ ] **Step 3: Load linked banks when editing a Plaid connection**

Inside the `useEffect` that loads existing connection data (around line 168), add after the existing state setters:

```typescript
        if (conn.sourceType === 'plaid_poll') {
          setLoadingItems(true)
          listPlaidItems(editingId)
            .then(setPlaidItems)
            .catch(e => setLoadError(e instanceof Error ? e.message : 'Failed to load linked banks'))
            .finally(() => setLoadingItems(false))
        }
```

- [ ] **Step 4: Add handlers for Add Bank and Disconnect**

After `handlePlaidExit`, add:

```typescript
  const handleAddBank = async () => {
    if (!editingId) return
    setAddingBank(true)
    setAddBankError(null)
    try {
      const token = await fetchLinkTokenForConnection(editingId)
      setLinkToken(token)
    } catch (e) {
      setAddBankError(e instanceof Error ? e.message : 'Failed to get link token')
      setAddingBank(false)
    }
  }

  const handleAddBankSuccess = async (publicToken: string) => {
    if (!editingId) return
    try {
      await exchangeTokenForConnection(editingId, publicToken)
      setLinkToken(null)
      setAddingBank(false)
      const items = await listPlaidItems(editingId)
      setPlaidItems(items)
    } catch (e) {
      setAddBankError(e instanceof Error ? e.message : 'Exchange failed')
      setAddingBank(false)
    }
  }

  const handleAddBankExit = () => {
    setLinkToken(null)
    setAddingBank(false)
  }

  const handleDisconnect = async (item: PlaidItem) => {
    if (!editingId) return
    setDisconnecting(item.id)
    try {
      await disconnectPlaidItem(editingId, item.id)
      setPlaidItems(prev => prev.filter(i => i.id !== item.id))
    } catch (e) {
      setAddBankError(e instanceof Error ? e.message : 'Disconnect failed')
    } finally {
      setDisconnecting(null)
    }
  }
```

- [ ] **Step 5: Update handleSave to include Plaid secrets for new connections**

Inside `handleSave`, update the non-editing create path for Plaid:

```typescript
      } else if (sourceType === 'plaid_poll') {
        if (!plaidClientId.trim() || !plaidSecret.trim()) {
          setSaveError('Plaid Client ID and Secret are required.')
          setSaving(false)
          return
        }
        await createSourceConnection({
          sourceType,
          connectionName: connectionName.trim(),
          personId: session.personId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : undefined,
          secrets: JSON.stringify({ client_id: plaidClientId.trim(), secret: plaidSecret.trim() }),
        })
      } else {
```

(Split the existing `else` create block into a Plaid-specific branch and a generic branch.)

- [ ] **Step 6: Replace the Plaid-specific section in the JSX (Section 3)**

Replace the entire `{sourceType === 'plaid_poll' && (...)}` block with:

```tsx
      {/* Section 3: Plaid-specific */}
      {sourceType === 'plaid_poll' && (
        <div style={{ marginBottom: 24 }}>
          {!isEditing ? (
            <>
              <span style={sectionLabel}>Plaid API Credentials</span>
              <div style={{ marginBottom: 10 }}>
                <input
                  type="text"
                  value={plaidClientId}
                  onChange={e => setPlaidClientId(e.target.value)}
                  placeholder="client_id"
                  style={{
                    width: '100%',
                    background: T.bgInput,
                    border: `1px solid ${T.border}`,
                    borderRadius: 8,
                    padding: '10px 12px',
                    color: T.textPrimary,
                    fontSize: 14,
                    outline: 'none',
                    boxSizing: 'border-box',
                    marginBottom: 8,
                  }}
                />
                <input
                  type="password"
                  value={plaidSecret}
                  onChange={e => setPlaidSecret(e.target.value)}
                  placeholder="secret"
                  style={{
                    width: '100%',
                    background: T.bgInput,
                    border: `1px solid ${T.border}`,
                    borderRadius: 8,
                    padding: '10px 12px',
                    color: T.textPrimary,
                    fontSize: 14,
                    outline: 'none',
                    boxSizing: 'border-box',
                  }}
                />
              </div>
              <div style={{ color: T.textMuted, fontSize: 12 }}>
                Credentials are encrypted and stored securely. You can link bank accounts after saving.
              </div>
            </>
          ) : (
            <>
              <span style={sectionLabel}>Linked Banks</span>
              {loadingItems ? (
                <div style={{ color: T.textMuted, fontSize: 13 }}>Loading...</div>
              ) : plaidItems.length === 0 ? (
                <div style={{ color: T.textMuted, fontSize: 13, marginBottom: 10 }}>
                  No banks linked yet.
                </div>
              ) : (
                <div style={{ marginBottom: 12 }}>
                  {plaidItems.map(item => (
                    <div key={item.id} style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      padding: '8px 12px',
                      background: T.bgRunStrip,
                      border: `1px solid ${T.borderRun}`,
                      borderRadius: 6,
                      marginBottom: 6,
                    }}>
                      <span style={{ fontSize: 13, color: T.textPrimary }}>{item.institutionName}</span>
                      <button
                        onClick={() => handleDisconnect(item)}
                        disabled={disconnecting === item.id}
                        style={{
                          background: 'transparent',
                          border: `1px solid ${T.border}`,
                          color: T.textMuted,
                          borderRadius: 4,
                          padding: '3px 10px',
                          fontSize: 12,
                          cursor: disconnecting === item.id ? 'not-allowed' : 'pointer',
                        }}
                      >
                        {disconnecting === item.id ? 'Removing...' : 'Disconnect'}
                      </button>
                    </div>
                  ))}
                </div>
              )}

              {addBankError && (
                <div style={{ color: T.errorText, fontSize: 12, marginBottom: 8 }}>{addBankError}</div>
              )}

              {linkToken ? (
                <PlaidLinkButton
                  token={linkToken}
                  personId={session.personId}
                  onSuccess={handleAddBankSuccess}
                  onExit={handleAddBankExit}
                />
              ) : (
                <button
                  onClick={handleAddBank}
                  disabled={addingBank}
                  style={{
                    background: T.accent,
                    border: 'none',
                    color: '#fff',
                    borderRadius: 8,
                    padding: '8px 16px',
                    fontSize: 13,
                    fontWeight: 600,
                    cursor: addingBank ? 'not-allowed' : 'pointer',
                    opacity: addingBank ? 0.6 : 1,
                  }}
                >
                  {addingBank ? 'Preparing...' : '+ Add Bank'}
                </button>
              )}

              <div style={{ background: T.bgRunStrip, border: `1px solid ${T.borderRun}`, borderRadius: 8, padding: '10px 14px', color: T.textSecondary, fontSize: 13, marginTop: 12 }}>
                API Credentials: ••••••• (stored encrypted)
              </div>
            </>
          )}
        </div>
      )}
```

- [ ] **Step 7: Update PlaidLinkButton to use new success callback signature**

Find the `PlaidLinkButton` component. Update the `onSuccess` prop type and handler:

```typescript
interface PlaidLinkButtonProps {
  token: string
  personId: string
  onSuccess: (publicToken: string) => void  // was: () => void
  onExit: () => void
}
```

And update the `usePlaidLink` onSuccess to pass the public token:
```typescript
    onSuccess: async (publicToken) => {
      onSuccess(publicToken)
    },
```

- [ ] **Step 8: TypeScript build check**

```bash
cd frontend && npm run build 2>&1 | head -40
```

Expected: errors only in `FinanceTab.tsx` — fixed in Task 12.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/components/SourceConnectionForm.tsx
git commit -m "feat(ui): SourceConnectionForm — Plaid creds on create; linked banks panel on edit"
```

---

## Task 12: FinanceTab.tsx — Remove Connect Flow (Read-Only)

**Files:**
- Modify: `frontend/src/components/FinanceTab.tsx`

FinanceTab now shows connected accounts read-only. The connect/disconnect flow has moved to `SourceConnectionForm`. We rewrite it to use `listPlaidItems` per source_connection to get the bank list.

- [ ] **Step 1: Rewrite FinanceTab to use the new API**

Replace the entire file:

```tsx
import { useCallback, useEffect, useState } from 'react'
import type { Session } from '../types'
import type { SourceConnection } from '../types'
import { listSourceConnections, listPlaidItems, type PlaidItem } from '../api'

interface Props {
  session: Session
}

export default function FinanceTab({ session }: Props) {
  const [connections, setConnections] = useState<SourceConnection[]>([])
  const [itemsByConn, setItemsByConn] = useState<Record<string, PlaidItem[]>>({})
  const [loading, setLoading]         = useState(true)
  const [error, setError]             = useState<string | null>(null)

  const loadData = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const conns = (await listSourceConnections(session.personId))
        .filter(c => c.sourceType === 'plaid_poll')
      setConnections(conns)
      const map: Record<string, PlaidItem[]> = {}
      await Promise.all(conns.map(async c => {
        map[c.id] = await listPlaidItems(c.id)
      }))
      setItemsByConn(map)
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }, [session.personId])

  useEffect(() => { loadData() }, [loadData])

  return (
    <div style={styles.wrapper}>
      <h2 style={styles.heading}>Connected Accounts</h2>
      <div style={{ color: 'var(--text-muted)', fontSize: 12, marginBottom: 16 }}>
        Manage connections in the Connections tab.
      </div>

      {error && <div style={styles.error}>{error}</div>}

      {loading ? (
        <div style={styles.muted}>Loading...</div>
      ) : connections.length === 0 ? (
        <div style={styles.muted}>No Plaid connections. Add one in the Connections tab.</div>
      ) : (
        <div style={styles.list}>
          {connections.map(conn => {
            const items = itemsByConn[conn.id] ?? []
            return (
              <div key={conn.id} style={styles.card}>
                <div style={styles.cardHeader}>
                  <span style={styles.connName}>{conn.connectionName}</span>
                  {conn.lastSyncedAt && (
                    <span style={styles.lastSynced}>
                      Synced {new Date(conn.lastSyncedAt).toLocaleDateString()}
                    </span>
                  )}
                </div>
                {items.length === 0 ? (
                  <div style={styles.muted}>No banks linked.</div>
                ) : (
                  items.map(item => (
                    <div key={item.id} style={styles.item}>
                      {item.institutionName}
                    </div>
                  ))
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper:    { padding: '32px 24px', maxWidth: 640, margin: '0 auto' },
  heading:    { fontWeight: 600, fontSize: 18, marginBottom: 4, color: 'var(--text-primary)' },
  list:       { display: 'flex', flexDirection: 'column', gap: 12 },
  card:       { background: 'var(--bg-surface2)', border: '1px solid var(--border)', borderRadius: 8, padding: 16 },
  cardHeader: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  connName:   { fontWeight: 600, fontSize: 15, color: 'var(--text-primary)' },
  lastSynced: { fontSize: 11, color: 'var(--text-muted)' },
  item:       { padding: '4px 0', color: 'var(--text-secondary)', fontSize: 13 },
  muted:      { color: 'var(--text-muted)', fontSize: 13, marginBottom: 12 },
  error:      { background: '#3d1a1a', color: '#ff6b6b', padding: '10px 14px', borderRadius: 6, marginBottom: 16, fontSize: 13 },
}
```

Note: `listSourceConnections` needs to be confirmed as an existing function in `api.ts`. Check the function name and adjust if different.

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend && npm run build 2>&1 | head -20
```

Expected: clean build.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/FinanceTab.tsx
git commit -m "feat(ui): FinanceTab — read-only view using new plaid items API; connect flow moved to SourceConnectionForm"
```

---

## Task 13: Spec Document Update

**Files:**
- Modify: `docs/superpowers/specs/2026-05-18-multi-source-connector-architecture-design.md`

- [ ] **Step 1: Update the Source Connection Registry section to reflect confirmed design decisions**

Find section `## 1. Source Connection Registry` and add the following block after the `source_connections` table DDL:

```markdown
### Confirmed design decisions (2026-05-20)

**Two-level hierarchy:** `source_connections` is the integration level — one row per `(source_type, person_id)`. Child rows live in connector-specific tables (`plaid.connections`, etc.).

**Plaid credential placement:** `source_connections.secrets` holds `{"client_id": "...", "secret": "..."}` (Plaid API credentials, encrypted). `plaid.connections.access_token` holds the per-item encrypted access_token.

**Uniqueness policy:**
- `(source_type, person_id)` — enforced via droppable partial unique index (one connection per type per person, for now)
- `(connection_name, person_id)` — permanent partial unique index (names are unique per person)

**UI contract:** The source connection edit screen is the management surface for child items. For Plaid: the edit screen lists linked banks with Add/Disconnect. Adding a bank triggers Plaid Link scoped to this connection's credentials.
```

- [ ] **Step 2: Update the `plaid.connections` table definition to show `access_token`**

In the Structured Source Connectors section, update the `plaid.connections` DDL to include:

```sql
CREATE TABLE plaid.connections (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_connection_id  UUID        NOT NULL REFERENCES source_connections(id) ON DELETE CASCADE,
    plaid_item_id         TEXT        NOT NULL UNIQUE,
    institution_name      TEXT        NOT NULL,
    cursor                TEXT,
    access_token          TEXT,       -- AES-256-GCM encrypted; COALESCE-preserved on upsert
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-05-18-multi-source-connector-architecture-design.md
git commit -m "docs: update multi-source-connector spec with confirmed two-level Plaid hierarchy decisions"
```

---

## Self-Review

**Spec coverage check:**

| Requirement | Task |
|---|---|
| Unique index `(source_type, person_id)` — droppable | Task 1 |
| Unique index `(connection_name, person_id)` — permanent | Task 1 |
| `access_token` per Plaid item | Tasks 1, 4, 5 |
| Plaid API creds stored on `source_connections.secrets` | Task 6 (create flow), Task 11 |
| source-connection-scoped link-token endpoint | Task 6 |
| source-connection-scoped exchange endpoint | Task 6 |
| List linked banks endpoint (with decrypted tokens) | Task 6 |
| Disconnect bank endpoint | Task 6 |
| PlaidClient uses per-call credentials | Task 3 |
| Python poller iterates items per integration | Task 9 |
| `SourceConnectionForm` create: enter Plaid creds | Task 11 |
| `SourceConnectionForm` edit: manage linked banks | Task 11 |
| FinanceTab read-only (no connect flow) | Task 12 |

**Type consistency check:**
- `PlaidConnectionRow.accessToken: Option[String]` defined in Task 4, used in Task 5 and Task 6 ✓
- `PlaidItemResponse.fromDomain(row, decryptedToken)` defined in Task 5, used in Task 6 ✓
- `upsertConnection(..., accessToken: Option[String])` defined in Task 4, called in Tasks 6 and 7 ✓
- `PlaidClient` methods all take `(clientId: String, secret: String)` — defined in Task 3, called in Task 6 ✓
- `fetchLinkTokenForConnection`, `exchangeTokenForConnection`, `listPlaidItems`, `disconnectPlaidItem` — defined in Task 10, used in Tasks 11 and 12 ✓

**Placeholder scan:** No TBD, TODO, or "similar to" references found.
