package com.myassistant.logging

import zio.logging.{LogFormat => ZLogFormat}

/** Custom structured log format for the application.
 *
 *  Formats log lines as JSON-like key=value pairs suitable for log
 *  aggregation systems (Datadog, Splunk, CloudWatch).
 */
object LogFormat:

  /** Structured log format including timestamp, level, fiber, and message. */
  val structured: ZLogFormat =
    ZLogFormat.label("timestamp", ZLogFormat.timestamp) +
    ZLogFormat.label("level",     ZLogFormat.level) +
    ZLogFormat.label("fiber",     ZLogFormat.fiberId) +
    ZLogFormat.label("message",   ZLogFormat.quoted(ZLogFormat.line)) +
    ZLogFormat.label("cause",     ZLogFormat.cause)
