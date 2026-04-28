package com.myassistant.domain

import java.time.Instant
import java.util.UUID

final case class SourceType(
    id:          UUID,
    name:        String,
    description: String,
    createdAt:   Instant,
)
