package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.PersonHouseholdResponse
import com.myassistant.services.HouseholdService
import io.circe.syntax.*
import io.circe.Json
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

/** HTTP routes for person-household membership.
 *
 *  PUT    /api/v1/households/:hid/members/:pid  — add a member
 *  DELETE /api/v1/households/:hid/members/:pid  — remove a member
 *  GET    /api/v1/households/:hid/members       — list members of a household
 *  GET    /api/v1/persons/:pid/households       — list households for a person
 */
object PersonHouseholdRoutes:

  /** Build membership routes requiring HouseholdService and ZConnectionPool in the environment. */
  val routes: Routes[HouseholdService & ZConnectionPool, Nothing] =
    Routes(
      Method.PUT / "api" / "v1" / "households" / string("householdId") / "members" / string("personId") ->
        handler { (householdId: String, personId: String, _: Request) =>
          (Try(UUID.fromString(householdId)).toEither, Try(UUID.fromString(personId)).toEither) match
            case (Left(_), _) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid household UUID: $householdId"}"""
              ).status(Status.BadRequest))
            case (_, Left(_)) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid person UUID: $personId"}"""
              ).status(Status.BadRequest))
            case (Right(hid), Right(pid)) =>
              ZIO.serviceWithZIO[HouseholdService](_.addMember(pid, hid))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  _   => ZIO.succeed(Response.status(Status.NoContent)),
                )
        },

      Method.DELETE / "api" / "v1" / "households" / string("householdId") / "members" / string("personId") ->
        handler { (householdId: String, personId: String, _: Request) =>
          (Try(UUID.fromString(householdId)).toEither, Try(UUID.fromString(personId)).toEither) match
            case (Left(_), _) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid household UUID: $householdId"}"""
              ).status(Status.BadRequest))
            case (_, Left(_)) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid person UUID: $personId"}"""
              ).status(Status.BadRequest))
            case (Right(hid), Right(pid)) =>
              ZIO.serviceWithZIO[HouseholdService](_.removeMember(pid, hid))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  _   => ZIO.succeed(Response.status(Status.NoContent)),
                )
        },

      Method.GET / "api" / "v1" / "households" / string("householdId") / "members" ->
        handler { (householdId: String, _: Request) =>
          Try(UUID.fromString(householdId)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $householdId"}"""
              ).status(Status.BadRequest))
            case Right(hid) =>
              ZIO.serviceWithZIO[HouseholdService](_.listMembers(hid))
                .foldZIO(
                  err     => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  members =>
                    val memberIds = members.map(_.personId.toString)
                    val body = Json.obj(
                      "householdId" -> Json.fromString(hid.toString),
                      "memberIds"   -> Json.arr(memberIds.map(Json.fromString)*),
                    )
                    ZIO.succeed(Response.json(body.noSpaces))
                )
        },

      Method.GET / "api" / "v1" / "persons" / string("personId") / "households" ->
        handler { (personId: String, _: Request) =>
          Try(UUID.fromString(personId)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $personId"}"""
              ).status(Status.BadRequest))
            case Right(pid) =>
              ZIO.serviceWithZIO[HouseholdService](_.listPersonHouseholds(pid))
                .foldZIO(
                  err         => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  memberships =>
                    val householdIds = memberships.map(_.householdId.toString)
                    val body = Json.obj(
                      "personId"    -> Json.fromString(pid.toString),
                      "householdIds" -> Json.arr(householdIds.map(Json.fromString)*),
                    )
                    ZIO.succeed(Response.json(body.noSpaces))
                )
        },
    )
