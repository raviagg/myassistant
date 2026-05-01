package com.myassistant.logging

import zio.logging.{LogFormat => ZLogFormat}

object LogFormat:

  /** Human-readable log format: timestamp level message */
  val structured: ZLogFormat =
    ZLogFormat.timestamp +
    ZLogFormat.text(" ") +
    ZLogFormat.level +
    ZLogFormat.text(" ") +
    ZLogFormat.line
