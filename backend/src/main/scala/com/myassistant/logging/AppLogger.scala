package com.myassistant.logging

import zio.*
import zio.logging.*
import zio.logging.backend.SLF4J

/** Application-wide logger configuration.
 *
 *  Provides a structured ZIO logging layer backed by SLF4J.
 *  Log output format is defined in LogFormat.
 */
object AppLogger:

  /** ZLayer providing structured SLF4J-backed logging for the ZIO runtime. */
  val live: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers >>>
      SLF4J.slf4j(LogFormat.structured)
