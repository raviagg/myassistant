package com.myassistant.api.models

import io.circe.Codec

import java.time.Instant

/** HTTP response body for a domain record. */
final case class DomainResponse(
    name:        String,
    description: String,
    createdAt:   Instant,
) derives Codec.AsObject

/** HTTP response body for a source type record. */
final case class SourceTypeResponse(
    name:        String,
    description: String,
    createdAt:   Instant,
) derives Codec.AsObject

/** HTTP request body for creating a domain or source type. */
final case class CreateReferenceRequest(
    name:        String,
    description: String,
) derives Codec.AsObject

/** HTTP response body for a kinship alias record. */
final case class KinshipAliasResponse(
    id:            Int,
    relationChain: List[String],
    language:      String,
    alias:         String,
    description:   Option[String],
    createdAt:     Instant,
) derives Codec.AsObject
