package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{CreateScheduledJobRequest, CreateScheduledJobRunRequest, UpdateScheduledJobRequest}
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
          val personIdResult    = req.queryParam("personId").map(s => Try(UUID.fromString(s)).toEither.left.map(_ => s))
          val householdIdResult = req.queryParam("householdId").map(s => Try(UUID.fromString(s)).toEither.left.map(_ => s))
          (personIdResult, householdIdResult) match
            case (Some(Left(bad)), _) =>
              ZIO.succeed(Response.json(s"""{"error":"bad_request","message":"Invalid UUID: $bad"}""").status(Status.BadRequest))
            case (_, Some(Left(bad))) =>
              ZIO.succeed(Response.json(s"""{"error":"bad_request","message":"Invalid UUID: $bad"}""").status(Status.BadRequest))
            case (Some(Right(personId)), _) =>
              ZIO.serviceWithZIO[ScheduledJobService](_.listByPerson(personId))
                .foldZIO(
                  err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  jobs => ZIO.succeed(Response.json(io.circe.Json.obj("items" -> io.circe.Json.arr(jobs.map(_.asJson)*)).noSpaces)),
                )
            case (_, Some(Right(householdId))) =>
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

      // GET /api/v1/scheduled-jobs/due — list jobs due for execution
      // MUST be registered before GET /api/v1/scheduled-jobs/:jobId to avoid "due" being captured as a jobId
      Method.GET / "api" / "v1" / "scheduled-jobs" / "due" ->
        handler { (_: Request) =>
          ZIO.serviceWithZIO[ScheduledJobService](_.listDue())
            .foldZIO(
              err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              jobs => ZIO.succeed(Response.json(io.circe.Json.obj("items" -> io.circe.Json.arr(jobs.map(_.asJson)*)).noSpaces)),
            )
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

      // PATCH /api/v1/scheduled-jobs/:jobId — update a scheduled job
      Method.PATCH / "api" / "v1" / "scheduled-jobs" / string("jobId") ->
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

      // POST /api/v1/scheduled-jobs/:jobId/runs — record a job run
      Method.POST / "api" / "v1" / "scheduled-jobs" / string("jobId") / "runs" ->
        handler { (jobId: String, req: Request) =>
          Try(UUID.fromString(jobId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $jobId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              for
                bodyStr  <- req.body.asString.orDie
                response <- decode[CreateScheduledJobRunRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(
                      s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                    ).status(Status.BadRequest))
                  case Right(runReq) =>
                    ZIO.serviceWithZIO[ScheduledJobService](_.createRun(id, runReq))
                      .foldZIO(
                        err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        run => ZIO.succeed(Response.json(run.asJson.noSpaces).status(Status.Created)),
                      )
              yield response
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
