package com.myassistant.config

/** File storage configuration.
 *
 *  Populated from the `myassistant.fileStorage` HOCON block.
 */
final case class FileStorageConfig(
    /** Base path or S3 prefix where uploaded files are stored.
     *  Local filesystem path in development; s3://bucket/prefix in production.
     */
    basePath: String,
)
