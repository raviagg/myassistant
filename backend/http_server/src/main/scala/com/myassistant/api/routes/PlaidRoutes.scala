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

  private def stableId(prefix: String, key: String): UUID =
    UUID.nameUUIDFromBytes(s"$prefix:$key".getBytes(StandardCharsets.UTF_8))

  private def handleExchange(
      personId:    UUID,
      publicToken: String,
  ): ZIO[PlaidClient & DocumentService & FactService & SchemaService & ReferenceService & ZConnectionPool, AppError, ExchangeResponse] =
    for
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
