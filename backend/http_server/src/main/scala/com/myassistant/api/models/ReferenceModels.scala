package com.myassistant.api.models

import io.circe.Codec

import java.time.Instant
import java.util.UUID

final case class DomainResponse(
    id:          UUID,
    name:        String,
    description: String,
    createdAt:   Instant,
) derives Codec.AsObject

final case class SourceTypeResponse(
    id:          UUID,
    name:        String,
    description: String,
    createdAt:   Instant,
) derives Codec.AsObject

final case class CreateReferenceRequest(
    name:        String,
    description: String,
) derives Codec.AsObject

final case class KinshipAliasResponse(
    id:            Int,
    relationChain: List[String],
    language:      String,
    alias:         String,
    description:   Option[String],
    createdAt:     Instant,
) derives Codec.AsObject
