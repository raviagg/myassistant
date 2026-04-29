package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{CreateHouseholdRequest, HouseholdResponse, PagedResponse, UpdateHouseholdRequest}
import com.myassistant.domain.{UpdateHousehold, CreateHousehold}
import com.myassistant.services.HouseholdService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

/** HTTP routes for household management.
 *
 *  POST   /api/v1/households       — create a household
 *  GET    /api/v1/households       — list all households
 *  GET    /api/v1/households/:id   — get a single household
 *  PATCH  /api/v1/households/:id   — update a household
 *  DELETE /api/v1/households/:id   — delete a household
 */
object HouseholdRoutes:

  /** Build household routes requiring HouseholdService and ZConnectionPool in the environment. */
  val routes: Routes[HouseholdService & ZConnectionPool, Nothing] =
    Routes(
      Method.GET / "api" / "v1" / "households" ->
        handler { (req: Request) =>
          req.queryParam("name") match
            case None =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'name' is required"}"""
              ).status(Status.BadRequest))
            case Some(name) =>
              ZIO.serviceWithZIO[HouseholdService](_.searchHouseholds(name))
                .foldZIO(
                  err        => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  households =>
                    ZIO.succeed(Response.json(
                      PagedResponse(
                        items  = households.map(HouseholdResponse.fromDomain),
                        total  = households.size.toLong,
                        limit  = 1000,
                        offset = 0,
                      ).asJson.noSpaces
                    ))
                )
        },

      Method.POST / "api" / "v1" / "households" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[CreateHouseholdRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(createReq) =>
                ZIO.serviceWithZIO[HouseholdService](_.createHousehold(CreateHousehold(createReq.name)))
                  .foldZIO(
                    err       => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    household => ZIO.succeed(Response.json(HouseholdResponse.fromDomain(household).asJson.noSpaces).status(Status.Created)),
                  )
          yield response
        },

      Method.GET / "api" / "v1" / "households" / string("householdId") ->
        handler { (householdId: String, _: Request) =>
          Try(UUID.fromString(householdId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $householdId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[HouseholdService](_.getHousehold(id))
                .foldZIO(
                  err       => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  household => ZIO.succeed(Response.json(HouseholdResponse.fromDomain(household).asJson.noSpaces)),
                )
        },

      Method.PATCH / "api" / "v1" / "households" / string("householdId") ->
        handler { (householdId: String, req: Request) =>
          Try(UUID.fromString(householdId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $householdId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              for
                bodyStr  <- req.body.asString.orDie
                response <- decode[UpdateHouseholdRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(
                      s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                    ).status(Status.BadRequest))
                  case Right(updateReq) =>
                    ZIO.serviceWithZIO[HouseholdService](_.updateHousehold(id, UpdateHousehold(updateReq.name)))
                      .foldZIO(
                        err       => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        household => ZIO.succeed(Response.json(HouseholdResponse.fromDomain(household).asJson.noSpaces)),
                      )
              yield response
        },

      Method.DELETE / "api" / "v1" / "households" / string("householdId") ->
        handler { (householdId: String, _: Request) =>
          Try(UUID.fromString(householdId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $householdId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[HouseholdService](_.deleteHousehold(id))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  _   => ZIO.succeed(Response.status(Status.NoContent)),
                )
        },
    )
