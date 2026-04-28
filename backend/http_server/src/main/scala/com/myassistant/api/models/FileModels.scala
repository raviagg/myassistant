package com.myassistant.api.models

import io.circe.Codec

import java.util.UUID

/** HTTP response body after a successful file upload. */
final case class FileUploadResponse(
    key:          String,
    originalName: String,
    mimeType:     String,
    size:         Long,
) derives Codec.AsObject

/** HTTP request body for POST /files/extract-text. */
final case class ExtractTextRequest(
    key:      String,
    mimeType: String,
) derives Codec.AsObject

/** HTTP response body for POST /files/extract-text. */
final case class ExtractTextResponse(
    key:         String,
    extractedText: String,
) derives Codec.AsObject

/** HTTP request body for GET /files — query filter. */
final case class FileInfo(
    key:          String,
    originalName: String,
    mimeType:     String,
    size:         Long,
    personId:     Option[UUID],
    householdId:  Option[UUID],
) derives Codec.AsObject
