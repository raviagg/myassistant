package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{
  PlaidBankAccountResponse,
  PlaidConnectionResponse,
  PlaidTransactionsBatchRequest,
  PlaidTransactionsBatchResponse,
  UpsertPlaidBankAccountRequest,
  UpsertPlaidConnectionRequest,
}
import com.myassistant.db.repositories.{PlaidSyncRepository, PlaidTransactionInput}
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

/** HTTP routes that fronts `plaid.*` schema upserts and deletes.
 *
 *  These are connector-internal endpoints used by the Python scheduler
 *  worker to write the data fetched from Plaid into the native
 *  relational tables. They are not exposed via the MCP layer.
 */
object PlaidSyncRoutes:

  val routes: Routes[PlaidSyncRepository & ZConnectionPool, Nothing] =
    Routes(

      // ── POST /api/v1/plaid/connections/upsert ────────────────
      Method.POST / "api" / "v1" / "plaid" / "connections" / "upsert" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[UpsertPlaidConnectionRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  Json.obj(
                    "error"   -> Json.fromString("bad_request"),
                    "message" -> Json.fromString(err.getMessage),
                  ).noSpaces
                ).status(Status.BadRequest))
              case Right(r) =>
                ZIO.serviceWithZIO[PlaidSyncRepository](_.upsertConnection(
                  r.sourceConnectionId, r.plaidItemId, r.institutionName, r.cursor,
                ))
                  .foldZIO(
                    err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    row => ZIO.succeed(Response.json(
                      PlaidConnectionResponse.fromDomain(row).asJson.noSpaces
                    )),
                  )
          yield response
        },

      // ── POST /api/v1/plaid/accounts/upsert ───────────────────
      Method.POST / "api" / "v1" / "plaid" / "accounts" / "upsert" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[UpsertPlaidBankAccountRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  Json.obj(
                    "error"   -> Json.fromString("bad_request"),
                    "message" -> Json.fromString(err.getMessage),
                  ).noSpaces
                ).status(Status.BadRequest))
              case Right(r) =>
                ZIO.serviceWithZIO[PlaidSyncRepository](_.upsertBankAccount(
                  r.sourceConnectionId, r.connectionId, r.plaidAccountId,
                  r.name, r.accountType, r.currentBalance,
                ))
                  .foldZIO(
                    err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    row => ZIO.succeed(Response.json(
                      PlaidBankAccountResponse.fromDomain(row).asJson.noSpaces
                    )),
                  )
          yield response
        },

      // ── POST /api/v1/plaid/transactions/batch ────────────────
      Method.POST / "api" / "v1" / "plaid" / "transactions" / "batch" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[PlaidTransactionsBatchRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  Json.obj(
                    "error"   -> Json.fromString("bad_request"),
                    "message" -> Json.fromString(err.getMessage),
                  ).noSpaces
                ).status(Status.BadRequest))
              case Right(r) =>
                val added    = r.added.getOrElse(Nil).map(toInput)
                val modified = r.modified.getOrElse(Nil).map(toInput)
                val removed  = r.removedPlaidTransactionIds.getOrElse(Nil)
                ZIO.serviceWithZIO[PlaidSyncRepository](_.upsertTransactionsBatch(
                  r.sourceConnectionId, r.accountId, added, modified, removed,
                ))
                  .foldZIO(
                    err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    { case (a, m, d) =>
                      ZIO.succeed(Response.json(
                        PlaidTransactionsBatchResponse(added = a, modified = m, removed = d).asJson.noSpaces
                      ))
                    },
                  )
          yield response
        },
    )

  private def toInput(w: com.myassistant.api.models.PlaidTransactionWrite): PlaidTransactionInput =
    PlaidTransactionInput(
      plaidTransactionId = w.plaidTransactionId,
      amount             = w.amount,
      date               = w.date,
      merchantName       = w.merchantName,
      category           = w.category.getOrElse(Nil),
      paymentChannel     = w.paymentChannel,
      pending            = w.pending.getOrElse(false),
    )
