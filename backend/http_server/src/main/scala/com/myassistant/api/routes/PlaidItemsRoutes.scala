package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.PlaidItemResponse
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

/** HTTP routes for source-connection-scoped Plaid operations.
 *
 *  Path layout:
 *    POST   /api/v1/source-connections/{connId}/plaid/link-token
 *    POST   /api/v1/source-connections/{connId}/plaid/exchange
 *    GET    /api/v1/source-connections/{connId}/plaid/items
 *    DELETE /api/v1/source-connections/{connId}/plaid/items/{itemId}
 */
object PlaidItemsRoutes:

  type Env = SourceConnectionService & PlaidClient & PlaidSyncRepository & SecretsConfig & ZConnectionPool

  val routes: Routes[Env, Nothing] =
    Routes(

      // POST /api/v1/source-connections/{connId}/plaid/link-token
      Method.POST / "api" / "v1" / "source-connections" / string("connId") / "plaid" / "link-token" ->
        handler { (connId: String, req: Request) =>
          (for
            scId  <- parseUUID(connId)
            creds <- resolveCreds(scId)
            token <- ZIO.serviceWithZIO[PlaidClient](
                       _.createLinkToken(connId, creds._1, creds._2))
                       .mapError(AppError.InternalError(_))
          yield Response.json(Json.obj("linkToken" -> Json.fromString(token)).noSpaces))
            .foldZIO(
              err => ZIO.logError(s"link-token failed for $connId: ${err.getMessage}") *>
                     ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              ZIO.succeed(_),
            )
        },

      // POST /api/v1/source-connections/{connId}/plaid/exchange
      Method.POST / "api" / "v1" / "source-connections" / string("connId") / "plaid" / "exchange" ->
        handler { (connId: String, req: Request) =>
          (for
            scId        <- parseUUID(connId)
            bodyStr     <- req.body.asString.orDie
            bodyJson    <- ZIO.fromEither(parser.parse(bodyStr))
                             .mapError(e => AppError.ValidationError(e.message))
            publicToken <- ZIO.fromOption(bodyJson.hcursor.get[String]("publicToken").toOption)
                             .mapError(_ => AppError.ValidationError("publicToken is required"))
            creds <- resolveCreds(scId)
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

            _ <- ZIO.serviceWithZIO[PlaidSyncRepository](_.upsertConnection(
                   sourceConnectionId = scId,
                   plaidItemId        = itemId,
                   institutionName    = institutionName,
                   cursor             = None,
                   accessToken        = Some(encryptedToken),
                 ))

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

      // GET /api/v1/source-connections/{connId}/plaid/items
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

      // DELETE /api/v1/source-connections/{connId}/plaid/items/{itemId}
      Method.DELETE / "api" / "v1" / "source-connections" / string("connId") / "plaid" / "items" / string("itemId") ->
        handler { (connId: String, itemId: String, _: Request) =>
          (for
            scId  <- parseUUID(connId)
            iId   <- parseUUID(itemId)
            found <- ZIO.serviceWithZIO[PlaidSyncRepository](_.deleteConnection(scId, iId))
            resp   = if found then Response.status(Status.NoContent)
                     else ErrorMiddleware.appErrorToResponse(
                            AppError.NotFound("plaid_connection", itemId))
          yield resp)
            .foldZIO(
              err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              ZIO.succeed(_),
            )
        },
    )

  private def parseUUID(s: String): IO[AppError, UUID] =
    ZIO.attempt(UUID.fromString(s))
      .mapError(_ => AppError.ValidationError(s"Invalid UUID: $s"))

  private def resolveCreds(connId: UUID): ZIO[SourceConnectionService & SecretsConfig & ZConnectionPool, AppError, (String, String)] =
    for
      rawOpt   <- ZIO.serviceWithZIO[SourceConnectionService](_.getSecrets(connId))
      raw      <- ZIO.fromOption(rawOpt).mapError(_ =>
                    AppError.ValidationError(s"No Plaid credentials stored for connection $connId"))
      json     <- ZIO.fromEither(parser.parse(raw))
                    .mapError(e => AppError.InternalError(new RuntimeException(s"Invalid secrets JSON: $e")))
      clientId <- ZIO.fromOption(json.hcursor.get[String]("client_id").toOption)
                    .mapError(_ => AppError.ValidationError("Missing client_id in connection secrets"))
      secret   <- ZIO.fromOption(json.hcursor.get[String]("secret").toOption)
                    .mapError(_ => AppError.ValidationError("Missing secret in connection secrets"))
    yield (clientId, secret)
