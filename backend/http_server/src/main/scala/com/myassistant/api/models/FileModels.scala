package com.myassistant.api.models

import io.circe.Codec

final case class UploadFileRequest(
    contentBase64: String,
    filename:      String,
    mimeType:      Option[String],
) derives Codec.AsObject

final case class FileUploadResponse(
    filePath:  String,
    filename:  String,
    mimeType:  String,
    sizeBytes: Long,
) derives Codec.AsObject

final case class ExtractTextRequest(
    filePath: String,
) derives Codec.AsObject

final case class ExtractTextResponse(
    filePath:         String,
    text:             String,
    extractionMethod: String,
) derives Codec.AsObject

final case class GetFileResponse(
    filePath:      String,
    contentBase64: String,
    mimeType:      String,
    filename:      String,
) derives Codec.AsObject
