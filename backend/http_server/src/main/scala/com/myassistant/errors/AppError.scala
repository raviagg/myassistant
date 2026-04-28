package com.myassistant.errors

/** Sealed trait hierarchy representing all application-level errors.
 *
 *  Every ZIO effect in this codebase uses AppError as its error channel.
 *  Subtypes map to distinct HTTP status codes in ErrorMiddleware.
 */
sealed trait AppError extends Throwable derives scala.CanEqual

object AppError:

  /** Raised when a requested resource does not exist in the database.
   *  Maps to HTTP 404.
   */
  final case class NotFound(resource: String, id: String)
      extends AppError:
    override def getMessage: String = s"$resource with id '$id' not found"

  /** Raised when a create or update violates a uniqueness constraint.
   *  Maps to HTTP 409.
   */
  final case class Conflict(message: String)
      extends AppError:
    override def getMessage: String = message

  /** Raised when incoming request data fails validation.
   *  Maps to HTTP 422.
   */
  final case class ValidationError(message: String)
      extends AppError:
    override def getMessage: String = message

  /** Raised when deleting a record that is still referenced by other records.
   *  `blocking` maps the referencing table name to the count of blocking rows.
   *  Maps to HTTP 409.
   */
  final case class ReferentialIntegrityError(
      message: String,
      blocking: Map[String, Int]
  ) extends AppError:
    override def getMessage: String =
      s"$message — blocking references: ${blocking.map((t, n) => s"$t($n)").mkString(", ")}"

  /** Wraps a database-level exception (JDBC / pool errors).
   *  Maps to HTTP 500.
   */
  final case class DatabaseError(cause: Throwable)
      extends AppError:
    override def getMessage: String = s"Database error: ${cause.getMessage}"
    override def getCause: Throwable = cause

  /** Wraps a file-system or object-storage exception.
   *  Maps to HTTP 500.
   */
  final case class FileSystemError(cause: Throwable)
      extends AppError:
    override def getMessage: String = s"File system error: ${cause.getMessage}"
    override def getCause: Throwable = cause

  /** Raised when a request lacks valid authentication credentials.
   *  Maps to HTTP 401.
   */
  case object AuthError extends AppError:
    override def getMessage: String = "Authentication required"

  /** Catch-all for unexpected internal failures.
   *  Maps to HTTP 500.
   */
  final case class InternalError(cause: Throwable)
      extends AppError:
    override def getMessage: String = s"Internal error: ${cause.getMessage}"
    override def getCause: Throwable = cause
