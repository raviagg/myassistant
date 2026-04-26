package com.myassistant.unit.middleware

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.errors.AppError
import zio.http.*
import zio.test.*
import zio.test.Assertion.*

object ErrorMiddlewareSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Throwable] =
    suite("ErrorMiddlewareSpec")(

      test("NotFound maps to 404") {
        val response = ErrorMiddleware.appErrorToResponse(AppError.NotFound("person", "abc-123"))
        for body <- response.body.asString
        yield assertTrue(response.status == Status.NotFound) &&
              assertTrue(body.contains("not_found"))
      },

      test("Conflict maps to 409") {
        val response = ErrorMiddleware.appErrorToResponse(AppError.Conflict("duplicate email"))
        for body <- response.body.asString
        yield assertTrue(response.status == Status.Conflict) &&
              assertTrue(body.contains("conflict"))
      },

      test("ValidationError maps to 422") {
        val response = ErrorMiddleware.appErrorToResponse(AppError.ValidationError("name is required"))
        for body <- response.body.asString
        yield assertTrue(response.status == Status.UnprocessableEntity) &&
              assertTrue(body.contains("validation_error"))
      },

      test("ReferentialIntegrityError maps to 409") {
        val response = ErrorMiddleware.appErrorToResponse(
          AppError.ReferentialIntegrityError("cannot delete", Map("fact" -> 3))
        )
        for body <- response.body.asString
        yield assertTrue(response.status == Status.Conflict) &&
              assertTrue(body.contains("referenced"))
      },

      test("DatabaseError maps to 500") {
        val response = ErrorMiddleware.appErrorToResponse(
          AppError.DatabaseError(new RuntimeException("connection reset"))
        )
        for body <- response.body.asString
        yield assertTrue(response.status == Status.InternalServerError) &&
              assertTrue(body.contains("database_error"))
      },

      test("AuthError maps to 401") {
        val response = ErrorMiddleware.appErrorToResponse(AppError.AuthError)
        for body <- response.body.asString
        yield assertTrue(response.status == Status.Unauthorized) &&
              assertTrue(body.contains("unauthorized"))
      },

      test("InternalError maps to 500") {
        val response = ErrorMiddleware.appErrorToResponse(
          AppError.InternalError(new RuntimeException("unexpected"))
        )
        for body <- response.body.asString
        yield assertTrue(response.status == Status.InternalServerError) &&
              assertTrue(body.contains("internal_error"))
      },

      test("FileSystemError maps to 500") {
        val response = ErrorMiddleware.appErrorToResponse(
          AppError.FileSystemError(new RuntimeException("disk full"))
        )
        for body <- response.body.asString
        yield assertTrue(response.status == Status.InternalServerError) &&
              assertTrue(body.contains("filesystem_error"))
      },

      test("response body contains error code") {
        val response = ErrorMiddleware.appErrorToResponse(AppError.NotFound("household", "xyz"))
        for body <- response.body.asString
        yield assertTrue(body.contains("\"error\"")) &&
              assertTrue(body.contains("\"message\""))
      },

    )
