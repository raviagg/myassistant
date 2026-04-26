package com.myassistant.unit

import com.myassistant.logging.LogFormat
import zio.*
import zio.test.*
import zio.test.Assertion.*

object LogFormatSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] =
    suite("LogFormatSpec")(

      test("structured format value is accessible") {
        assertTrue(LogFormat.structured != null)
      },

      test("structured format toString contains expected label keys") {
        val rendered = LogFormat.structured.toString
        assertTrue(rendered.nonEmpty)
      },

      test("log output is captured when a message is emitted") {
        for
          _      <- ZIO.logInfo("hello format test")
          output <- ZTestLogger.logOutput
        yield assertTrue(output.nonEmpty) &&
              assertTrue(output.exists(_.message() == "hello format test"))
      },

      test("log output captures the log level") {
        for
          _      <- ZIO.logWarning("warn message")
          output <- ZTestLogger.logOutput
        yield assertTrue(output.exists(e =>
          e.message() == "warn message" && e.logLevel == LogLevel.Warning
        ))
      },

    )
