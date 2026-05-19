package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.CreateSourceConnectionRequest
import com.myassistant.api.plaid.*
import com.myassistant.db.repositories.PlaidSyncRepository
import com.myassistant.errors.AppError
import com.myassistant.services.SourceConnectionService
import io.circe.{Json, JsonObject}
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID

object PlaidRoutes:

  val routes: Routes[PlaidClient & SourceConnectionService & PlaidSyncRepository & ZConnectionPool, Nothing] =
    Routes(

      // POST /api/v1/plaid/link-token
      Method.POST / "api" / "v1" / "plaid" / "link-token" ->
        handler { (req: Request) =>
          for
            bodyStr <- req.body.asString.orDie
            resp <- decode[LinkTokenRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  Json.obj(
                    "error"   -> Json.fromString("bad_request"),
                    "message" -> Json.fromString(err.getMessage),
                  ).noSpaces
                ).status(Status.BadRequest))
              case Right(r) =>
                ZIO.serviceWithZIO[PlaidClient](_.createLinkToken(r.personId.toString))
                  .foldCauseZIO(
                    cause =>
                      val msg = cause.squash.getMessage
                      ZIO.logError(s"Plaid link-token failed: $msg") *>
                        ZIO.succeed(Response.json(
                          Json.obj(
                            "error"   -> Json.fromString("plaid_error"),
                            "message" -> Json.fromString(msg),
                          ).noSpaces
                        ).status(Status.BadGateway)),
                    tok => ZIO.succeed(Response.json(LinkTokenResponse(tok).asJson.noSpaces)),
                  )
          yield resp
        },

      // POST /api/v1/plaid/exchange
      Method.POST / "api" / "v1" / "plaid" / "exchange" ->
        handler { (req: Request) =>
          for
            bodyStr <- req.body.asString.orDie
            resp <- decode[ExchangeRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  Json.obj(
                    "error"   -> Json.fromString("bad_request"),
                    "message" -> Json.fromString(err.getMessage),
                  ).noSpaces
                ).status(Status.BadRequest))
              case Right(r) =>
                handleExchange(r.personId, r.publicToken)
                  .foldZIO(
                    err    => ZIO.logError(s"Plaid exchange failed: ${err.getMessage}") *>
                                ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    result => ZIO.succeed(Response.json(result.asJson.noSpaces).status(Status.Created)),
                  )
          yield resp
        },
    )

  /** Exchange a Plaid public token, then persist:
   *    1. one row in `source_connections` (with encrypted access_token + item_id)
   *    2. one row in `plaid.connections`
   *    3. one row per account in `plaid.bank_accounts`
   *
   *  The legacy document/fact ingestion path has been removed — Plaid
   *  data now lives in the native `plaid.*` tables.
   */
  private def handleExchange(
      personId:    UUID,
      publicToken: String,
  ): ZIO[PlaidClient & SourceConnectionService & PlaidSyncRepository & ZConnectionPool, AppError, ExchangeResponse] =
    for
      // 1. Talk to Plaid: exchange + accounts + institution name
      (accessToken, itemId) <- ZIO.serviceWithZIO[PlaidClient](_.exchangePublicToken(publicToken))
        .mapError(e => AppError.InternalError(e))

      accountsResp <- ZIO.serviceWithZIO[PlaidClient](_.getAccounts(accessToken))
        .mapError(e => AppError.InternalError(e))

      institutionName <- accountsResp.item.institution_id match
        case Some(instId) =>
          ZIO.serviceWithZIO[PlaidClient](_.getInstitutionName(instId))
            .mapError(e => AppError.InternalError(e))
            .orElse(ZIO.succeed("Unknown Institution"))
        case None =>
          ZIO.succeed("Unknown Institution")

      // 2. Create the source_connections row (service encrypts secrets).
      configObj = JsonObject(
        "institution_name" -> Json.fromString(institutionName),
        "institution_id"   -> accountsResp.item.institution_id.fold(Json.Null)(Json.fromString),
      )
      secretsPlain = Json.obj(
        "access_token" -> Json.fromString(accessToken),
        "item_id"      -> Json.fromString(itemId),
      ).noSpaces
      sourceConn <- ZIO.serviceWithZIO[SourceConnectionService](_.create(CreateSourceConnectionRequest(
        sourceType     = "plaid_poll",
        connectionName = institutionName,
        personId       = Some(personId),
        householdId    = None,
        config         = Some(configObj),
        secrets        = Some(secretsPlain),
        syncScheduled  = Some(true),
        syncAdhoc      = Some(true),
        syncSchedule   = Some("0 2 * * *"),
      )))

      // 3. Upsert plaid.connections.
      plaidConn <- ZIO.serviceWithZIO[PlaidSyncRepository](_.upsertConnection(
        sourceConnectionId = sourceConn.id,
        plaidItemId        = itemId,
        institutionName    = institutionName,
        cursor             = None,
      ))

      // 4. Upsert one plaid.bank_accounts row per account.
      _ <- ZIO.foreachDiscard(accountsResp.accounts) { account =>
        val balance: Option[BigDecimal] = account.balances.current.map(BigDecimal.apply)
        ZIO.serviceWithZIO[PlaidSyncRepository](_.upsertBankAccount(
          sourceConnectionId = sourceConn.id,
          connectionId       = plaidConn.id,
          plaidAccountId     = account.account_id,
          name               = account.name,
          accountType        = account.`type`,
          currentBalance     = balance,
        ))
      }

    yield ExchangeResponse(itemId = itemId, institutionName = institutionName)
