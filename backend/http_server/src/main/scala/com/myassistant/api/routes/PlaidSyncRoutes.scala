package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{
  PlaidConnectionResponse,
  UpsertPlaidConnectionRequest,
}
import com.myassistant.db.repositories.PlaidSyncRepository
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
                  r.sourceConnectionId, r.plaidItemId, r.institutionName, r.cursor, r.accessToken,
                ))
                  .foldZIO(
                    err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    row => ZIO.succeed(Response.json(
                      PlaidConnectionResponse.fromDomain(row).asJson.noSpaces
                    )),
                  )
          yield response
        },
    )
