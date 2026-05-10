package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{CreateScheduledJobRequest, UpdateScheduledJobRequest}
import com.myassistant.services.ScheduledJobService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

object ScheduledJobRoutes:

  val routes: Routes[ScheduledJobService & ZConnectionPool, Nothing] =
    Routes(
      // POST /api/v1/scheduled-jobs — create a new scheduled job
      Method.POST / "api" / "v1" / "scheduled-jobs" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[CreateScheduledJobRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(createReq) =>
                ZIO.serviceWithZIO[ScheduledJobService](_.create(createReq))
                  .foldZIO(
                    err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    job => ZIO.succeed(Response.json(job.asJson.noSpaces).status(Status.Created)),
                  )
          yield response
        },

      // GET /api/v1/scheduled-jobs?personId=...&householdId=...
      Method.GET / "api" / "v1" / "scheduled-jobs" ->
        handler { (req: Request) =>
          val personIdParam    = req.queryParam("personId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val householdIdParam = req.queryParam("householdId").flatMap(s => Try(UUID.fromString(s)).toOption)
          (personIdParam, householdIdParam) match
            case (Some(personId), _) =>
              ZIO.serviceWithZIO[ScheduledJobService](_.listByPerson(personId))
                .foldZIO(
                  err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  jobs => ZIO.succeed(Response.json(io.circe.Json.obj("items" -> io.circe.Json.arr(jobs.map(_.asJson)*)).noSpaces)),
                )
            case (_, Some(householdId)) =>
              ZIO.serviceWithZIO[ScheduledJobService](_.listByHousehold(householdId))
                .foldZIO(
                  err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  jobs => ZIO.succeed(Response.json(io.circe.Json.obj("items" -> io.circe.Json.arr(jobs.map(_.asJson)*)).noSpaces)),
                )
            case (None, None) =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'personId' or 'householdId' is required"}"""
              ).status(Status.BadRequest))
        },

      // GET /api/v1/scheduled-jobs/:jobId — get a single scheduled job
      Method.GET / "api" / "v1" / "scheduled-jobs" / string("jobId") ->
        handler { (jobId: String, _: Request) =>
          Try(UUID.fromString(jobId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $jobId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[ScheduledJobService](_.getById(id))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  {
                    case None      => ZIO.succeed(Response.json(
                        s"""{"error":"not_found","message":"scheduled_job with id '$id' not found"}"""
                      ).status(Status.NotFound))
                    case Some(job) => ZIO.succeed(Response.json(job.asJson.noSpaces))
                  },
                )
        },

      // PUT /api/v1/scheduled-jobs/:jobId — update a scheduled job
      Method.PUT / "api" / "v1" / "scheduled-jobs" / string("jobId") ->
        handler { (jobId: String, req: Request) =>
          Try(UUID.fromString(jobId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $jobId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              for
                bodyStr  <- req.body.asString.orDie
                response <- decode[UpdateScheduledJobRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(
                      s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                    ).status(Status.BadRequest))
                  case Right(updateReq) =>
                    ZIO.serviceWithZIO[ScheduledJobService](_.update(id, updateReq))
                      .foldZIO(
                        err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        {
                          case None      => ZIO.succeed(Response.json(
                              s"""{"error":"not_found","message":"scheduled_job with id '$id' not found"}"""
                            ).status(Status.NotFound))
                          case Some(job) => ZIO.succeed(Response.json(job.asJson.noSpaces))
                        },
                      )
              yield response
        },

      // DELETE /api/v1/scheduled-jobs/:jobId — delete a scheduled job
      Method.DELETE / "api" / "v1" / "scheduled-jobs" / string("jobId") ->
        handler { (jobId: String, _: Request) =>
          Try(UUID.fromString(jobId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $jobId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[ScheduledJobService](_.delete(id))
                .foldZIO(
                  err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  found =>
                    if found then ZIO.succeed(Response.status(Status.NoContent))
                    else ZIO.succeed(Response.json(
                      s"""{"error":"not_found","message":"scheduled_job with id '$id' not found"}"""
                    ).status(Status.NotFound)),
                )
        },

      // GET /api/v1/scheduled-jobs/:jobId/runs — list runs for a job
      Method.GET / "api" / "v1" / "scheduled-jobs" / string("jobId") / "runs" ->
        handler { (jobId: String, _: Request) =>
          Try(UUID.fromString(jobId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $jobId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[ScheduledJobService](_.getRunsByJobId(id))
                .foldZIO(
                  err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  runs => ZIO.succeed(Response.json(io.circe.Json.obj("items" -> io.circe.Json.arr(runs.map(_.asJson)*)).noSpaces)),
                )
        },
    )
