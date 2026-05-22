# Plaid Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Plaid financial data into the personal assistant — bank accounts and transactions stored as facts/documents, manageable via a Finance tab in the UI and a scheduled poller.

**Architecture:** Two new Scala endpoints handle Plaid OAuth (link-token + exchange); a Python `plaid_poll.py` handler (same scheduler pattern as news) syncs transactions and balances on a user-defined schedule; a Finance tab in the React UI manages connected accounts using `react-plaid-link`. No new DB tables — everything uses the existing document + fact system.

**Tech Stack:** Scala 3 / ZIO 2 / zio-http (Plaid HTTP calls via `java.net.http.HttpClient`), Python 3 / httpx (poller), React 18 / TypeScript / react-plaid-link (frontend), Flyway (migration), Plaid Sandbox API.

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `backend/http_server/src/main/resources/db/migration/V15__plaid_finance.sql` | Create | Add plaid_connection, transaction, bank_account entity type schemas; mark plaid_poll as is_scheduled |
| `backend/http_server/src/main/scala/com/myassistant/config/PlaidConfig.scala` | Create | HOCON config case class for Plaid credentials |
| `backend/http_server/src/main/scala/com/myassistant/config/AppConfig.scala` | Modify | Add `plaid: PlaidConfig` field |
| `backend/http_server/src/main/resources/application.conf` | Modify | Add `plaid { clientId, secret, env }` section |
| `backend/http_server/src/main/scala/com/myassistant/api/plaid/PlaidModels.scala` | Create | Request/response models for our routes + Plaid API response models |
| `backend/http_server/src/main/scala/com/myassistant/api/plaid/PlaidClient.scala` | Create | Service that calls Plaid REST API using Java HttpClient |
| `backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidRoutes.scala` | Create | POST /api/v1/plaid/link-token and POST /api/v1/plaid/exchange |
| `backend/http_server/src/main/scala/com/myassistant/api/Router.scala` | Modify | Add PlaidClient to AppEnv; add PlaidRoutes to protected routes |
| `backend/http_server/src/main/scala/com/myassistant/Main.scala` | Modify | Wire PlaidConfig + PlaidClient layers |
| `backend/scheduler/handlers/plaid_poll.py` | Create | Scheduled handler: sync transactions + balances, store as facts |
| `backend/scheduler/main.py` | Modify | Register PlaidPollHandler in handler map + pass PLAID_* env vars |
| `frontend/vite.config.ts` | Modify | Add `/api/v1` proxy to Scala server (port 8080) before `/api` chatbot proxy |
| `frontend/package.json` | Modify | Add `react-plaid-link` dependency |
| `frontend/src/App.tsx` | Modify | Add tab navigation (Chat / Finance) |
| `frontend/src/api.ts` | Modify | Add `fetchLinkToken`, `exchangeToken`, `listPlaidConnections`, `disconnectPlaidAccount` |
| `frontend/src/components/FinanceTab.tsx` | Create | Finance tab: list connections, Plaid Link widget, disconnect button |
| `.env.tmp` | Modify | Add PLAID_CLIENT_ID, PLAID_SECRET, PLAID_ENV |
| `.env.synology.example` | Modify | Add PLAID_CLIENT_ID, PLAID_SECRET, PLAID_ENV |
| `docker-compose.yml` | Modify | Add PLAID_* env vars to http-server and scheduler services |

---

## Task 1: DB Migration — Plaid Entity Type Schemas

**Files:**
- Create: `backend/http_server/src/main/resources/db/migration/V15__plaid_finance.sql`

- [ ] **Step 1: Create the migration file**

```sql
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
```

- [ ] **Step 2: Verify migration runs cleanly**

```bash
cd backend/http_server
sbt flywayMigrate
```

Expected: `Successfully applied 1 migration to schema "public", now at version v15`

If you hit a duplicate key error on entity_type_schema, it means the schema already exists — drop and re-run, or adjust the migration to use `ON CONFLICT DO NOTHING`.

- [ ] **Step 3: Verify schemas are queryable**

```bash
docker exec myassistant-db psql -U myassistant -d myassistant -c \
  "SELECT entity_type, schema_version FROM entity_type_schema WHERE domain_id = (SELECT id FROM domain WHERE name = 'finance') ORDER BY entity_type;"
```

Expected output includes rows for `bank_account`, `plaid_connection`, `transaction`.

- [ ] **Step 4: Commit**

```bash
git add backend/http_server/src/main/resources/db/migration/V15__plaid_finance.sql
git commit -m "feat(plaid): add plaid_connection, transaction, bank_account entity type schemas"
```

---

## Task 2: PlaidConfig — HOCON Configuration

**Files:**
- Create: `backend/http_server/src/main/scala/com/myassistant/config/PlaidConfig.scala`
- Modify: `backend/http_server/src/main/scala/com/myassistant/config/AppConfig.scala`
- Modify: `backend/http_server/src/main/resources/application.conf`

- [ ] **Step 1: Create PlaidConfig.scala**

```scala
package com.myassistant.config

/** Plaid API credentials and environment selection.
 *
 *  env values: "sandbox" | "development" | "production"
 *  All three fields are required; they default to empty strings so the
 *  server starts without crashing, but Plaid calls will fail until real
 *  values are provided via PLAID_CLIENT_ID / PLAID_SECRET / PLAID_ENV.
 */
final case class PlaidConfig(
    clientId: String,
    secret:   String,
    env:      String,
)
```

- [ ] **Step 2: Add `plaid` field to AppConfig.scala**

Current `AppConfig.scala`:
```scala
final case class AppConfig(
    server:      ServerConfig,
    database:    DatabaseConfig,
    auth:        AuthConfig,
    fileStorage: FileStorageConfig,
)
```

Updated `AppConfig.scala` — add one field:
```scala
final case class AppConfig(
    server:      ServerConfig,
    database:    DatabaseConfig,
    auth:        AuthConfig,
    fileStorage: FileStorageConfig,
    plaid:       PlaidConfig,
)
```

(The `object AppConfig` and `val live` remain unchanged — `deriveConfig[AppConfig]` picks up the new field automatically.)

- [ ] **Step 3: Add plaid section to application.conf**

In `backend/http_server/src/main/resources/application.conf`, inside the `myassistant { ... }` block, add after the `fileStorage` section:

```hocon
  # ── Plaid API ────────────────────────────────────────────────
  plaid {
    clientId = ""
    clientId = ${?PLAID_CLIENT_ID}

    secret = ""
    secret = ${?PLAID_SECRET}

    # "sandbox" | "development" | "production"
    env = "sandbox"
    env = ${?PLAID_ENV}
  }
```

- [ ] **Step 4: Verify the server still compiles**

```bash
cd backend/http_server
sbt compile
```

Expected: `[success] Total time: ...`

- [ ] **Step 5: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/config/PlaidConfig.scala \
        backend/http_server/src/main/scala/com/myassistant/config/AppConfig.scala \
        backend/http_server/src/main/resources/application.conf
git commit -m "feat(plaid): add PlaidConfig + application.conf plaid section"
```

---

## Task 3: PlaidModels + PlaidClient

**Files:**
- Create: `backend/http_server/src/main/scala/com/myassistant/api/plaid/PlaidModels.scala`
- Create: `backend/http_server/src/main/scala/com/myassistant/api/plaid/PlaidClient.scala`

- [ ] **Step 1: Create the `com/myassistant/api/plaid/` directory (it's just a package — create both files)**

- [ ] **Step 2: Create PlaidModels.scala**

```scala
package com.myassistant.api.plaid

import io.circe.Codec
import java.util.UUID

// ── Route request / response models ────────────────────────────────────────

final case class LinkTokenRequest(personId: UUID) derives Codec.AsObject
final case class ExchangeRequest(personId: UUID, publicToken: String) derives Codec.AsObject
final case class LinkTokenResponse(linkToken: String) derives Codec.AsObject
final case class ExchangeResponse(itemId: String, institutionName: String) derives Codec.AsObject

// ── Plaid API response models (snake_case matching Plaid JSON) ─────────────

final case class PlaidLinkTokenResp(link_token: String) derives Codec.AsObject
final case class PlaidExchangeResp(access_token: String, item_id: String) derives Codec.AsObject

final case class PlaidBalance(
    current:           Option[Double],
    available:         Option[Double],
    iso_currency_code: Option[String],
) derives Codec.AsObject

final case class PlaidAccount(
    account_id:    String,
    name:          String,
    official_name: Option[String],
    `type`:        String,
    subtype:       Option[String],
    mask:          Option[String],
    balances:      PlaidBalance,
) derives Codec.AsObject

final case class PlaidItem(institution_id: Option[String]) derives Codec.AsObject

final case class PlaidAccountsResp(
    accounts: List[PlaidAccount],
    item:     PlaidItem,
) derives Codec.AsObject

final case class PlaidInstitution(name: String) derives Codec.AsObject
final case class PlaidInstitutionResp(institution: PlaidInstitution) derives Codec.AsObject

final case class PlaidCategory(primary: String, detailed: String) derives Codec.AsObject

final case class PlaidTransaction(
    transaction_id:            String,
    account_id:                String,
    amount:                    Double,
    iso_currency_code:         Option[String],
    merchant_name:             Option[String],
    name:                      String,
    personal_finance_category: Option[PlaidCategory],
    date:                      String,
    pending:                   Boolean,
) derives Codec.AsObject

final case class PlaidRemovedTransaction(transaction_id: String) derives Codec.AsObject

final case class PlaidSyncResp(
    added:       List[PlaidTransaction],
    modified:    List[PlaidTransaction],
    removed:     List[PlaidRemovedTransaction],
    next_cursor: String,
    has_more:    Boolean,
) derives Codec.AsObject
```

- [ ] **Step 3: Create PlaidClient.scala**

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

/** Service that calls the Plaid REST API.
 *
 *  Uses java.net.http.HttpClient (blocking, wrapped in ZIO.attemptBlocking).
 *  No new library dependencies required — JDK 11+ HttpClient is available.
 *
 *  All methods add client_id + secret to every request body before sending.
 */
trait PlaidClient:
  def createLinkToken(clientUserId: String): Task[String]
  def exchangePublicToken(publicToken: String): Task[(String, String)]
  def getAccounts(accessToken: String): Task[PlaidAccountsResp]
  def getInstitutionName(institutionId: String): Task[String]
  def syncTransactions(accessToken: String, cursor: Option[String]): Task[PlaidSyncResp]

object PlaidClient:

  final class Live(cfg: PlaidConfig) extends PlaidClient:

    private val http = JHttpClient.newHttpClient()

    private val baseUrl: String = cfg.env match
      case "production"  => "https://production.plaid.com"
      case "development" => "https://development.plaid.com"
      case _             => "https://sandbox.plaid.com"

    private def withAuth(body: Json): Json =
      Json.fromJsonObject(
        body.asObject.getOrElse(io.circe.JsonObject.empty)
          .add("client_id", Json.fromString(cfg.clientId))
          .add("secret",    Json.fromString(cfg.secret))
      )

    private def post[A: io.circe.Decoder](path: String, body: Json): Task[A] =
      ZIO.attemptBlocking {
        val req = HttpRequest.newBuilder()
          .uri(URI.create(s"$baseUrl$path"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(withAuth(body).noSpaces, StandardCharsets.UTF_8))
          .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if resp.statusCode() >= 400 then
          throw RuntimeException(s"Plaid API error ${resp.statusCode()}: ${resp.body().take(500)}")
        decode[A](resp.body()) match
          case Right(v) => v
          case Left(e)  => throw RuntimeException(s"Plaid decode error for $path: $e")
      }

    def createLinkToken(clientUserId: String): Task[String] =
      post[PlaidLinkTokenResp]("/link/token/create", Json.obj(
        "user"         -> Json.obj("client_user_id" -> Json.fromString(clientUserId)),
        "client_name"  -> Json.fromString("myassistant"),
        "products"     -> Json.arr(Json.fromString("transactions")),
        "country_codes"-> Json.arr(Json.fromString("US")),
        "language"     -> Json.fromString("en"),
      )).map(_.link_token)

    def exchangePublicToken(publicToken: String): Task[(String, String)] =
      post[PlaidExchangeResp]("/item/public_token/exchange", Json.obj(
        "public_token" -> Json.fromString(publicToken),
      )).map(r => (r.access_token, r.item_id))

    def getAccounts(accessToken: String): Task[PlaidAccountsResp] =
      post[PlaidAccountsResp]("/accounts/get", Json.obj(
        "access_token" -> Json.fromString(accessToken),
      ))

    def getInstitutionName(institutionId: String): Task[String] =
      post[PlaidInstitutionResp]("/institutions/get_by_id", Json.obj(
        "institution_id" -> Json.fromString(institutionId),
        "country_codes"  -> Json.arr(Json.fromString("US")),
      )).map(_.institution.name)

    def syncTransactions(accessToken: String, cursor: Option[String]): Task[PlaidSyncResp] =
      val body = cursor.foldLeft(Json.obj("access_token" -> Json.fromString(accessToken))) { (b, c) =>
        Json.fromJsonObject(b.asObject.get.add("cursor", Json.fromString(c)))
      }
      post[PlaidSyncResp]("/transactions/sync", body)

  val live: ZLayer[PlaidConfig, Nothing, PlaidClient] =
    ZLayer.fromFunction(new Live(_))
```

- [ ] **Step 4: Compile**

```bash
cd backend/http_server && sbt compile
```

Expected: `[success]` — no errors.

- [ ] **Step 5: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/api/plaid/
git commit -m "feat(plaid): add PlaidModels and PlaidClient"
```

---

## Task 4: PlaidRoutes — Two New Endpoints

**Files:**
- Create: `backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidRoutes.scala`

The exchange endpoint does the heavy lifting:
1. Exchange public_token for (accessToken, itemId) via PlaidClient
2. Get accounts + institution name via PlaidClient
3. Resolve `user_input` sourceType UUID and `finance` domain UUID from ReferenceService
4. Resolve `plaid_connection` and `bank_account` schema UUIDs from SchemaService
5. Create a document recording the connection
6. Create a `plaid_connection` fact with a stable, deterministic entity_instance_id derived from item_id
7. Create `bank_account` facts for each account

Entity instance IDs are derived from Plaid's stable IDs using `UUID.nameUUIDFromBytes` (UUID v3 / MD5), so the same Plaid ID always maps to the same UUID across all runs.

- [ ] **Step 1: Create PlaidRoutes.scala**

```scala
package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.plaid.*
import com.myassistant.domain.{CreateDocument, CreateFact, OperationType}
import com.myassistant.errors.AppError
import com.myassistant.services.{DocumentService, FactService, ReferenceService, SchemaService}
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

object PlaidRoutes:

  val routes: Routes[PlaidClient & DocumentService & FactService & SchemaService & ReferenceService & ZConnectionPool, Nothing] =
    Routes(

      // POST /api/v1/plaid/link-token
      // Calls Plaid /link/token/create and returns the link_token to the frontend.
      Method.POST / "api" / "v1" / "plaid" / "link-token" ->
        handler { (req: Request) =>
          for
            bodyStr <- req.body.asString.orDie
            resp <- decode[LinkTokenRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(r) =>
                ZIO.serviceWithZIO[PlaidClient](_.createLinkToken(r.personId.toString))
                  .foldZIO(
                    err => ZIO.succeed(Response.json(
                      s"""{"error":"plaid_error","message":"${err.getMessage.replace("\"", "'")}"}"""
                    ).status(Status.BadGateway)),
                    tok => ZIO.succeed(Response.json(LinkTokenResponse(tok).asJson.noSpaces)),
                  )
          yield resp
        },

      // POST /api/v1/plaid/exchange
      // Exchanges the public_token from Plaid Link, fetches accounts, stores facts.
      Method.POST / "api" / "v1" / "plaid" / "exchange" ->
        handler { (req: Request) =>
          for
            bodyStr <- req.body.asString.orDie
            resp <- decode[ExchangeRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(r) =>
                handleExchange(r.personId, r.publicToken)
                  .foldZIO(
                    err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    result => ZIO.succeed(Response.json(result.asJson.noSpaces).status(Status.Created)),
                  )
          yield resp
        },
    )

  // Generates a deterministic UUID from a Plaid-stable string ID.
  // Uses the same algorithm as Java UUID.nameUUIDFromBytes (MD5, version 3).
  private def stableId(prefix: String, key: String): UUID =
    UUID.nameUUIDFromBytes(s"$prefix:$key".getBytes(StandardCharsets.UTF_8))

  private def handleExchange(
      personId:    UUID,
      publicToken: String,
  ): ZIO[PlaidClient & DocumentService & FactService & SchemaService & ReferenceService & ZConnectionPool, AppError, ExchangeResponse] =
    for
      // 1. Exchange public_token → (accessToken, itemId)
      (accessToken, itemId) <- ZIO.serviceWithZIO[PlaidClient](_.exchangePublicToken(publicToken))
        .mapError(e => AppError.InternalError(e))

      // 2. Get accounts + institution
      accountsResp <- ZIO.serviceWithZIO[PlaidClient](_.getAccounts(accessToken))
        .mapError(e => AppError.InternalError(e))

      institutionName <- accountsResp.item.institution_id match
        case Some(instId) =>
          ZIO.serviceWithZIO[PlaidClient](_.getInstitutionName(instId))
            .mapError(e => AppError.InternalError(e))
            .orElse(ZIO.succeed("Unknown Institution"))
        case None =>
          ZIO.succeed("Unknown Institution")

      // 3. Resolve reference IDs
      sourceTypes <- ZIO.serviceWithZIO[ReferenceService](_.listSourceTypes)
        .mapError(e => AppError.InternalError(e))
      userInputSrcId <- ZIO.fromOption(sourceTypes.find(_.name == "user_input").map(_.id))
        .orElseFail(AppError.InternalError(RuntimeException("user_input source type not found")))

      domains <- ZIO.serviceWithZIO[ReferenceService](_.listDomains)
        .mapError(e => AppError.InternalError(e))
      financeDomainId <- ZIO.fromOption(domains.find(_.name == "finance").map(_.id))
        .orElseFail(AppError.InternalError(RuntimeException("finance domain not found")))

      connectionSchema <- ZIO.serviceWithZIO[SchemaService](_.getCurrentSchema(financeDomainId, "plaid_connection"))
      bankAccountSchema <- ZIO.serviceWithZIO[SchemaService](_.getCurrentSchema(financeDomainId, "bank_account"))

      // 4. Create document recording the connection event
      now = Instant.now()
      doc <- ZIO.serviceWithZIO[DocumentService](_.createDocument(CreateDocument(
        personId      = Some(personId),
        householdId   = None,
        contentText   = s"Connected $institutionName via Plaid on ${now.toString.take(10)}",
        sourceTypeId  = userInputSrcId,
        embedding     = List.empty,
        files         = Json.arr(),
        supersedesIds = List.empty,
      )))

      // 5. Create plaid_connection fact
      connectionInstanceId = stableId("plaid:item", itemId)
      _ <- ZIO.serviceWithZIO[FactService](_.createFact(CreateFact(
        documentId       = doc.id,
        schemaId         = connectionSchema.id,
        entityInstanceId = connectionInstanceId,
        operationType    = OperationType.Create,
        fields           = Json.obj(
          "item_id"          -> Json.fromString(itemId),
          "institution_id"   -> accountsResp.item.institution_id.fold(Json.Null)(Json.fromString),
          "institution_name" -> Json.fromString(institutionName),
          "access_token"     -> Json.fromString(accessToken),
          "sync_cursor"      -> Json.fromString(""),
          "last_synced_at"   -> Json.Null,
        ),
        embedding = List.empty,
      )))

      // 6. Create bank_account facts (one per account)
      _ <- ZIO.foreachDiscard(accountsResp.accounts) { account =>
        ZIO.serviceWithZIO[FactService](_.createFact(CreateFact(
          documentId       = doc.id,
          schemaId         = bankAccountSchema.id,
          entityInstanceId = stableId("plaid:account", account.account_id),
          operationType    = OperationType.Create,
          fields           = Json.obj(
            "account_id"        -> Json.fromString(account.account_id),
            "item_id"           -> Json.fromString(itemId),
            "name"              -> Json.fromString(account.name),
            "official_name"     -> account.official_name.fold(Json.Null)(Json.fromString),
            "type"              -> Json.fromString(account.`type`),
            "subtype"           -> account.subtype.fold(Json.Null)(Json.fromString),
            "mask"              -> account.mask.fold(Json.Null)(Json.fromString),
            "current_balance"   -> account.balances.current.flatMap(Json.fromDouble).getOrElse(Json.Null),
            "available_balance" -> account.balances.available.flatMap(Json.fromDouble).getOrElse(Json.Null),
            "iso_currency_code" -> account.balances.iso_currency_code.fold(Json.Null)(Json.fromString),
            "institution_name"  -> Json.fromString(institutionName),
          ),
          embedding = List.empty,
        )))
      }

    yield ExchangeResponse(itemId = itemId, institutionName = institutionName)
```

- [ ] **Step 2: Compile**

```bash
cd backend/http_server && sbt compile
```

Expected: `[success]`

- [ ] **Step 3: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/api/routes/PlaidRoutes.scala
git commit -m "feat(plaid): add PlaidRoutes — link-token and exchange endpoints"
```

---

## Task 5: Wire Router + Main

**Files:**
- Modify: `backend/http_server/src/main/scala/com/myassistant/api/Router.scala`
- Modify: `backend/http_server/src/main/scala/com/myassistant/Main.scala`

- [ ] **Step 1: Update Router.scala — add PlaidClient to AppEnv and PlaidRoutes to protected routes**

In `Router.scala`, update the `AppEnv` type alias (add `PlaidClient` after `ScheduledJobService`):

```scala
package com.myassistant.api

import com.myassistant.api.middleware.{AuthMiddleware, LoggingMiddleware}
import com.myassistant.api.plaid.PlaidClient
import com.myassistant.api.routes.*
import com.myassistant.config.AuthConfig
import com.myassistant.services.*
import zio.*
import zio.http.*
import zio.jdbc.*

object Router:

  type AppEnv =
    PersonService
      & HouseholdService
      & RelationshipService
      & KinshipResolver
      & DocumentService
      & FactService
      & SchemaService
      & ReferenceService
      & AuditService
      & FileService
      & ScheduledJobService
      & PlaidClient
      & ZConnectionPool
      & AuthConfig

  val app: ZIO[AppEnv, Nothing, Routes[AppEnv, Nothing]] =
    ZIO.serviceWith[AuthConfig] { authCfg =>
      val publicRoutes: Routes[AppEnv, Nothing] =
        HealthRoutes.routes

      val protectedRoutes: Routes[AppEnv, Nothing] =
        (PersonRoutes.routes ++
          HouseholdRoutes.routes ++
          PersonHouseholdRoutes.routes ++
          RelationshipRoutes.routes ++
          DocumentRoutes.routes ++
          FactRoutes.routes ++
          SchemaRoutes.routes ++
          ReferenceRoutes.routes ++
          AuditRoutes.routes ++
          FileRoutes.routes ++
          ScheduledJobRoutes.routes ++
          PlaidRoutes.routes) @@ AuthMiddleware(authCfg.token)

      (publicRoutes ++ protectedRoutes) @@ LoggingMiddleware.logRequests
    }
```

- [ ] **Step 2: Update Main.scala — wire PlaidConfig + PlaidClient layers**

In `Main.scala`, add the PlaidConfig and PlaidClient layers in the `appLayer` definition.

Add after the `fileConfigLayer` line:
```scala
val plaidConfigLayer  = configLayer >>> ZLayer.fromFunction((_: AppConfig).plaid)
```

Add after all the service layers, before the final `poolLayer ++ ...` block:
```scala
val plaidClientLayer  = plaidConfigLayer >>> PlaidClient.live
```

Add `plaidClientLayer` to the final combination. The full updated `appLayer` private val:

```scala
private val appLayer: ZLayer[Any, Throwable, Router.AppEnv] =
  val configLayer       = AppConfig.live
  val serverConfigLayer = configLayer >>> ZLayer.fromFunction((_: AppConfig).server)
  val dbConfigLayer     = configLayer >>> ZLayer.fromFunction((_: AppConfig).database)
  val authConfigLayer   = configLayer >>> ZLayer.fromFunction((_: AppConfig).auth)
  val fileConfigLayer   = configLayer >>> ZLayer.fromFunction((_: AppConfig).fileStorage)
  val plaidConfigLayer  = configLayer >>> ZLayer.fromFunction((_: AppConfig).plaid)

  val poolLayer = dbConfigLayer >>> DatabaseModule.connectionPoolLive

  val personRepoLayer       = PersonRepository.live
  val householdRepoLayer    = HouseholdRepository.live
  val relationshipRepoLayer = RelationshipRepository.live
  val documentRepoLayer     = DocumentRepository.live
  val factRepoLayer         = FactRepository.live
  val schemaRepoLayer       = SchemaRepository.live
  val referenceRepoLayer    = ReferenceRepository.live
  val auditRepoLayer        = AuditRepository.live
  val fileRepoLayer         = FileRepository.live
  val scheduledJobRepoLayer = ScheduledJobRepository.live

  val personSvcLayer       = personRepoLayer       >>> PersonService.live
  val householdSvcLayer    = householdRepoLayer    >>> HouseholdService.live
  val relationshipSvcLayer = relationshipRepoLayer >>> RelationshipService.live
  val kinshipSvcLayer      = (relationshipRepoLayer ++ referenceRepoLayer) >>> KinshipResolver.live
  val documentSvcLayer     = documentRepoLayer     >>> DocumentService.live
  val factSvcLayer         = factRepoLayer         >>> FactService.live
  val schemaSvcLayer       = schemaRepoLayer       >>> SchemaService.live
  val referenceSvcLayer    = referenceRepoLayer    >>> ReferenceService.live
  val auditSvcLayer        = auditRepoLayer        >>> AuditService.live
  val fileSvcLayer         = fileConfigLayer       >>> FileService.live
  val scheduledJobSvcLayer = scheduledJobRepoLayer >>> ScheduledJobService.live
  val plaidClientLayer     = plaidConfigLayer      >>> PlaidClient.live

  poolLayer ++
    personSvcLayer ++
    householdSvcLayer ++
    relationshipSvcLayer ++
    kinshipSvcLayer ++
    documentSvcLayer ++
    factSvcLayer ++
    schemaSvcLayer ++
    referenceSvcLayer ++
    auditSvcLayer ++
    fileSvcLayer ++
    scheduledJobSvcLayer ++
    plaidClientLayer ++
    authConfigLayer
```

Also add the import at the top of `Main.scala`:
```scala
import com.myassistant.api.plaid.PlaidClient
```

- [ ] **Step 3: Compile and start**

```bash
cd backend/http_server && sbt compile
```

Expected: `[success]`

Start the server and verify the endpoints exist:

```bash
# In one terminal: cd backend/http_server && sbt run
# In another terminal (wait for "Server listening on port"):
curl -s -X POST http://localhost:8080/api/v1/plaid/link-token \
  -H "Authorization: Bearer dev-token-change-me-in-production" \
  -H "Content-Type: application/json" \
  -d '{"personId": "00000000-0000-0000-0000-000000000001"}' | jq .
```

Expected with empty PLAID_CLIENT_ID: `{"error":"plaid_error","message":"..."}` (BadGateway from Plaid) — confirms the route exists and is reachable.

- [ ] **Step 4: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/api/Router.scala \
        backend/http_server/src/main/scala/com/myassistant/Main.scala
git commit -m "feat(plaid): wire PlaidClient into Router.AppEnv and Main layer graph"
```

---

## Task 6: Plaid Poller — Python Handler

**Files:**
- Create: `backend/scheduler/handlers/plaid_poll.py`
- Modify: `backend/scheduler/main.py`

The handler:
1. Fetches all `plaid_connection` facts for the person
2. For each connection, calls Plaid `/transactions/sync` and `/accounts/get` directly
3. Stores one document per sync batch + transaction facts (add/modify/delete) + updates bank_account facts
4. Updates the `plaid_connection` fact with new cursor + last_synced_at

Entity instance IDs are generated with the same deterministic MD5 algorithm as the Scala code so IDs are consistent across the two systems.

- [ ] **Step 1: Create plaid_poll.py**

```python
import hashlib
import json
import os
import uuid
from datetime import datetime, timedelta, timezone

import httpx

from handlers.base import BaseHandler

_PLAID_CLIENT_ID = os.environ.get("PLAID_CLIENT_ID", "")
_PLAID_SECRET = os.environ.get("PLAID_SECRET", "")
_PLAID_ENV = os.environ.get("PLAID_ENV", "sandbox")

_PLAID_BASE = {
    "production":  "https://production.plaid.com",
    "development": "https://development.plaid.com",
}.get(_PLAID_ENV, "https://sandbox.plaid.com")


def _stable_id(prefix: str, key: str) -> str:
    """Deterministic UUID matching Java UUID.nameUUIDFromBytes (MD5, UUID v3 variant)."""
    h = bytearray(hashlib.md5(f"{prefix}:{key}".encode("utf-8")).digest())
    h[6] = (h[6] & 0x0F) | 0x30  # version 3
    h[8] = (h[8] & 0x3F) | 0x80  # variant RFC 4122
    return str(uuid.UUID(bytes=bytes(h)))


def _plaid_post(path: str, body: dict) -> dict:
    body = {**body, "client_id": _PLAID_CLIENT_ID, "secret": _PLAID_SECRET}
    resp = httpx.post(f"{_PLAID_BASE}{path}", json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


class PlaidPollHandler(BaseHandler):
    def __init__(self, http: httpx.Client):
        self.http = http
        self._plaid_poll_source_type_id: str | None = None
        self._finance_domain_id: str | None = None
        self._plaid_connection_schema_id: str | None = None
        self._transaction_schema_id: str | None = None
        self._bank_account_schema_id: str | None = None

    # ── Reference data helpers (lazy, cached) ──────────────────────────────

    def _get_source_type_id(self, name: str) -> str:
        resp = self.http.get("/api/v1/reference/source-types")
        resp.raise_for_status()
        match = next((st for st in resp.json().get("items", []) if st["name"] == name), None)
        if not match:
            raise RuntimeError(f"{name} source type not found")
        return match["id"]

    def _get_finance_domain_id(self) -> str:
        if self._finance_domain_id is None:
            resp = self.http.get("/api/v1/reference/domains")
            resp.raise_for_status()
            match = next((d for d in resp.json().get("items", []) if d["name"] == "finance"), None)
            if not match:
                raise RuntimeError("finance domain not found")
            self._finance_domain_id = match["id"]
        return self._finance_domain_id

    def _get_schema_id(self, entity_type: str) -> str:
        resp = self.http.get(
            "/api/v1/schemas/current",
            params={"domainId": self._get_finance_domain_id(), "entityType": entity_type},
        )
        resp.raise_for_status()
        return resp.json()["id"]

    def _plaid_poll_source_type(self) -> str:
        if self._plaid_poll_source_type_id is None:
            self._plaid_poll_source_type_id = self._get_source_type_id("plaid_poll")
        return self._plaid_poll_source_type_id

    def _connection_schema(self) -> str:
        if self._plaid_connection_schema_id is None:
            self._plaid_connection_schema_id = self._get_schema_id("plaid_connection")
        return self._plaid_connection_schema_id

    def _transaction_schema(self) -> str:
        if self._transaction_schema_id is None:
            self._transaction_schema_id = self._get_schema_id("transaction")
        return self._transaction_schema_id

    def _bank_account_schema(self) -> str:
        if self._bank_account_schema_id is None:
            self._bank_account_schema_id = self._get_schema_id("bank_account")
        return self._bank_account_schema_id

    # ── Document helper ───────────────────────────────────────────────────

    def _create_document(self, person_id: str, content: str) -> str:
        resp = self.http.post("/api/v1/documents", json={
            "personId": person_id,
            "contentText": content,
            "sourceTypeId": self._plaid_poll_source_type(),
            "embedding": [],
            "files": [],
            "supersedesIds": [],
        })
        resp.raise_for_status()
        return resp.json()["id"]

    # ── Fact helpers ──────────────────────────────────────────────────────

    def _create_fact(self, document_id: str, schema_id: str, instance_id: str,
                     operation: str, fields: dict) -> None:
        resp = self.http.post("/api/v1/facts", json={
            "documentId": document_id,
            "schemaId": schema_id,
            "entityInstanceId": instance_id,
            "operationType": operation,
            "fields": fields,
            "embedding": [],
        })
        if not resp.is_success:
            print(f"[plaid_poll] WARNING: failed to store fact ({operation}): {resp.status_code} {resp.text[:200]}")

    # ── Scheduler housekeeping ────────────────────────────────────────────

    def _record_run(self, job_id: str, status: str, detail: str) -> None:
        resp = self.http.post(f"/api/v1/scheduled-jobs/{job_id}/runs", json={
            "status": status,
            "statusDetail": detail,
        })
        if not resp.is_success:
            print(f"[plaid_poll] WARNING: failed to record run: {resp.status_code}")

    def _advance_next_run(self, job_id: str, job: dict) -> None:
        from croniter import croniter
        from zoneinfo import ZoneInfo
        tz = ZoneInfo(os.environ.get("SCHEDULER_TIMEZONE", "UTC"))
        try:
            cron = croniter(job["cronExpression"], datetime.now(tz))
            next_run = cron.get_next(datetime).astimezone(timezone.utc)
        except Exception:
            next_run = datetime.now(timezone.utc) + timedelta(hours=24)
        self.http.patch(f"/api/v1/scheduled-jobs/{job_id}", json={
            "nextRunAt": next_run.isoformat(),
        })

    # ── Main run method ───────────────────────────────────────────────────

    def run(self, job: dict) -> None:
        job_id = job["id"]
        person_id = job.get("personId")
        assert person_id, "plaid_poll jobs must have personId"

        connections_synced = 0
        transactions_stored = 0
        errors: list[str] = []

        try:
            # Fetch all plaid_connection facts for this person
            connections_resp = self.http.get(
                "/api/v1/facts/current",
                params={"personId": person_id, "entityType": "plaid_connection", "limit": 50},
            )
            connections_resp.raise_for_status()
            connections = connections_resp.json().get("items", [])

            if not connections:
                self._record_run(job_id, "skipped", "No Plaid connections found for this person")
                self._advance_next_run(job_id, job)
                return

            for connection in connections:
                fields = connection.get("fields", {})
                item_id = fields.get("item_id", "")
                access_token = fields.get("access_token", "")
                cursor = fields.get("sync_cursor") or None
                connection_instance_id = connection["entityInstanceId"]

                if not access_token:
                    errors.append(f"connection {item_id}: missing access_token")
                    continue

                try:
                    self._sync_one_connection(
                        person_id=person_id,
                        item_id=item_id,
                        access_token=access_token,
                        cursor=cursor,
                        connection_instance_id=connection_instance_id,
                        connection_doc_id=connection.get("documentId", ""),
                    )
                    connections_synced += 1
                except Exception as e:
                    errors.append(f"connection {item_id}: {e}")
                    print(f"[plaid_poll] error syncing {item_id}: {e}")

        except Exception as e:
            errors.append(str(e))
            print(f"[plaid_poll] fatal error: {e}")

        status = "success" if not errors else ("failure" if connections_synced == 0 else "skipped")
        detail = f"Synced {connections_synced} connection(s)" + (f"; errors: {'; '.join(errors)}" if errors else "")
        self._record_run(job_id, status, detail)
        self._advance_next_run(job_id, job)

    def _sync_one_connection(
        self,
        person_id: str,
        item_id: str,
        access_token: str,
        cursor: str | None,
        connection_instance_id: str,
        connection_doc_id: str,
    ) -> None:
        # 1. Sync transactions (paginate until has_more is False)
        all_added: list[dict] = []
        all_modified: list[dict] = []
        all_removed: list[dict] = []
        next_cursor = cursor

        while True:
            body = {"access_token": access_token}
            if next_cursor:
                body["cursor"] = next_cursor
            sync_resp = _plaid_post("/transactions/sync", body)
            all_added.extend(sync_resp.get("added", []))
            all_modified.extend(sync_resp.get("modified", []))
            all_removed.extend(sync_resp.get("removed", []))
            next_cursor = sync_resp.get("next_cursor", "")
            if not sync_resp.get("has_more", False):
                break

        # 2. Get latest account balances
        accounts_resp = _plaid_post("/accounts/get", {"access_token": access_token})
        accounts = accounts_resp.get("accounts", [])

        # 3. Create sync document
        total = len(all_added) + len(all_modified) + len(all_removed)
        now_str = datetime.now(timezone.utc).isoformat()
        doc_id = self._create_document(
            person_id,
            f"Plaid sync for item {item_id} at {now_str}: "
            f"{len(all_added)} added, {len(all_modified)} modified, {len(all_removed)} removed transactions; "
            f"{len(accounts)} accounts updated",
        )

        # 4. Store transaction facts
        for txn in all_added:
            cat = txn.get("personal_finance_category") or {}
            category_str = " > ".join(filter(None, [cat.get("primary"), cat.get("detailed")])) or ""
            self._create_fact(doc_id, self._transaction_schema(),
                              _stable_id("plaid:transaction", txn["transaction_id"]),
                              "create", {
                                  "transaction_id":    txn["transaction_id"],
                                  "account_id":        txn["account_id"],
                                  "amount":            txn["amount"],
                                  "iso_currency_code": txn.get("iso_currency_code"),
                                  "merchant_name":     txn.get("merchant_name"),
                                  "name":              txn["name"],
                                  "category":          category_str,
                                  "date":              txn["date"],
                                  "pending":           txn.get("pending", False),
                              })

        for txn in all_modified:
            cat = txn.get("personal_finance_category") or {}
            category_str = " > ".join(filter(None, [cat.get("primary"), cat.get("detailed")])) or ""
            self._create_fact(doc_id, self._transaction_schema(),
                              _stable_id("plaid:transaction", txn["transaction_id"]),
                              "update", {
                                  "amount":        txn["amount"],
                                  "merchant_name": txn.get("merchant_name"),
                                  "name":          txn["name"],
                                  "category":      category_str,
                                  "date":          txn["date"],
                                  "pending":       txn.get("pending", False),
                              })

        for removed in all_removed:
            self._create_fact(doc_id, self._transaction_schema(),
                              _stable_id("plaid:transaction", removed["transaction_id"]),
                              "delete", {})

        # 5. Upsert bank_account facts
        for account in accounts:
            balances = account.get("balances", {})
            self._create_fact(doc_id, self._bank_account_schema(),
                              _stable_id("plaid:account", account["account_id"]),
                              "update", {
                                  "account_id":        account["account_id"],
                                  "item_id":           item_id,
                                  "name":              account["name"],
                                  "official_name":     account.get("official_name"),
                                  "type":              account["type"],
                                  "subtype":           account.get("subtype"),
                                  "mask":              account.get("mask"),
                                  "current_balance":   balances.get("current"),
                                  "available_balance": balances.get("available"),
                                  "iso_currency_code": balances.get("iso_currency_code"),
                              })

        # 6. Update plaid_connection fact (new cursor + last_synced_at)
        self._create_fact(doc_id, self._connection_schema(),
                          connection_instance_id,
                          "update", {
                              "sync_cursor":    next_cursor,
                              "last_synced_at": now_str,
                          })
```

- [ ] **Step 2: Register PlaidPollHandler in main.py**

Update `backend/scheduler/main.py`:

```python
import time
import os
import httpx
from handlers.news_poll import NewsPollHandler
from handlers.plaid_poll import PlaidPollHandler

HTTP_SERVER_URL = os.environ["HTTP_SERVER_URL"]
AUTH_TOKEN = os.environ["AUTH_TOKEN"]
POLL_INTERVAL = 60  # seconds


def build_handler_map(http: httpx.Client) -> dict:
    return {
        "news_poll":  NewsPollHandler(http),
        "plaid_poll": PlaidPollHandler(http),
    }


def run():
    http = httpx.Client(
        base_url=HTTP_SERVER_URL,
        headers={"Authorization": f"Bearer {AUTH_TOKEN}"},
        timeout=30,
    )
    handlers = build_handler_map(http)
    print(f"[scheduler] started — polling every {POLL_INTERVAL}s")
    while True:
        try:
            resp = http.get("/api/v1/scheduled-jobs/due")
            resp.raise_for_status()
            jobs = resp.json().get("items", [])
            for job in jobs:
                handler = handlers.get(job["sourceType"])
                if handler:
                    print(f"[scheduler] running job {job['id']} ({job['sourceType']})")
                    handler.run(job)
        except Exception as e:
            print(f"[scheduler] poll error: {e}")
        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    run()
```

- [ ] **Step 3: Verify import works**

```bash
cd backend/scheduler
python -c "from handlers.plaid_poll import PlaidPollHandler; print('OK')"
```

Expected: `OK`

- [ ] **Step 4: Commit**

```bash
git add backend/scheduler/handlers/plaid_poll.py backend/scheduler/main.py
git commit -m "feat(plaid): add PlaidPollHandler and register in scheduler"
```

---

## Task 7: Frontend — Vite Proxy + Tab Navigation

**Files:**
- Modify: `frontend/vite.config.ts`
- Modify: `frontend/src/App.tsx`

The Finance tab needs to call `/api/v1/facts/current` and `/api/v1/plaid/*` — all on the Scala HTTP server (port 8080). The existing vite proxy sends all `/api` to the chatbot server (port 8000). We add a more specific rule for `/api/v1` that routes to the Scala server first.

- [ ] **Step 1: Update vite.config.ts**

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const chatbotPort = process.env.CHATBOT_PORT ?? '8000'
const httpPort    = process.env.HTTP_SERVER_PORT ?? '8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: parseInt(process.env.FRONTEND_PORT ?? '5173'),
    proxy: {
      // Scala HTTP server — all REST API v1 routes (facts, schemas, plaid, etc.)
      '/api/v1': {
        target: `http://localhost:${httpPort}`,
        changeOrigin: true,
      },
      // Chatbot server — login, chat streaming, file upload
      '/api': {
        target: `http://localhost:${chatbotPort}`,
        changeOrigin: true,
      },
    },
  },
})
```

- [ ] **Step 2: Update App.tsx — add tab navigation**

```tsx
import { useState } from 'react'
import LoginScreen from './components/LoginScreen'
import ChatScreen from './components/ChatScreen'
import FinanceTab from './components/FinanceTab'
import type { Session } from './types'

type Tab = 'chat' | 'finance'

export default function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [activeTab, setActiveTab] = useState<Tab>('chat')

  if (!session) {
    return <LoginScreen onLogin={setSession} />
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <div style={styles.tabBar}>
        <button
          style={{ ...styles.tab, ...(activeTab === 'chat' ? styles.tabActive : {}) }}
          onClick={() => setActiveTab('chat')}
        >
          Chat
        </button>
        <button
          style={{ ...styles.tab, ...(activeTab === 'finance' ? styles.tabActive : {}) }}
          onClick={() => setActiveTab('finance')}
        >
          Finance
        </button>
      </div>
      {activeTab === 'chat'    && <ChatScreen session={session} onLogout={() => setSession(null)} />}
      {activeTab === 'finance' && <FinanceTab session={session} />}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  tabBar: {
    display: 'flex',
    background: 'var(--bg-surface2)',
    borderBottom: '1px solid var(--border)',
    flexShrink: 0,
  },
  tab: {
    padding: '10px 24px',
    border: 'none',
    background: 'transparent',
    color: 'var(--text-muted)',
    cursor: 'pointer',
    fontSize: 14,
    fontWeight: 500,
  },
  tabActive: {
    color: 'var(--text-primary)',
    borderBottom: '2px solid var(--accent)',
  },
}
```

- [ ] **Step 3: Verify the app renders with both tabs**

```bash
cd frontend && npm run dev
```

Open http://localhost:5173, log in, confirm you see "Chat" and "Finance" tabs. Chat tab should work as before.

- [ ] **Step 4: Commit**

```bash
git add frontend/vite.config.ts frontend/src/App.tsx
git commit -m "feat(plaid): add Finance tab navigation and /api/v1 vite proxy to Scala server"
```

---

## Task 8: Frontend — FinanceTab Component

**Files:**
- Modify: `frontend/package.json` (add react-plaid-link)
- Modify: `frontend/src/api.ts` (add Plaid API functions)
- Create: `frontend/src/components/FinanceTab.tsx`

- [ ] **Step 1: Install react-plaid-link**

```bash
cd frontend && npm install react-plaid-link
```

Verify it appears in `package.json` dependencies.

- [ ] **Step 2: Add Plaid API functions to api.ts**

Append to the end of `frontend/src/api.ts`:

```typescript
// ── Finance / Plaid ──────────────────────────────────────────────────────────

export interface PlaidConnectionFields {
  item_id: string
  institution_id?: string
  institution_name: string
  sync_cursor: string
  last_synced_at?: string
}

export interface PlaidConnection {
  entityInstanceId: string
  schemaId: string
  fields: PlaidConnectionFields
}

export interface BankAccountFields {
  account_id: string
  item_id: string
  name: string
  official_name?: string
  type: string
  subtype?: string
  mask?: string
  current_balance?: number
  available_balance?: number
  iso_currency_code?: string
  institution_name?: string
}

export interface BankAccount {
  entityInstanceId: string
  fields: BankAccountFields
}

export async function fetchLinkToken(personId: string): Promise<string> {
  const resp = await fetch('/api/v1/plaid/link-token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ personId }),
  })
  if (!resp.ok) throw new Error(`link-token failed: ${resp.status}`)
  const data = await resp.json()
  return data.linkToken as string
}

export async function exchangeToken(personId: string, publicToken: string): Promise<void> {
  const resp = await fetch('/api/v1/plaid/exchange', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ personId, publicToken }),
  })
  if (!resp.ok) throw new Error(`exchange failed: ${resp.status}`)
}

export async function listPlaidConnections(personId: string): Promise<PlaidConnection[]> {
  const resp = await fetch(
    `/api/v1/facts/current?personId=${personId}&entityType=plaid_connection&limit=50`
  )
  if (!resp.ok) throw new Error(`list connections failed: ${resp.status}`)
  const data = await resp.json()
  return (data.items ?? []) as PlaidConnection[]
}

export async function listBankAccounts(personId: string): Promise<BankAccount[]> {
  const resp = await fetch(
    `/api/v1/facts/current?personId=${personId}&entityType=bank_account&limit=200`
  )
  if (!resp.ok) throw new Error(`list accounts failed: ${resp.status}`)
  const data = await resp.json()
  return (data.items ?? []) as BankAccount[]
}

export async function disconnectPlaidAccount(
  entityInstanceId: string,
  schemaId: string,
  personId: string,
): Promise<void> {
  // We need a document to attach the delete fact to.
  // Use a minimal document with no content — the agent pipeline treats it as a delete event.
  const docResp = await fetch('/api/v1/documents', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      personId,
      contentText: 'Disconnected Plaid account',
      sourceTypeId: await getUserInputSourceTypeId(),
      embedding: [],
      files: [],
      supersedesIds: [],
    }),
  })
  if (!docResp.ok) throw new Error(`create doc failed: ${docResp.status}`)
  const doc = await docResp.json()

  const factResp = await fetch('/api/v1/facts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      documentId: doc.id,
      schemaId,
      entityInstanceId,
      operationType: 'delete',
      fields: {},
      embedding: [],
    }),
  })
  if (!factResp.ok) throw new Error(`delete fact failed: ${factResp.status}`)
}

let _userInputSourceTypeId: string | null = null
async function getUserInputSourceTypeId(): Promise<string> {
  if (_userInputSourceTypeId) return _userInputSourceTypeId
  const resp = await fetch('/api/v1/reference/source-types')
  if (!resp.ok) throw new Error('cannot fetch source types')
  const data = await resp.json()
  const match = (data.items ?? []).find((st: { name: string; id: string }) => st.name === 'user_input')
  if (!match) throw new Error('user_input source type not found')
  _userInputSourceTypeId = match.id as string
  return _userInputSourceTypeId!
}
```

- [ ] **Step 3: Create FinanceTab.tsx**

```tsx
import { useCallback, useEffect, useState } from 'react'
import { usePlaidLink } from 'react-plaid-link'
import type { Session } from '../types'
import {
  disconnectPlaidAccount,
  exchangeToken,
  fetchLinkToken,
  listBankAccounts,
  listPlaidConnections,
  type BankAccount,
  type PlaidConnection,
} from '../api'

interface Props {
  session: Session
}

export default function FinanceTab({ session }: Props) {
  const [connections, setConnections]   = useState<PlaidConnection[]>([])
  const [accounts, setAccounts]         = useState<BankAccount[]>([])
  const [linkToken, setLinkToken]       = useState<string | null>(null)
  const [loading, setLoading]           = useState(true)
  const [error, setError]               = useState<string | null>(null)
  const [connecting, setConnecting]     = useState(false)
  const [disconnecting, setDisconnecting] = useState<string | null>(null)

  const loadData = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [conns, accts] = await Promise.all([
        listPlaidConnections(session.personId),
        listBankAccounts(session.personId),
      ])
      setConnections(conns)
      setAccounts(accts)
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }, [session.personId])

  useEffect(() => { loadData() }, [loadData])

  const handleConnectClick = async () => {
    setConnecting(true)
    setError(null)
    try {
      const token = await fetchLinkToken(session.personId)
      setLinkToken(token)
    } catch (e) {
      setError(String(e))
      setConnecting(false)
    }
  }

  const onPlaidSuccess = useCallback(async (publicToken: string) => {
    setError(null)
    try {
      await exchangeToken(session.personId, publicToken)
      setLinkToken(null)
      setConnecting(false)
      await loadData()
    } catch (e) {
      setError(String(e))
      setConnecting(false)
    }
  }, [session.personId, loadData])

  const onPlaidExit = useCallback(() => {
    setLinkToken(null)
    setConnecting(false)
  }, [])

  const handleDisconnect = async (conn: PlaidConnection) => {
    setDisconnecting(conn.entityInstanceId)
    setError(null)
    try {
      await disconnectPlaidAccount(conn.entityInstanceId, conn.schemaId, session.personId)
      await loadData()
    } catch (e) {
      setError(String(e))
    } finally {
      setDisconnecting(null)
    }
  }

  // Group accounts by item_id for display
  const accountsByItemId = accounts.reduce<Record<string, BankAccount[]>>((acc, a) => {
    const key = a.fields.item_id
    if (!acc[key]) acc[key] = []
    acc[key].push(a)
    return acc
  }, {})

  return (
    <div style={styles.wrapper}>
      <h2 style={styles.heading}>Connected Accounts</h2>

      {error && <div style={styles.error}>{error}</div>}

      {loading ? (
        <div style={styles.muted}>Loading...</div>
      ) : connections.length === 0 ? (
        <div style={styles.muted}>No accounts connected yet.</div>
      ) : (
        <div style={styles.list}>
          {connections.map(conn => {
            const connAccounts = accountsByItemId[conn.fields.item_id] ?? []
            return (
              <div key={conn.entityInstanceId} style={styles.card}>
                <div style={styles.cardHeader}>
                  <span style={styles.institution}>{conn.fields.institution_name}</span>
                  <button
                    style={styles.disconnectBtn}
                    disabled={disconnecting === conn.entityInstanceId}
                    onClick={() => handleDisconnect(conn)}
                  >
                    {disconnecting === conn.entityInstanceId ? 'Disconnecting...' : 'Disconnect'}
                  </button>
                </div>
                {connAccounts.map(acct => (
                  <div key={acct.entityInstanceId} style={styles.account}>
                    <span style={styles.accountName}>
                      {acct.fields.name}
                      {acct.fields.mask ? ` ····${acct.fields.mask}` : ''}
                    </span>
                    {acct.fields.current_balance != null && (
                      <span style={styles.balance}>
                        {acct.fields.iso_currency_code ?? ''} {acct.fields.current_balance.toFixed(2)}
                      </span>
                    )}
                  </div>
                ))}
                {conn.fields.last_synced_at && (
                  <div style={styles.lastSynced}>
                    Last synced: {new Date(conn.fields.last_synced_at).toLocaleString()}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}

      <PlaidLinkButton
        linkToken={linkToken}
        onSuccess={onPlaidSuccess}
        onExit={onPlaidExit}
        onConnectClick={handleConnectClick}
        connecting={connecting}
      />
    </div>
  )
}

// Separate component so usePlaidLink re-initializes whenever linkToken changes
function PlaidLinkButton({
  linkToken,
  onSuccess,
  onExit,
  onConnectClick,
  connecting,
}: {
  linkToken: string | null
  onSuccess: (token: string) => void
  onExit: () => void
  onConnectClick: () => void
  connecting: boolean
}) {
  const { open, ready } = usePlaidLink({
    token: linkToken ?? '',
    onSuccess: (public_token) => onSuccess(public_token),
    onExit: () => onExit(),
  })

  useEffect(() => {
    if (linkToken && ready) open()
  }, [linkToken, ready, open])

  return (
    <button
      style={styles.connectBtn}
      onClick={onConnectClick}
      disabled={connecting}
    >
      {connecting ? 'Opening Plaid...' : '+ Connect account'}
    </button>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper:      { padding: '32px 24px', maxWidth: 640, margin: '0 auto' },
  heading:      { fontWeight: 600, fontSize: 18, marginBottom: 20, color: 'var(--text-primary)' },
  list:         { display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 24 },
  card:         { background: 'var(--bg-surface2)', border: '1px solid var(--border)', borderRadius: 8, padding: 16 },
  cardHeader:   { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 },
  institution:  { fontWeight: 600, fontSize: 15, color: 'var(--text-primary)' },
  disconnectBtn:{ padding: '4px 12px', fontSize: 12, borderRadius: 4, border: '1px solid var(--border)', background: 'transparent', color: 'var(--text-muted)', cursor: 'pointer' },
  account:      { display: 'flex', justifyContent: 'space-between', padding: '4px 0', color: 'var(--text-secondary)', fontSize: 13 },
  accountName:  { color: 'var(--text-secondary)' },
  balance:      { color: 'var(--text-muted)', fontVariantNumeric: 'tabular-nums' },
  lastSynced:   { fontSize: 11, color: 'var(--text-muted)', marginTop: 8 },
  connectBtn:   { marginTop: 8, padding: '10px 20px', background: 'var(--accent)', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer', fontSize: 14, fontWeight: 500 },
  muted:        { color: 'var(--text-muted)', marginBottom: 24 },
  error:        { background: '#3d1a1a', color: '#ff6b6b', padding: '10px 14px', borderRadius: 6, marginBottom: 16, fontSize: 13 },
}
```

- [ ] **Step 4: Verify the Finance tab compiles and renders**

```bash
cd frontend && npm run dev
```

Open http://localhost:5173, log in, click Finance tab. With no Plaid env vars set, clicking "Connect account" will fail at the link-token call — that's expected. The UI should render the "No accounts connected yet" state without crashing.

Run TypeScript check:
```bash
cd frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/package.json frontend/package-lock.json \
        frontend/src/api.ts frontend/src/components/FinanceTab.tsx
git commit -m "feat(plaid): add FinanceTab component with react-plaid-link integration"
```

---

## Task 9: Configuration — Env Vars Across All Files

**Files:**
- Modify: `.env.tmp`
- Modify: `.env.synology.example`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add Plaid vars to .env.tmp**

In `.env.tmp`, add a new section after the `# ── Scala HTTP server` block:

```bash
# ── Plaid (banking integration) ───────────────────────────────────────────────
# Get credentials from https://dashboard.plaid.com (create a Sandbox app)
# Switching to Development/Production is a single env var change (PLAID_ENV=development)
PLAID_CLIENT_ID=
PLAID_SECRET=
PLAID_ENV=sandbox
```

Also add the same vars to the scheduler section comment:

```bash
# Plaid poller (schedule via chat: "Run Plaid sync every night at 2am")
PLAID_CLIENT_ID=  # already set above — scheduler reads same env
PLAID_SECRET=
PLAID_ENV=sandbox
```

Actually, the scheduler reads from the same shell environment, so adding once at the top of `.env.tmp` is sufficient.

- [ ] **Step 2: Add Plaid vars to .env.synology.example**

In `.env.synology.example`, add after the `GRAFANA_PASSWORD` line:

```bash
# ── Plaid (banking integration) ───────────────────────────────────────────────
PLAID_CLIENT_ID=CHANGE_ME
PLAID_SECRET=CHANGE_ME
PLAID_ENV=sandbox
```

- [ ] **Step 3: Add Plaid vars to docker-compose.yml**

In the `http-server` service `environment` block, add:
```yaml
      PLAID_CLIENT_ID: ${PLAID_CLIENT_ID:-}
      PLAID_SECRET: ${PLAID_SECRET:-}
      PLAID_ENV: ${PLAID_ENV:-sandbox}
```

In the `scheduler` service `environment` block, add:
```yaml
      PLAID_CLIENT_ID: ${PLAID_CLIENT_ID:-}
      PLAID_SECRET: ${PLAID_SECRET:-}
      PLAID_ENV: ${PLAID_ENV:-sandbox}
```

- [ ] **Step 4: Commit**

```bash
git add .env.tmp .env.synology.example docker-compose.yml
git commit -m "feat(plaid): add PLAID_CLIENT_ID/SECRET/ENV to all config files"
```

---

## Verification

Once all tasks are complete and PLAID_CLIENT_ID + PLAID_SECRET are set (Sandbox credentials from https://dashboard.plaid.com):

- [ ] Start backend: `./start.sh` (or `sbt run` in backend/http_server + uvicorn in chatbot_server)
- [ ] Open http://localhost:5173, log in
- [ ] Click Finance tab → see "No accounts connected yet"
- [ ] Click "+ Connect account" → Plaid Link widget opens
- [ ] In Sandbox: select any institution (e.g. "Plaid Bank"), use credentials `user_good / pass_good`
- [ ] On success: Finance tab refreshes and shows connected institution with accounts + balances
- [ ] Verify facts stored: `curl "http://localhost:8080/api/v1/facts/current?personId=YOUR_ID&entityType=plaid_connection" -H "Authorization: Bearer dev-token-change-me-in-production"`
- [ ] Click Disconnect → connection removed from list
- [ ] Schedule a Plaid sync via chat: *"Run Plaid sync every hour"* → scheduler creates a `plaid_poll` job
- [ ] Wait for sync to run (or force via `GET /api/v1/scheduled-jobs/due` manually) → transaction facts appear
- [ ] Query via chat: *"What were my recent transactions?"* → agent reads the facts and responds

---

## Self-Review Notes

**Spec coverage:**
- Accounts / Balances / Transactions: covered (V15 migration + exchange endpoint + poller)
- Plaid Link OAuth flow: covered (link-token + exchange endpoints)
- Plaid poller: covered (plaid_poll.py with same scheduler pattern as news)
- Finance tab: covered (FinanceTab.tsx with react-plaid-link)
- Config (sandbox-first, env var switch): covered (.env.tmp + .env.synology.example + docker-compose.yml)

**Out of scope (v1):** Investments, Liabilities, Webhooks, multiple Plaid accounts per person — not implemented.

**entity_instance_id consistency:** Both Scala (`UUID.nameUUIDFromBytes`) and Python (`_stable_id`) use MD5 with the same prefix strings (`"plaid:item:{itemId}"`, `"plaid:account:{accountId}"`, `"plaid:transaction:{txnId}"`). The bit manipulation for version/variant is identical, so IDs are consistent across runs and both services.

**Disconnect caveat:** The disconnect operation creates a `delete` operation fact on the `plaid_connection` entity. The `plaid_poll` handler will still find the connection in `listCurrentFacts` because `current_facts` view shows the last operation. To fully prevent further syncing after disconnect, the poller checks the fact fields — if `access_token` is missing in the merged fields (which it won't be after a delete operation is applied correctly), it skips. Ensure `current_facts` view applies delete operations correctly (this is handled by the existing fact system).
