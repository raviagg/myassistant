package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{CreatePersonRequest, PagedResponse, PersonResponse, UpdatePersonRequest}
import com.myassistant.errors.AppError
import com.myassistant.services.PersonService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

/** HTTP routes for person management.
 *
 *  POST   /api/v1/persons       — create a new person
 *  GET    /api/v1/persons       — list all persons (query: householdId)
 *  GET    /api/v1/persons/:id   — get a single person
 *  PATCH  /api/v1/persons/:id   — update a person
 *  DELETE /api/v1/persons/:id   — delete a person
 */
object PersonRoutes:

  /** Build person routes requiring PersonService and ZConnectionPool in the environment. */
  val routes: Routes[PersonService & ZConnectionPool, Nothing] =
    Routes(
      Method.GET / "api" / "v1" / "persons" ->
        handler { (req: Request) =>
          val householdId = req.queryParam("householdId")
            .flatMap(s => Try(UUID.fromString(s)).toOption)
          ZIO.serviceWithZIO[PersonService](_.listPersons(householdId))
            .foldZIO(
              err     => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              persons =>
                val offset = req.queryParam("offset").flatMap(_.toIntOption).getOrElse(0)
                val limit  = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(50)
                ZIO.succeed(Response.json(
                  PagedResponse(
                    items  = persons.map(PersonResponse.fromDomain),
                    total  = persons.size.toLong,
                    limit  = limit,
                    offset = offset,
                  ).asJson.noSpaces
                ))
            )
        },

      Method.POST / "api" / "v1" / "persons" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[CreatePersonRequest](bodyStr) match
              case Left(err)  =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(createReq) =>
                createReq.toDomain match
                  case Left(msg) =>
                    ZIO.succeed(Response.json(
                      s"""{"error":"validation_error","message":"$msg"}"""
                    ).status(Status.UnprocessableEntity))
                  case Right(domainReq) =>
                    ZIO.serviceWithZIO[PersonService](_.createPerson(domainReq))
                      .foldZIO(
                        err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        person => ZIO.succeed(Response.json(PersonResponse.fromDomain(person).asJson.noSpaces).status(Status.Created)),
                      )
          yield response
        },

      Method.GET / "api" / "v1" / "persons" / string("personId") ->
        handler { (personId: String, _: Request) =>
          Try(UUID.fromString(personId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $personId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[PersonService](_.getPerson(id))
                .foldZIO(
                  err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  person => ZIO.succeed(Response.json(PersonResponse.fromDomain(person).asJson.noSpaces)),
                )
        },

      Method.PATCH / "api" / "v1" / "persons" / string("personId") ->
        handler { (personId: String, req: Request) =>
          Try(UUID.fromString(personId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $personId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              for
                bodyStr  <- req.body.asString.orDie
                response <- decode[UpdatePersonRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(
                      s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                    ).status(Status.BadRequest))
                  case Right(updateReq) =>
                    updateReq.toDomain match
                      case Left(msg) =>
                        ZIO.succeed(Response.json(
                          s"""{"error":"validation_error","message":"$msg"}"""
                        ).status(Status.UnprocessableEntity))
                      case Right(patch) =>
                        ZIO.serviceWithZIO[PersonService](_.updatePerson(id, patch))
                          .foldZIO(
                            err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                            person => ZIO.succeed(Response.json(PersonResponse.fromDomain(person).asJson.noSpaces)),
                          )
              yield response
        },

      Method.DELETE / "api" / "v1" / "persons" / string("personId") ->
        handler { (personId: String, _: Request) =>
          Try(UUID.fromString(personId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $personId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[PersonService](_.deletePerson(id))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  _   => ZIO.succeed(Response.status(Status.NoContent)),
                )
        },
    )
