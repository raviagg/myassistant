package com.myassistant.domain

import java.time.Instant
import java.util.UUID

final case class Domain(
    id:          UUID,
    name:        String,
    description: String,
    createdAt:   Instant,
)
