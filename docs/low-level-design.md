# Low-Level Design — myassistant Scala ZIO Backend

**Stack:** Scala 3.4, ZIO 2, ZIO HTTP 3.x, ZIO JDBC, ZIO Config, ZIO Logging, Circe, Flyway, Prometheus, sbt  
**Purpose:** 43 HTTP REST endpoints (`Bearer <token>` auth) backing the MCP tool layer for a personal assistant.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Package Structure](#2-package-structure)
3. [Package: config](#3-package-commyassistantconfig)
4. [Package: errors](#4-package-commyassistanterrors)
5. [Package: domain](#5-package-commyassistantdomain)
6. [Package: logging](#6-package-commyassistantlogging)
7. [Package: monitoring](#7-package-commyassistantmonitoring)
8. [Package: db](#8-package-commyassistantdb)
9. [Package: db.repositories](#9-package-commyassistantdbrepositories)
10. [Package: services](#10-package-commyassistantservices)
11. [Package: api.models](#11-package-commyassistantapimodels)
12. [Package: api.middleware](#12-package-commyassistantapimiddleware)
13. [Package: api.routes](#13-package-commyassistantapiroutes)
14. [Package: api (Router)](#14-package-commyassistantapi)
15. [Main.scala](#15-mainscala)
16. [ZIO Layer Wiring Diagram](#16-zio-layer-wiring-diagram)
17. [Request Flow Walkthrough](#17-request-flow-walkthrough)
18. [Test Structure](#18-test-structure)

---

## 1. Architecture Overview

The service is a two-tier stateless HTTP backend:

```
Client (MCP tool layer / curl)
        │
        ▼  HTTP/1.1, Bearer token
┌───────────────────────────────────────────────────────┐
│  API Tier  (ZIO HTTP 3.x)                             │
│  Middleware chain: Auth → Metrics → Logging →         │
│  ErrorHandler → Routes                                │
│  One Routes object per resource group                 │
└───────────────────┬───────────────────────────────────┘
                    │ ZIO effect calls
┌───────────────────▼───────────────────────────────────┐
│  Service Tier  (pure ZIO effects)                     │
│  Business logic, referential integrity, BFS kinship   │
│  One Service per domain area                          │
└───────────────────┬───────────────────────────────────┘
                    │ ZIO JDBC (ZConnection)
┌───────────────────▼───────────────────────────────────┐
│  Repository Tier  (ZIO JDBC sql interpolator)         │
│  One Repository trait + Live impl per entity          │
└───────────────────┬───────────────────────────────────┘
                    │ HikariCP connection pool
              PostgreSQL + pgvector
```

**Key principles:**

- No session state — every request is self-contained. Auth token is a static Bearer secret for now.
- All side-effectful operations are ZIO effects. No `Future`, no `Try`.
- ZLayer is the only dependency injection mechanism. No runtime reflection, no DI frameworks.
- Errors propagate as typed `AppError` in the ZIO error channel. Middleware at the boundary converts them to HTTP responses.
- Every ZIO JDBC operation takes an explicit `ZConnection` — transactions are composed at the service layer by wrapping multiple repo calls in `ZIO.serviceWithZIO[ZConnectionPool](_.transaction(...))`.

---

## 2. Package Structure

```
src/
├── main/
│   ├── scala/
│   │   └── com/myassistant/
│   │       ├── Main.scala
│   │       ├── config/
│   │       │   ├── AppConfig.scala
│   │       │   ├── ServerConfig.scala
│   │       │   ├── DatabaseConfig.scala
│   │       │   └── FileStorageConfig.scala
│   │       ├── errors/
│   │       │   └── AppError.scala
│   │       ├── domain/
│   │       │   ├── Person.scala
│   │       │   ├── Household.scala
│   │       │   ├── PersonHousehold.scala
│   │       │   ├── Relationship.scala
│   │       │   ├── Document.scala
│   │       │   ├── Fact.scala
│   │       │   ├── EntityTypeSchema.scala
│   │       │   ├── Domain.scala
│   │       │   ├── SourceType.scala
│   │       │   ├── KinshipAlias.scala
│   │       │   └── AuditLog.scala
│   │       ├── logging/
│   │       │   ├── AppLogger.scala
│   │       │   └── LogFormat.scala
│   │       ├── monitoring/
│   │       │   ├── Metrics.scala
│   │       │   ├── MetricsRegistry.scala
│   │       │   └── MetricsExporter.scala
│   │       ├── db/
│   │       │   ├── DatabaseModule.scala
│   │       │   ├── migrations/
│   │       │   │   └── MigrationRunner.scala
│   │       │   └── repositories/
│   │       │       ├── PersonRepository.scala
│   │       │       ├── HouseholdRepository.scala
│   │       │       ├── PersonHouseholdRepository.scala
│   │       │       ├── RelationshipRepository.scala
│   │       │       ├── DocumentRepository.scala
│   │       │       ├── FactRepository.scala
│   │       │       ├── SchemaRepository.scala
│   │       │       ├── ReferenceRepository.scala
│   │       │       └── AuditRepository.scala
│   │       ├── services/
│   │       │   ├── PersonService.scala
│   │       │   ├── HouseholdService.scala
│   │       │   ├── PersonHouseholdService.scala
│   │       │   ├── RelationshipService.scala
│   │       │   ├── KinshipResolver.scala
│   │       │   ├── DocumentService.scala
│   │       │   ├── FactService.scala
│   │       │   ├── SchemaService.scala
│   │       │   ├── ReferenceService.scala
│   │       │   ├── AuditService.scala
│   │       │   └── FileService.scala
│   │       └── api/
│   │           ├── Router.scala
│   │           ├── models/
│   │           │   ├── CommonModels.scala
│   │           │   ├── PersonModels.scala
│   │           │   ├── HouseholdModels.scala
│   │           │   ├── RelationshipModels.scala
│   │           │   ├── DocumentModels.scala
│   │           │   ├── FactModels.scala
│   │           │   ├── SchemaModels.scala
│   │           │   ├── ReferenceModels.scala
│   │           │   ├── AuditModels.scala
│   │           │   └── FileModels.scala
│   │           ├── middleware/
│   │           │   ├── AuthMiddleware.scala
│   │           │   ├── RequestLoggingMiddleware.scala
│   │           │   ├── MetricsMiddleware.scala
│   │           │   └── ErrorHandlerMiddleware.scala
│   │           └── routes/
│   │               ├── PersonRoutes.scala
│   │               ├── HouseholdRoutes.scala
│   │               ├── PersonHouseholdRoutes.scala
│   │               ├── RelationshipRoutes.scala
│   │               ├── DocumentRoutes.scala
│   │               ├── FactRoutes.scala
│   │               ├── SchemaRoutes.scala
│   │               ├── ReferenceRoutes.scala
│   │               ├── AuditRoutes.scala
│   │               ├── FileRoutes.scala
│   │               └── HealthRoutes.scala
│   └── resources/
│       ├── application.conf
│       └── db/
│           └── migration/
│               ├── V1__spine.sql
│               ├── V2__relationships.sql
│               ├── V3__reference.sql
│               ├── V4__schema_governance.sql
│               ├── V5__document.sql
│               ├── V6__fact.sql
│               └── V7__audit.sql
└── test/
    ├── scala/
    │   └── com/myassistant/
    │       ├── unit/
    │       │   ├── services/
    │       │   │   ├── PersonServiceSpec.scala
    │       │   │   ├── KinshipResolverSpec.scala
    │       │   │   └── FactServiceSpec.scala
    │       │   └── api/
    │       │       ├── AuthMiddlewareSpec.scala
    │       │       └── ErrorHandlerSpec.scala
    │       ├── integration/
    │       │   ├── PersonRepositorySpec.scala
    │       │   ├── FactRepositorySpec.scala
    │       │   ├── DocumentRepositorySpec.scala
    │       │   └── DatabaseTestSupport.scala
    │       └── e2e/
    │           ├── steps/
    │           │   ├── PersonSteps.scala
    │           │   └── FactSteps.scala
    │           └── features/
    │               ├── person.feature
    │               └── fact_lifecycle.feature
    └── resources/
        └── k6/
            └── load_test.js
```

---

## 3. Package: `com.myassistant.config`

### `AppConfig.scala`

```scala
package com.myassistant.config

import zio.config.*
import zio.config.magnolia.*
import zio.{ZIO, ZLayer}

final case class AppConfig(
  server:      ServerConfig,
  database:    DatabaseConfig,
  fileStorage: FileStorageConfig
)

object AppConfig:
  val descriptor: ConfigDescriptor[AppConfig] =
    deriveConfig[AppConfig]

  val live: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer.fromZIO(
      ZIO.config(descriptor.mapKey(toKebabCase))
    )
```

### `ServerConfig.scala`

```scala
package com.myassistant.config

final case class ServerConfig(
  host:      String,   // e.g. "0.0.0.0"
  port:      Int,      // e.g. 8080
  authToken: String    // static Bearer token; compared with constant-time equals
)
```

### `DatabaseConfig.scala`

```scala
package com.myassistant.config

final case class DatabaseConfig(
  jdbcUrl:  String,   // e.g. "jdbc:postgresql://localhost:5432/myassistant"
  user:     String,
  password: String,
  poolSize: Int       // HikariCP maximum pool size, e.g. 10
)
```

### `FileStorageConfig.scala`

```scala
package com.myassistant.config

final case class FileStorageConfig(
  basePath: String   // absolute local directory, e.g. "/var/myassistant/files"
)
```

`application.conf` (HOCON, read by ZIO Config):

```hocon
server {
  host       = "0.0.0.0"
  port       = 8080
  auth-token = "change-me"
}
database {
  jdbc-url  = "jdbc:postgresql://localhost:5432/myassistant"
  user      = "myassistant"
  password  = "secret"
  pool-size = 10
}
file-storage {
  base-path = "/var/myassistant/files"
}
```

---

## 4. Package: `com.myassistant.errors`

### `AppError.scala`

```scala
package com.myassistant.errors

import java.util.UUID

sealed trait AppError extends Throwable

/** Resource not found in the database. */
final case class NotFoundError(
  resource: String,   // e.g. "Person", "Fact"
  id:       String    // stringified identifier
) extends AppError

/** Input failed validation before hitting the DB. */
final case class ValidationError(
  field:   String,
  message: String
) extends AppError

/** Unique constraint violation — resource already exists. */
final case class ConflictError(
  resource: String,
  message:  String
) extends AppError

/**
 * Delete blocked because other rows reference this resource.
 * blockedBy lists the referring tables/fields so the caller can
 * surface a useful message.
 */
final case class ReferencedError(
  resource:  String,
  id:        String,
  blockedBy: List[String]   // e.g. List("document.person_id", "audit_log.person_id")
) extends AppError

/** Unexpected database-level error. Wraps the original cause. */
final case class DatabaseError(
  message: String,
  cause:   Option[Throwable] = None
) extends AppError

/** Missing or invalid Bearer token. */
case object AuthorizationError extends AppError

/** File I/O error during upload/download/delete. */
final case class FileError(
  message: String,
  cause:   Option[Throwable] = None
) extends AppError
```

---

## 5. Package: `com.myassistant.domain`

All domain classes are pure case classes with no ZIO dependency. Enums use Scala 3 `enum`.

### `Person.scala`

```scala
package com.myassistant.domain

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

enum Gender:
  case Male, Female

final case class Person(
  id:             UUID,
  fullName:       String,
  gender:         Gender,
  dateOfBirth:    Option[LocalDate],
  preferredName:  Option[String],
  userIdentifier: Option[String],
  createdAt:      OffsetDateTime,
  updatedAt:      OffsetDateTime
)
```

### `Household.scala`

```scala
package com.myassistant.domain

import java.time.OffsetDateTime
import java.util.UUID

final case class Household(
  id:        UUID,
  name:      String,
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime
)
```

### `PersonHousehold.scala`

```scala
package com.myassistant.domain

import java.time.OffsetDateTime
import java.util.UUID

final case class PersonHousehold(
  personId:    UUID,
  householdId: UUID,
  createdAt:   OffsetDateTime
)
```

### `Relationship.scala`

```scala
package com.myassistant.domain

import java.time.OffsetDateTime
import java.util.UUID

enum RelationType:
  case Father, Mother, Son, Daughter, Brother, Sister, Husband, Wife

object RelationType:
  def fromString(s: String): Either[String, RelationType] =
    values.find(_.toString.equalsIgnoreCase(s))
          .toRight(s"Unknown relation type: $s")

final case class Relationship(
  id:           UUID,
  personIdA:    UUID,
  personIdB:    UUID,
  relationType: RelationType,
  createdAt:    OffsetDateTime,
  updatedAt:    OffsetDateTime
)
```

### `Document.scala`

```scala
package com.myassistant.domain

import java.time.OffsetDateTime
import java.util.UUID

final case class FileAttachment(
  filePath:         String,
  fileType:         String,
  originalFilename: Option[String]
)

final case class Document(
  id:            UUID,
  personId:      Option[UUID],
  householdId:   Option[UUID],
  contentText:   String,
  sourceType:    String,
  files:         List[FileAttachment],
  supersedesIds: List[UUID],
  embedding:     Option[Vector[Float]],  // 1536-dim; None until embedding pipeline runs
  createdAt:     OffsetDateTime
)
```

### `Fact.scala`

```scala
package com.myassistant.domain

import java.time.OffsetDateTime
import java.util.UUID
import io.circe.Json

enum OperationType:
  case Create, Update, Delete

object OperationType:
  def fromString(s: String): Either[String, OperationType] =
    values.find(_.toString.equalsIgnoreCase(s))
          .toRight(s"Unknown operation type: $s")

/** Raw row from the fact table — one operation in the append-only stream. */
final case class Fact(
  id:               UUID,
  documentId:       UUID,
  schemaId:         UUID,
  entityInstanceId: UUID,
  operationType:    OperationType,
  fields:           Json,            // JSONB stored as Circe Json
  embedding:        Option[Vector[Float]],
  createdAt:        OffsetDateTime
)

/**
 * Merged current state of one entity instance.
 * Returned by the current_facts view query and by semantic search.
 * similarityScore is populated only when result comes from a vector search.
 */
final case class CurrentFact(
  entityInstanceId: UUID,
  schemaId:         UUID,
  documentId:       UUID,
  currentFields:    Json,
  createdAt:        OffsetDateTime,
  updatedAt:        OffsetDateTime,
  similarityScore:  Option[Double]
)
```

### `EntityTypeSchema.scala`

```scala
package com.myassistant.domain

import java.time.OffsetDateTime
import java.util.UUID
import io.circe.Json

final case class FieldDefinition(
  name:        String,
  `type`:      String,    // text | number | date | boolean | file
  mandatory:   Boolean,
  description: String
)

final case class EntityTypeSchema(
  id:                UUID,
  domain:            String,
  entityType:        String,
  schemaVersion:     Int,
  description:       String,
  fieldDefinitions:  List[FieldDefinition],
  mandatoryFields:   List[String],           // generated column
  extractionPrompt:  String,
  isActive:          Boolean,
  changeDescription: Option[String],
  createdAt:         OffsetDateTime
)
```

### `Domain.scala`

```scala
package com.myassistant.domain

import java.time.OffsetDateTime

final case class Domain(
  name:        String,
  description: String,
  createdAt:   OffsetDateTime
)
```

### `SourceType.scala`

```scala
package com.myassistant.domain

import java.time.OffsetDateTime

final case class SourceType(
  name:        String,
  description: String,
  createdAt:   OffsetDateTime
)
```

### `KinshipAlias.scala`

```scala
package com.myassistant.domain

import java.time.OffsetDateTime

final case class KinshipAlias(
  id:            Int,
  relationChain: List[String],   // e.g. List("father", "sister")
  language:      String,
  alias:         String,
  description:   Option[String],
  createdAt:     OffsetDateTime
)
```

### `AuditLog.scala`

```scala
package com.myassistant.domain

import java.time.OffsetDateTime
import java.util.UUID
import io.circe.Json

enum AuditStatus:
  case Success, Partial, Failed

final case class AuditLog(
  id:        UUID,
  personId:  Option[UUID],
  jobType:   Option[String],
  message:   String,
  response:  Option[String],
  toolCalls: Json,            // JSONB array
  status:    AuditStatus,
  error:     Option[String],
  createdAt: OffsetDateTime
)
```

---

## 6. Package: `com.myassistant.logging`

### `LogFormat.scala`

```scala
package com.myassistant.logging

import zio.logging.LogFormat
import zio.logging.LogFormat.*

/**
 * Structured JSON log format.
 * Every log line is a single JSON object on stdout.
 * Fields: timestamp, level, service, fiberId, traceId, message, cause
 * Additional KV annotations added with ZIO.logAnnotate are appended.
 */
object LogFormat:
  val jsonStructured: LogFormat =
    LogFormat.make { (builder, trace, fiberId, level, message, cause, context, spans, annotations) =>
      builder.openBrace()
      builder.appendText("\"timestamp\":\"")
      builder.appendText(java.time.Instant.now().toString)
      builder.appendText("\",\"level\":\"")
      builder.appendText(level.label)
      builder.appendText("\",\"service\":\"myassistant\"")
      builder.appendText(",\"fiberId\":\"")
      builder.appendText(fiberId.threadName)
      builder.appendText("\",\"message\":")
      builder.appendQuoted(message)
      annotations.foreach { case (k, v) =>
        builder.appendText(s""","$k":""")
        builder.appendQuoted(v)
      }
      cause.fold(())(c => {
        builder.appendText(",\"cause\":")
        builder.appendQuoted(c.prettyPrint)
      })
      builder.closeBrace()
    }
```

### `AppLogger.scala`

```scala
package com.myassistant.logging

import zio.logging.*
import zio.{ULayer, ZLayer}

object AppLogger:
  /**
   * ZLayer providing structured JSON console logging.
   * Replaces ZIO's default logger with the custom JSON format.
   * Use ZIO.logAnnotate("traceId", id) on every request to add
   * a correlation ID to all log lines within that request's fiber.
   */
  val live: ULayer[Unit] =
    Runtime.removeDefaultLoggers >>>
    consoleLogger(
      ConsoleLoggerConfig(
        LogFormat.jsonStructured,
        LogFilter.logLevel(zio.LogLevel.Info)
      )
    )
```

---

## 7. Package: `com.myassistant.monitoring`

### `Metrics.scala`

```scala
package com.myassistant.monitoring

import zio.metrics.*

object Metrics:
  /** Total HTTP requests. Labels: method, path, status */
  val httpRequestsTotal: Metric.Counter[Long] =
    Metric.counterLong("http_requests_total")
      .tagged("method", "unknown")
      .tagged("path",   "unknown")
      .tagged("status", "unknown")

  /** HTTP request latency histogram. Labels: method, path */
  val httpRequestDuration: Metric.Histogram[Double] =
    Metric.histogram(
      "http_request_duration_seconds",
      MetricKeyType.Histogram.Boundaries.fromChunk(
        zio.Chunk(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)
      )
    ).tagged("method", "unknown")
     .tagged("path",   "unknown")

  /** DB query latency histogram. Label: query_name */
  val dbQueryDuration: Metric.Histogram[Double] =
    Metric.histogram(
      "db_query_duration_seconds",
      MetricKeyType.Histogram.Boundaries.fromChunk(
        zio.Chunk(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0)
      )
    ).tagged("query_name", "unknown")

  /** Number of connections currently checked out of HikariCP pool */
  val activeConnections: Metric.Gauge[Double] =
    Metric.gauge("active_connections")
```

### `MetricsRegistry.scala`

```scala
package com.myassistant.monitoring

import io.prometheus.client.CollectorRegistry
import zio.{Task, ULayer, ZIO, ZLayer}

trait MetricsRegistry:
  def registry: CollectorRegistry

object MetricsRegistry:
  val live: ULayer[MetricsRegistry] =
    ZLayer.succeed {
      new MetricsRegistry:
        val registry: CollectorRegistry =
          CollectorRegistry.defaultRegistry
    }

  def registry: ZIO[MetricsRegistry, Nothing, CollectorRegistry] =
    ZIO.serviceWith[MetricsRegistry](_.registry)
```

### `MetricsExporter.scala`

```scala
package com.myassistant.monitoring

import io.prometheus.client.exporter.common.TextFormat
import zio.*
import zio.http.*

import java.io.StringWriter

/**
 * ZIO HTTP handler for GET /metrics.
 * Serialises all registered Prometheus collectors to
 * the standard text/plain;version=0.0.4 format.
 */
object MetricsExporter:
  val routes: Routes[MetricsRegistry, Nothing] =
    Routes(
      Method.GET / "metrics" ->
        handler { (_: Request) =>
          ZIO.serviceWithZIO[MetricsRegistry] { reg =>
            ZIO.attempt {
              val writer = new StringWriter()
              TextFormat.write004(writer, reg.registry.metricFamilySamples())
              Response(
                status = Status.Ok,
                headers = Headers(Header.ContentType(MediaType.text.plain)),
                body = Body.fromString(writer.toString)
              )
            }.orDie
          }
        }
    )
```

---

## 8. Package: `com.myassistant.db`

### `DatabaseModule.scala`

```scala
package com.myassistant.db

import com.myassistant.config.DatabaseConfig
import zio.*
import zio.jdbc.*

object DatabaseModule:
  /**
   * ZLayer providing a ZConnectionPool backed by HikariCP.
   * Pool is acquired once on startup and released on shutdown.
   * Reads connection parameters from DatabaseConfig.
   */
  val live: ZLayer[DatabaseConfig, Throwable, ZConnectionPool] =
    ZLayer.scoped {
      for
        cfg  <- ZIO.service[DatabaseConfig]
        pool <- ZConnectionPool.h2mem(  // replace with .postgres for real usage
                  ZConnectionPoolConfig.default
                    .withMaxConnections(cfg.poolSize)
                ).orElse(
                  ZConnectionPool.postgres(
                    host     = cfg.jdbcUrl,   // parsed at construction
                    port     = 5432,
                    database = "myassistant",
                    props    = Map(
                      "user"     -> cfg.user,
                      "password" -> cfg.password
                    ),
                    config = ZConnectionPoolConfig.default
                               .withMaxConnections(cfg.poolSize)
                  )
                )
      yield pool
    }

  /**
   * Convenience helper: wraps a ZIO[ZConnection, E, A] in a
   * transaction, acquiring and releasing from the pool.
   */
  def transact[E, A](
    effect: ZIO[ZConnection, E, A]
  ): ZIO[ZConnectionPool, E | Throwable, A] =
    ZIO.serviceWithZIO[ZConnectionPool](_.transaction(effect))
```

### `migrations/MigrationRunner.scala`

```scala
package com.myassistant.db.migrations

import com.myassistant.config.DatabaseConfig
import org.flywaydb.core.Flyway
import zio.*

object MigrationRunner:
  /**
   * Runs Flyway migrations on startup.
   * SQL files must be on the classpath at db/migration/V*.sql.
   * Called from Main.scala before the HTTP server starts.
   * Fails the startup ZIO if migrations cannot be applied.
   */
  val run: ZIO[DatabaseConfig, Throwable, Unit] =
    ZIO.serviceWithZIO[DatabaseConfig] { cfg =>
      ZIO.attemptBlocking {
        val flyway = Flyway.configure()
          .dataSource(cfg.jdbcUrl, cfg.user, cfg.password)
          .locations("classpath:db/migration")
          .baselineOnMigrate(true)
          .load()
        val result = flyway.migrate()
        ZIO.logInfo(s"Flyway: applied ${result.migrationsExecuted} migration(s)")
      }.flatten
    }
```

---

## 9. Package: `com.myassistant.db.repositories`

All repositories follow this pattern:

```
trait XRepository:
  def method(...): ZIO[ZConnection, AppError, T]

object XRepository:
  val live: ZLayer[Any, Nothing, XRepository] = ZLayer.succeed(XRepositoryLive())

final class XRepositoryLive extends XRepository:
  // ZIO JDBC sql interpolator implementation
```

The `ZConnection` dependency appears in every return type — the caller (service layer) wraps multiple calls in `ZConnectionPool#transaction(...)` to get atomicity.

---

### `PersonRepository.scala`

```scala
package com.myassistant.db.repositories

import com.myassistant.domain.Person
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

trait PersonRepository:
  def findById(id: UUID): ZIO[ZConnection, AppError, Option[Person]]

  def findByUserIdentifier(uid: String): ZIO[ZConnection, AppError, Option[Person]]

  /** Supports optional filters: fullName ILIKE, gender, hasUserIdentifier */
  def findAll(
    fullNameLike:      Option[String] = None,
    gender:            Option[String] = None,
    hasUserIdentifier: Option[Boolean] = None,
    limit:             Int = 50,
    offset:            Int = 0
  ): ZIO[ZConnection, AppError, List[Person]]

  def insert(person: Person): ZIO[ZConnection, AppError, Person]

  def update(
    id:            UUID,
    fullName:      Option[String],
    preferredName: Option[String],
    dateOfBirth:   Option[java.time.LocalDate],
    gender:        Option[String]
  ): ZIO[ZConnection, AppError, Person]

  def delete(id: UUID): ZIO[ZConnection, AppError, Unit]

  /**
   * Returns table.column references that prevent deleting this person.
   * Service uses this list to build a ReferencedError.
   */
  def checkReferences(id: UUID): ZIO[ZConnection, AppError, List[String]]

object PersonRepository:
  val live: ZLayer[Any, Nothing, PersonRepository] =
    ZLayer.succeed(PersonRepositoryLive())

  def findById(id: UUID) =
    ZIO.serviceWithZIO[PersonRepository](_.findById(id))
  // ... accessor mirrors for all methods
```

---

### `HouseholdRepository.scala`

```scala
package com.myassistant.db.repositories

import com.myassistant.domain.Household
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

trait HouseholdRepository:
  def findById(id: UUID): ZIO[ZConnection, AppError, Option[Household]]
  def findAll(nameLike: Option[String] = None, limit: Int = 50, offset: Int = 0): ZIO[ZConnection, AppError, List[Household]]
  def insert(h: Household): ZIO[ZConnection, AppError, Household]
  def update(id: UUID, name: String): ZIO[ZConnection, AppError, Household]
  def delete(id: UUID): ZIO[ZConnection, AppError, Unit]
  def checkReferences(id: UUID): ZIO[ZConnection, AppError, List[String]]

object HouseholdRepository:
  val live: ZLayer[Any, Nothing, HouseholdRepository] =
    ZLayer.succeed(HouseholdRepositoryLive())
```

---

### `PersonHouseholdRepository.scala`

```scala
package com.myassistant.db.repositories

import com.myassistant.domain.PersonHousehold
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

trait PersonHouseholdRepository:
  def listByPerson(personId: UUID): ZIO[ZConnection, AppError, List[PersonHousehold]]
  def listByHousehold(householdId: UUID): ZIO[ZConnection, AppError, List[PersonHousehold]]
  def insert(ph: PersonHousehold): ZIO[ZConnection, AppError, PersonHousehold]
  def delete(personId: UUID, householdId: UUID): ZIO[ZConnection, AppError, Unit]
  def exists(personId: UUID, householdId: UUID): ZIO[ZConnection, AppError, Boolean]

object PersonHouseholdRepository:
  val live: ZLayer[Any, Nothing, PersonHouseholdRepository] =
    ZLayer.succeed(PersonHouseholdRepositoryLive())
```

---

### `RelationshipRepository.scala`

```scala
package com.myassistant.db.repositories

import com.myassistant.domain.{Relationship, RelationType}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

trait RelationshipRepository:
  def findById(id: UUID): ZIO[ZConnection, AppError, Option[Relationship]]
  def listByPerson(personId: UUID): ZIO[ZConnection, AppError, List[Relationship]]
  def insert(r: Relationship): ZIO[ZConnection, AppError, Relationship]
  def delete(id: UUID): ZIO[ZConnection, AppError, Unit]

  /**
   * Fetch all outgoing edges from a set of person IDs in one query.
   * Used by KinshipResolver for BFS traversal.
   */
  def fetchEdges(personIds: Set[UUID]): ZIO[ZConnection, AppError, List[Relationship]]

object RelationshipRepository:
  val live: ZLayer[Any, Nothing, RelationshipRepository] =
    ZLayer.succeed(RelationshipRepositoryLive())
```

---

### `DocumentRepository.scala`

```scala
package com.myassistant.db.repositories

import com.myassistant.domain.Document
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

trait DocumentRepository:
  def findById(id: UUID): ZIO[ZConnection, AppError, Option[Document]]

  def listFiltered(
    personId:    Option[UUID] = None,
    householdId: Option[UUID] = None,
    sourceType:  Option[String] = None,
    limit:       Int = 50,
    offset:      Int = 0
  ): ZIO[ZConnection, AppError, List[Document]]

  def insert(doc: Document): ZIO[ZConnection, AppError, Document]

  /**
   * Semantic similarity search using pgvector cosine distance.
   * Returns documents whose embedding is within (1 - threshold) cosine distance.
   * Sorted by similarity descending. Returns at most limit rows.
   */
  def searchBySimilarity(
    embedding:  Vector[Float],
    personId:   Option[UUID] = None,
    householdId: Option[UUID] = None,
    sourceType: Option[String] = None,
    threshold:  Double = 0.8,
    limit:      Int = 10
  ): ZIO[ZConnection, AppError, List[(Document, Double)]]

  /**
   * Update only the embedding column.
   * Called by the embedding pipeline after generating the vector.
   */
  def updateEmbedding(id: UUID, embedding: Vector[Float]): ZIO[ZConnection, AppError, Unit]

object DocumentRepository:
  val live: ZLayer[Any, Nothing, DocumentRepository] =
    ZLayer.succeed(DocumentRepositoryLive())
```

---

### `FactRepository.scala`

```scala
package com.myassistant.db.repositories

import com.myassistant.domain.{CurrentFact, Fact}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

trait FactRepository:
  /** Insert one operation row (create / update / delete). */
  def insertFact(fact: Fact): ZIO[ZConnection, AppError, Fact]

  /** All operation rows for one entity instance, oldest first. */
  def getHistory(entityInstanceId: UUID): ZIO[ZConnection, AppError, List[Fact]]

  /**
   * Current merged state of one entity instance via current_facts view.
   * Returns None if the instance was deleted or never created.
   */
  def getCurrentFact(entityInstanceId: UUID): ZIO[ZConnection, AppError, Option[CurrentFact]]

  /**
   * List current state of all active entity instances, optionally filtered.
   * Joins current_facts → entity_type_schema → document for filter support.
   */
  def listCurrentFacts(
    domain:         Option[String] = None,
    entityType:     Option[String] = None,
    personId:       Option[UUID] = None,
    householdId:    Option[UUID] = None,
    limit:          Int = 50,
    offset:         Int = 0
  ): ZIO[ZConnection, AppError, List[CurrentFact]]

  /**
   * Semantic search over fact embeddings.
   * Returns (CurrentFact, similarityScore) pairs for instances whose
   * embedding is within (1 - threshold) cosine distance of the query embedding.
   */
  def searchCurrentFacts(
    embedding:   Vector[Float],
    domain:      Option[String] = None,
    entityType:  Option[String] = None,
    personId:    Option[UUID] = None,
    threshold:   Double = 0.8,
    limit:       Int = 10
  ): ZIO[ZConnection, AppError, List[CurrentFact]]

  /**
   * Update only the embedding column of a fact row.
   * Called by the embedding pipeline.
   */
  def updateEmbedding(id: UUID, embedding: Vector[Float]): ZIO[ZConnection, AppError, Unit]

  /**
   * Returns fact IDs whose schema_id is not the latest active version
   * for their (domain, entity_type). Used by re-extraction job.
   */
  def listStaleSchemaFacts(limit: Int = 100): ZIO[ZConnection, AppError, List[UUID]]

object FactRepository:
  val live: ZLayer[Any, Nothing, FactRepository] =
    ZLayer.succeed(FactRepositoryLive())
```

---

### `SchemaRepository.scala`

```scala
package com.myassistant.db.repositories

import com.myassistant.domain.EntityTypeSchema
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

trait SchemaRepository:
  def findById(id: UUID): ZIO[ZConnection, AppError, Option[EntityTypeSchema]]

  def findCurrent(domain: String, entityType: String): ZIO[ZConnection, AppError, Option[EntityTypeSchema]]

  def listAll(
    domain:     Option[String] = None,
    activeOnly: Boolean = true
  ): ZIO[ZConnection, AppError, List[EntityTypeSchema]]

  def insert(schema: EntityTypeSchema): ZIO[ZConnection, AppError, EntityTypeSchema]

  /**
   * Deactivates all active versions for (domain, entityType),
   * then inserts a new row with bumped schema_version.
   * Both operations execute in the same transaction (caller wraps in transact).
   */
  def evolve(
    domain:            String,
    entityType:        String,
    newFieldDefs:      io.circe.Json,
    extractionPrompt:  String,
    changeDescription: String
  ): ZIO[ZConnection, AppError, EntityTypeSchema]

  def deactivate(id: UUID): ZIO[ZConnection, AppError, Unit]

object SchemaRepository:
  val live: ZLayer[Any, Nothing, SchemaRepository] =
    ZLayer.succeed(SchemaRepositoryLive())
```

---

### `ReferenceRepository.scala`

```scala
package com.myassistant.db.repositories

import com.myassistant.domain.{Domain, KinshipAlias, SourceType}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

trait ReferenceRepository:
  def listDomains(): ZIO[ZConnection, AppError, List[Domain]]
  def findDomain(name: String): ZIO[ZConnection, AppError, Option[Domain]]
  def insertDomain(d: Domain): ZIO[ZConnection, AppError, Domain]

  def listSourceTypes(): ZIO[ZConnection, AppError, List[SourceType]]
  def findSourceType(name: String): ZIO[ZConnection, AppError, Option[SourceType]]
  def insertSourceType(s: SourceType): ZIO[ZConnection, AppError, SourceType]

  def listKinshipAliases(language: Option[String] = None): ZIO[ZConnection, AppError, List[KinshipAlias]]

  /**
   * Lookup a kinship alias by exact relation chain and language.
   * Returns None if no alias is registered for this chain.
   */
  def findKinshipAlias(chain: List[String], language: String): ZIO[ZConnection, AppError, Option[KinshipAlias]]

  def insertKinshipAlias(ka: KinshipAlias): ZIO[ZConnection, AppError, KinshipAlias]

object ReferenceRepository:
  val live: ZLayer[Any, Nothing, ReferenceRepository] =
    ZLayer.succeed(ReferenceRepositoryLive())
```

---

### `AuditRepository.scala`

```scala
package com.myassistant.db.repositories

import com.myassistant.domain.{AuditLog, AuditStatus}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

trait AuditRepository:
  def insert(entry: AuditLog): ZIO[ZConnection, AppError, AuditLog]

  def findById(id: UUID): ZIO[ZConnection, AppError, Option[AuditLog]]

  def list(
    personId:  Option[UUID] = None,
    jobType:   Option[String] = None,
    status:    Option[AuditStatus] = None,
    limit:     Int = 50,
    offset:    Int = 0
  ): ZIO[ZConnection, AppError, List[AuditLog]]

object AuditRepository:
  val live: ZLayer[Any, Nothing, AuditRepository] =
    ZLayer.succeed(AuditRepositoryLive())
```

---

## 10. Package: `com.myassistant.services`

Services depend on repositories and the `ZConnectionPool`. Every method that needs atomicity wraps repo calls via `ZConnectionPool#transaction(...)`.

---

### `PersonService.scala`

```scala
package com.myassistant.services

import com.myassistant.domain.Person
import com.myassistant.errors.*
import zio.*
import zio.jdbc.*
import java.util.UUID

trait PersonService:
  def getById(id: UUID): ZIO[Any, AppError, Person]
  def list(fullNameLike: Option[String], gender: Option[String], hasUserIdentifier: Option[Boolean], limit: Int, offset: Int): ZIO[Any, AppError, List[Person]]
  def create(fullName: String, gender: String, dateOfBirth: Option[java.time.LocalDate], preferredName: Option[String], userIdentifier: Option[String]): ZIO[Any, AppError, Person]
  def update(id: UUID, fullName: Option[String], preferredName: Option[String], dateOfBirth: Option[java.time.LocalDate], gender: Option[String]): ZIO[Any, AppError, Person]
  def delete(id: UUID): ZIO[Any, AppError, Unit]

object PersonService:
  val live: ZLayer[PersonRepository & ZConnectionPool, Nothing, PersonService] =
    ZLayer.fromFunction(PersonServiceLive(_, _))

final class PersonServiceLive(
  repo: PersonRepository,
  pool: ZConnectionPool
) extends PersonService:
  def getById(id: UUID) =
    pool.transaction(repo.findById(id))
        .flatMap(ZIO.fromOption(_).mapError(_ => NotFoundError("Person", id.toString)))

  def delete(id: UUID) =
    pool.transaction {
      for
        refs <- repo.checkReferences(id)
        _    <- ZIO.when(refs.nonEmpty)(ZIO.fail(ReferencedError("Person", id.toString, refs)))
        _    <- repo.delete(id)
      yield ()
    }
  // ... other method implementations
```

---

### `HouseholdService.scala`

```scala
package com.myassistant.services

import com.myassistant.domain.Household
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

trait HouseholdService:
  def getById(id: UUID): ZIO[Any, AppError, Household]
  def list(nameLike: Option[String], limit: Int, offset: Int): ZIO[Any, AppError, List[Household]]
  def create(name: String): ZIO[Any, AppError, Household]
  def update(id: UUID, name: String): ZIO[Any, AppError, Household]
  def delete(id: UUID): ZIO[Any, AppError, Unit]

object HouseholdService:
  val live: ZLayer[HouseholdRepository & ZConnectionPool, Nothing, HouseholdService] =
    ZLayer.fromFunction(HouseholdServiceLive(_, _))
```

---

### `PersonHouseholdService.scala`

```scala
package com.myassistant.services

import com.myassistant.domain.PersonHousehold
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

trait PersonHouseholdService:
  def listByPerson(personId: UUID): ZIO[Any, AppError, List[PersonHousehold]]
  def listByHousehold(householdId: UUID): ZIO[Any, AppError, List[PersonHousehold]]
  def addMember(personId: UUID, householdId: UUID): ZIO[Any, AppError, PersonHousehold]
  def removeMember(personId: UUID, householdId: UUID): ZIO[Any, AppError, Unit]

object PersonHouseholdService:
  val live: ZLayer[PersonHouseholdRepository & PersonRepository & HouseholdRepository & ZConnectionPool, Nothing, PersonHouseholdService] =
    ZLayer.fromFunction(PersonHouseholdServiceLive(_, _, _, _))
```

---

### `RelationshipService.scala`

```scala
package com.myassistant.services

import com.myassistant.domain.Relationship
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

final case class KinshipResult(
  chain:       List[String],   // e.g. List("father", "sister")
  alias:       Option[String], // e.g. Some("bua")
  description: String          // e.g. "father's sister"
)

trait RelationshipService:
  def getById(id: UUID): ZIO[Any, AppError, Relationship]
  def listByPerson(personId: UUID): ZIO[Any, AppError, List[Relationship]]
  def create(personIdA: UUID, personIdB: UUID, relationType: String): ZIO[Any, AppError, Relationship]
  def delete(id: UUID): ZIO[Any, AppError, Unit]

  /**
   * Resolve the kinship between two persons.
   * Uses BFS to find the shortest relation chain, then looks up KinshipAlias.
   * Returns None if no path exists within maxDepth hops.
   */
  def resolveKinship(fromPersonId: UUID, toPersonId: UUID, language: String, maxDepth: Int = 6): ZIO[Any, AppError, Option[KinshipResult]]

object RelationshipService:
  val live: ZLayer[RelationshipRepository & ReferenceRepository & ZConnectionPool, Nothing, RelationshipService] =
    ZLayer.fromFunction(RelationshipServiceLive(_, _, _))
```

---

### `KinshipResolver.scala`

```scala
package com.myassistant.services

import com.myassistant.db.repositories.{ReferenceRepository, RelationshipRepository}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

/**
 * BFS-based kinship resolution over the relationship graph.
 *
 * Algorithm:
 * 1. Start from `fromPersonId` with an empty chain.
 * 2. At each BFS frontier, call RelationshipRepository.fetchEdges(frontier)
 *    to bulk-load all outgoing edges in one query.
 * 3. For each edge, extend the chain by appending the relation_type.
 * 4. If `toPersonId` is reached, return the chain.
 * 5. Stop at maxDepth to prevent infinite loops on cyclic graphs.
 *
 * After finding the chain, calls ReferenceRepository.findKinshipAlias
 * to get the language-specific alias. If no alias exists, builds a
 * plain-English description by joining chain elements with "'s ".
 *
 * This is intentionally NOT a recursive CTE — BFS in application code
 * gives us full control over depth limits and early termination.
 * For very deep graphs (> 6 hops) a recursive CTE could be used instead.
 */
object KinshipResolver:
  def resolve(
    fromPersonId: UUID,
    toPersonId:   UUID,
    language:     String,
    maxDepth:     Int,
    relRepo:      RelationshipRepository,
    refRepo:      ReferenceRepository
  ): ZIO[ZConnection, AppError, Option[KinshipResult]] =

    case class State(
      frontier:  Set[UUID],
      visited:   Set[UUID],
      paths:     Map[UUID, List[String]]   // personId -> chain to reach it
    )

    def bfsStep(state: State, depth: Int): ZIO[ZConnection, AppError, Option[KinshipResult]] =
      if depth > maxDepth || state.frontier.isEmpty then ZIO.none
      else
        for
          edges <- relRepo.fetchEdges(state.frontier)
          result <- ZIO.foldLeft(edges)(Option.empty[KinshipResult]) { (acc, edge) =>
            if acc.isDefined then ZIO.succeed(acc)
            else
              val parentChain = state.paths.getOrElse(edge.personIdA, Nil)
              val newChain    = parentChain :+ edge.relationType.toString.toLowerCase
              if edge.personIdB == toPersonId then
                refRepo.findKinshipAlias(newChain, language).map { aliasOpt =>
                  val description = newChain.mkString("'s ")
                  Some(KinshipResult(newChain, aliasOpt.map(_.alias), description))
                }
              else ZIO.succeed(None)
          }
          finalResult <-
            if result.isDefined then ZIO.succeed(result)
            else
              val newFrontier = edges
                .map(_.personIdB)
                .filterNot(state.visited.contains)
                .toSet
              val newPaths = edges.foldLeft(state.paths) { (m, e) =>
                val chain = state.paths.getOrElse(e.personIdA, Nil) :+
                            e.relationType.toString.toLowerCase
                m + (e.personIdB -> chain)
              }
              bfsStep(State(newFrontier, state.visited ++ newFrontier, newPaths), depth + 1)
        yield finalResult

    val initial = State(
      frontier = Set(fromPersonId),
      visited  = Set(fromPersonId),
      paths    = Map(fromPersonId -> Nil)
    )
    bfsStep(initial, 0)
```

---

### `DocumentService.scala`

```scala
package com.myassistant.services

import com.myassistant.domain.Document
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

trait DocumentService:
  def getById(id: UUID): ZIO[Any, AppError, Document]
  def list(personId: Option[UUID], householdId: Option[UUID], sourceType: Option[String], limit: Int, offset: Int): ZIO[Any, AppError, List[Document]]
  def create(personId: Option[UUID], householdId: Option[UUID], contentText: String, sourceType: String, files: List[domain.FileAttachment], supersedesIds: List[UUID]): ZIO[Any, AppError, Document]
  def searchBySimilarity(embedding: Vector[Float], personId: Option[UUID], householdId: Option[UUID], sourceType: Option[String], threshold: Double, limit: Int): ZIO[Any, AppError, List[(Document, Double)]]
  def updateEmbedding(id: UUID, embedding: Vector[Float]): ZIO[Any, AppError, Unit]

object DocumentService:
  val live: ZLayer[DocumentRepository & ZConnectionPool, Nothing, DocumentService] =
    ZLayer.fromFunction(DocumentServiceLive(_, _))
```

---

### `FactService.scala`

```scala
package com.myassistant.services

import com.myassistant.domain.{CurrentFact, Fact}
import com.myassistant.errors.AppError
import io.circe.Json
import zio.*
import zio.jdbc.*
import java.util.UUID

trait FactService:
  def createFact(documentId: UUID, schemaId: UUID, entityInstanceId: Option[UUID], operationType: String, fields: Json): ZIO[Any, AppError, Fact]
  def getHistory(entityInstanceId: UUID): ZIO[Any, AppError, List[Fact]]
  def getCurrent(entityInstanceId: UUID): ZIO[Any, AppError, CurrentFact]
  def listCurrent(domain: Option[String], entityType: Option[String], personId: Option[UUID], householdId: Option[UUID], limit: Int, offset: Int): ZIO[Any, AppError, List[CurrentFact]]
  def searchCurrent(embedding: Vector[Float], domain: Option[String], entityType: Option[String], personId: Option[UUID], threshold: Double, limit: Int): ZIO[Any, AppError, List[CurrentFact]]
  def updateEmbedding(id: UUID, embedding: Vector[Float]): ZIO[Any, AppError, Unit]
  def listStaleFacts(limit: Int): ZIO[Any, AppError, List[UUID]]

object FactService:
  val live: ZLayer[FactRepository & SchemaRepository & DocumentRepository & ZConnectionPool, Nothing, FactService] =
    ZLayer.fromFunction(FactServiceLive(_, _, _, _))
```

`FactServiceLive.createFact` logic:

1. Validate `operationType` parses to `OperationType`.
2. Verify `schemaId` exists and `is_active = true` via `SchemaRepository.findById`.
3. Verify `documentId` exists via `DocumentRepository.findById`.
4. For `create`: generate new `entityInstanceId` UUID if caller provides `None`.
5. For `update`/`delete`: require `entityInstanceId` to be provided; verify it has an existing `create` row via `FactRepository.getCurrentFact`.
6. Wrap insert in `pool.transaction(repo.insertFact(...))`.

---

### `SchemaService.scala`

```scala
package com.myassistant.services

import com.myassistant.domain.EntityTypeSchema
import com.myassistant.errors.AppError
import io.circe.Json
import zio.*
import zio.jdbc.*
import java.util.UUID

trait SchemaService:
  def getById(id: UUID): ZIO[Any, AppError, EntityTypeSchema]
  def getCurrent(domain: String, entityType: String): ZIO[Any, AppError, EntityTypeSchema]
  def list(domain: Option[String], activeOnly: Boolean): ZIO[Any, AppError, List[EntityTypeSchema]]
  def create(domain: String, entityType: String, description: String, fieldDefinitions: Json, extractionPrompt: String): ZIO[Any, AppError, EntityTypeSchema]
  def evolve(domain: String, entityType: String, fieldDefinitions: Json, extractionPrompt: String, changeDescription: String): ZIO[Any, AppError, EntityTypeSchema]
  def deactivate(id: UUID): ZIO[Any, AppError, Unit]

object SchemaService:
  val live: ZLayer[SchemaRepository & ReferenceRepository & ZConnectionPool, Nothing, SchemaService] =
    ZLayer.fromFunction(SchemaServiceLive(_, _, _))
```

`SchemaServiceLive.create`:

1. Validate `domain` exists in `ReferenceRepository.findDomain`.
2. Check no active schema exists for `(domain, entityType)` — return `ConflictError` if one does.
3. Validate `fieldDefinitions` JSON array: every element must have `name`, `type` (one of `text|number|date|boolean|file`), `mandatory`, `description`.
4. Insert at `schema_version = 1`.

`SchemaServiceLive.evolve`:

1. Verify current schema exists.
2. Call `SchemaRepository.evolve` inside a single transaction (it deactivates old + inserts new).

---

### `ReferenceService.scala`

```scala
package com.myassistant.services

import com.myassistant.domain.{Domain, KinshipAlias, SourceType}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

trait ReferenceService:
  def listDomains(): ZIO[Any, AppError, List[Domain]]
  def createDomain(name: String, description: String): ZIO[Any, AppError, Domain]
  def listSourceTypes(): ZIO[Any, AppError, List[SourceType]]
  def createSourceType(name: String, description: String): ZIO[Any, AppError, SourceType]
  def listKinshipAliases(language: Option[String]): ZIO[Any, AppError, List[KinshipAlias]]
  def createKinshipAlias(chain: List[String], language: String, alias: String, description: Option[String]): ZIO[Any, AppError, KinshipAlias]

object ReferenceService:
  val live: ZLayer[ReferenceRepository & ZConnectionPool, Nothing, ReferenceService] =
    ZLayer.fromFunction(ReferenceServiceLive(_, _))
```

---

### `AuditService.scala`

```scala
package com.myassistant.services

import com.myassistant.domain.{AuditLog, AuditStatus}
import com.myassistant.errors.AppError
import io.circe.Json
import zio.*
import zio.jdbc.*
import java.util.UUID

trait AuditService:
  def log(personId: Option[UUID], jobType: Option[String], message: String, response: Option[String], toolCalls: Json, status: AuditStatus, error: Option[String]): ZIO[Any, AppError, AuditLog]
  def getById(id: UUID): ZIO[Any, AppError, AuditLog]
  def list(personId: Option[UUID], jobType: Option[String], status: Option[AuditStatus], limit: Int, offset: Int): ZIO[Any, AppError, List[AuditLog]]

object AuditService:
  val live: ZLayer[AuditRepository & ZConnectionPool, Nothing, AuditService] =
    ZLayer.fromFunction(AuditServiceLive(_, _))
```

---

### `FileService.scala`

```scala
package com.myassistant.services

import com.myassistant.config.FileStorageConfig
import com.myassistant.errors.AppError
import zio.*
import java.nio.file.Path
import java.util.UUID

final case class StoredFile(
  filePath:         String,   // relative path from basePath
  fileType:         String,
  originalFilename: Option[String],
  sizeBytes:        Long
)

trait FileService:
  /**
   * Persist bytes to basePath/<personId>/<uuid>.<ext>.
   * Returns the stored file metadata including the relative filePath.
   * filePath is what gets stored in document.files[].file_path.
   */
  def store(personId: UUID, bytes: Array[Byte], originalFilename: String, fileType: String): ZIO[Any, AppError, StoredFile]

  def retrieve(filePath: String): ZIO[Any, AppError, Array[Byte]]

  /**
   * Deletes a file from disk.
   * Before deleting, checks DocumentRepository to ensure no document
   * references this filePath in its files array.
   * Fails with ReferencedError if references exist.
   */
  def delete(filePath: String): ZIO[Any, AppError, Unit]

  def exists(filePath: String): ZIO[Any, AppError, Boolean]

object FileService:
  val live: ZLayer[FileStorageConfig & DocumentRepository & ZConnectionPool, Nothing, FileService] =
    ZLayer.fromFunction(FileServiceLive(_, _, _))
```

---

## 11. Package: `com.myassistant.api.models`

All request/response case classes. All derive Circe `Codec` for automatic JSON encode/decode.

### `CommonModels.scala`

```scala
package com.myassistant.api.models

import io.circe.generic.semiauto.*
import io.circe.{Codec, Decoder, Encoder}

final case class PaginationParams(
  limit:  Int = 50,
  offset: Int = 0
)

/** Generic error response for 4xx/5xx. */
final case class ErrorResponse(
  error:   String,
  message: String
) derives Codec.AsObject

/**
 * Error response for 409 Conflict when a delete is blocked.
 * blockedBy lists the table.column references preventing deletion.
 */
final case class ReferencedErrorResponse(
  error:     String,
  message:   String,
  blockedBy: List[String]
) derives Codec.AsObject
```

### `PersonModels.scala`

```scala
package com.myassistant.api.models

import io.circe.Codec
import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

final case class CreatePersonRequest(
  fullName:       String,
  gender:         String,
  dateOfBirth:    Option[LocalDate],
  preferredName:  Option[String],
  userIdentifier: Option[String]
) derives Codec.AsObject

final case class UpdatePersonRequest(
  fullName:      Option[String],
  preferredName: Option[String],
  dateOfBirth:   Option[LocalDate],
  gender:        Option[String]
) derives Codec.AsObject

final case class PersonResponse(
  id:             UUID,
  fullName:       String,
  gender:         String,
  dateOfBirth:    Option[LocalDate],
  preferredName:  Option[String],
  userIdentifier: Option[String],
  createdAt:      OffsetDateTime,
  updatedAt:      OffsetDateTime
) derives Codec.AsObject

final case class PersonListResponse(
  items: List[PersonResponse],
  total: Int
) derives Codec.AsObject
```

### `HouseholdModels.scala`

```scala
package com.myassistant.api.models

import io.circe.Codec
import java.time.OffsetDateTime
import java.util.UUID

final case class CreateHouseholdRequest(name: String) derives Codec.AsObject
final case class UpdateHouseholdRequest(name: String) derives Codec.AsObject

final case class HouseholdResponse(
  id:        UUID,
  name:      String,
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime
) derives Codec.AsObject

final case class HouseholdListResponse(items: List[HouseholdResponse], total: Int) derives Codec.AsObject
```

### `RelationshipModels.scala`

```scala
package com.myassistant.api.models

import io.circe.Codec
import java.time.OffsetDateTime
import java.util.UUID

final case class CreateRelationshipRequest(
  personIdA:    UUID,
  personIdB:    UUID,
  relationType: String
) derives Codec.AsObject

final case class RelationshipResponse(
  id:           UUID,
  personIdA:    UUID,
  personIdB:    UUID,
  relationType: String,
  createdAt:    OffsetDateTime,
  updatedAt:    OffsetDateTime
) derives Codec.AsObject

final case class RelationshipListResponse(items: List[RelationshipResponse]) derives Codec.AsObject

final case class ResolveKinshipRequest(
  fromPersonId: UUID,
  toPersonId:   UUID,
  language:     String,
  maxDepth:     Option[Int]
) derives Codec.AsObject

final case class KinshipResponse(
  chain:       List[String],
  alias:       Option[String],
  description: String
) derives Codec.AsObject
```

### `DocumentModels.scala`

```scala
package com.myassistant.api.models

import io.circe.Codec
import io.circe.generic.semiauto.*
import java.time.OffsetDateTime
import java.util.UUID

final case class FileAttachmentRequest(
  filePath:         String,
  fileType:         String,
  originalFilename: Option[String]
) derives Codec.AsObject

final case class CreateDocumentRequest(
  personId:      Option[UUID],
  householdId:   Option[UUID],
  contentText:   String,
  sourceType:    String,
  files:         List[FileAttachmentRequest] = Nil,
  supersedesIds: List[UUID] = Nil
) derives Codec.AsObject

final case class DocumentSearchRequest(
  embedding:   List[Float],   // 1536 floats
  personId:    Option[UUID],
  householdId: Option[UUID],
  sourceType:  Option[String],
  threshold:   Option[Double],
  limit:       Option[Int]
) derives Codec.AsObject

final case class DocumentResponse(
  id:            UUID,
  personId:      Option[UUID],
  householdId:   Option[UUID],
  contentText:   String,
  sourceType:    String,
  files:         List[FileAttachmentRequest],
  supersedesIds: List[UUID],
  hasEmbedding:  Boolean,
  createdAt:     OffsetDateTime
) derives Codec.AsObject

final case class DocumentListResponse(items: List[DocumentResponse], total: Int) derives Codec.AsObject

final case class DocumentSearchResponse(
  items: List[DocumentWithScore]
) derives Codec.AsObject

final case class DocumentWithScore(
  document:        DocumentResponse,
  similarityScore: Double
) derives Codec.AsObject
```

### `FactModels.scala`

```scala
package com.myassistant.api.models

import io.circe.{Codec, Json}
import java.time.OffsetDateTime
import java.util.UUID

final case class CreateFactRequest(
  documentId:       UUID,
  schemaId:         UUID,
  entityInstanceId: Option[UUID],   // None = new entity; Some = existing entity
  operationType:    String,          // create | update | delete
  fields:           Json
) derives Codec.AsObject

final case class FactResponse(
  id:               UUID,
  documentId:       UUID,
  schemaId:         UUID,
  entityInstanceId: UUID,
  operationType:    String,
  fields:           Json,
  hasEmbedding:     Boolean,
  createdAt:        OffsetDateTime
) derives Codec.AsObject

final case class CurrentFactResponse(
  entityInstanceId: UUID,
  schemaId:         UUID,
  documentId:       UUID,
  currentFields:    Json,
  createdAt:        OffsetDateTime,
  updatedAt:        OffsetDateTime,
  similarityScore:  Option[Double]
) derives Codec.AsObject

final case class CurrentFactListResponse(items: List[CurrentFactResponse], total: Int) derives Codec.AsObject

final case class FactSearchRequest(
  embedding:  List[Float],
  domain:     Option[String],
  entityType: Option[String],
  personId:   Option[UUID],
  threshold:  Option[Double],
  limit:      Option[Int]
) derives Codec.AsObject

final case class FactHistoryResponse(items: List[FactResponse]) derives Codec.AsObject

final case class UpdateEmbeddingRequest(embedding: List[Float]) derives Codec.AsObject
```

### `SchemaModels.scala`

```scala
package com.myassistant.api.models

import io.circe.{Codec, Json}
import java.time.OffsetDateTime
import java.util.UUID

final case class CreateSchemaRequest(
  domain:           String,
  entityType:       String,
  description:      String,
  fieldDefinitions: Json,
  extractionPrompt: String
) derives Codec.AsObject

final case class EvolveSchemaRequest(
  fieldDefinitions:  Json,
  extractionPrompt:  String,
  changeDescription: String
) derives Codec.AsObject

final case class FieldDefinitionResponse(
  name:        String,
  `type`:      String,
  mandatory:   Boolean,
  description: String
) derives Codec.AsObject

final case class SchemaResponse(
  id:                UUID,
  domain:            String,
  entityType:        String,
  schemaVersion:     Int,
  description:       String,
  fieldDefinitions:  List[FieldDefinitionResponse],
  mandatoryFields:   List[String],
  extractionPrompt:  String,
  isActive:          Boolean,
  changeDescription: Option[String],
  createdAt:         OffsetDateTime
) derives Codec.AsObject

final case class SchemaListResponse(items: List[SchemaResponse]) derives Codec.AsObject
```

### `ReferenceModels.scala`

```scala
package com.myassistant.api.models

import io.circe.Codec
import java.time.OffsetDateTime

final case class CreateDomainRequest(name: String, description: String) derives Codec.AsObject
final case class DomainResponse(name: String, description: String, createdAt: OffsetDateTime) derives Codec.AsObject
final case class DomainListResponse(items: List[DomainResponse]) derives Codec.AsObject

final case class CreateSourceTypeRequest(name: String, description: String) derives Codec.AsObject
final case class SourceTypeResponse(name: String, description: String, createdAt: OffsetDateTime) derives Codec.AsObject
final case class SourceTypeListResponse(items: List[SourceTypeResponse]) derives Codec.AsObject

final case class CreateKinshipAliasRequest(chain: List[String], language: String, alias: String, description: Option[String]) derives Codec.AsObject
final case class KinshipAliasResponse(id: Int, chain: List[String], language: String, alias: String, description: Option[String], createdAt: OffsetDateTime) derives Codec.AsObject
final case class KinshipAliasListResponse(items: List[KinshipAliasResponse]) derives Codec.AsObject
```

### `AuditModels.scala`

```scala
package com.myassistant.api.models

import io.circe.{Codec, Json}
import java.time.OffsetDateTime
import java.util.UUID

final case class CreateAuditLogRequest(
  personId:  Option[UUID],
  jobType:   Option[String],
  message:   String,
  response:  Option[String],
  toolCalls: Json,
  status:    String,
  error:     Option[String]
) derives Codec.AsObject

final case class AuditLogResponse(
  id:        UUID,
  personId:  Option[UUID],
  jobType:   Option[String],
  message:   String,
  response:  Option[String],
  toolCalls: Json,
  status:    String,
  error:     Option[String],
  createdAt: OffsetDateTime
) derives Codec.AsObject

final case class AuditLogListResponse(items: List[AuditLogResponse], total: Int) derives Codec.AsObject
```

### `FileModels.scala`

```scala
package com.myassistant.api.models

import io.circe.Codec
import java.util.UUID

final case class FileUploadResponse(
  filePath:         String,
  fileType:         String,
  originalFilename: Option[String],
  sizeBytes:        Long
) derives Codec.AsObject
```

---

## 12. Package: `com.myassistant.api.middleware`

ZIO HTTP middleware is expressed as `HttpApp => HttpApp` (a function from Routes to Routes with enriched behavior).

### `AuthMiddleware.scala`

```scala
package com.myassistant.api.middleware

import com.myassistant.config.ServerConfig
import com.myassistant.api.models.ErrorResponse
import io.circe.syntax.*
import zio.*
import zio.http.*

object AuthMiddleware:
  /**
   * Checks the Authorization header for "Bearer <token>".
   * Compares using MessageDigest.isEqual (constant-time) to prevent timing attacks.
   * Returns 401 with ErrorResponse JSON if header is missing or token is wrong.
   * Routes matching /health and /metrics are excluded by the caller (Router).
   */
  def make(cfg: ServerConfig): HttpMiddleware[Any] =
    Middleware.interceptHandler(
      incoming = Handler.fromFunctionZIO { req =>
        val headerToken = req.header(Header.Authorization)
          .collect { case Header.Authorization.Bearer(token) => token.value.toString }
        headerToken match
          case Some(t) if constantTimeEquals(t, cfg.authToken) =>
            ZIO.succeed(req)
          case _ =>
            ZIO.fail(
              Response.json(ErrorResponse("unauthorized", "Missing or invalid Bearer token").asJson.noSpaces)
                      .status(Status.Unauthorized)
            )
      }
    )

  private def constantTimeEquals(a: String, b: String): Boolean =
    java.security.MessageDigest.isEqual(a.getBytes, b.getBytes)
```

### `RequestLoggingMiddleware.scala`

```scala
package com.myassistant.api.middleware

import zio.*
import zio.http.*
import zio.logging.*

object RequestLoggingMiddleware:
  /**
   * Logs each request on receipt and each response on completion.
   * Annotates the fiber with a random traceId so all log lines
   * within one request are correlated.
   * Log fields: traceId, method, path, status, durationMs
   */
  val make: HttpMiddleware[Any] =
    Middleware.interceptHandlerStateful[Any, Nothing, Long](
      incoming = Handler.fromFunctionZIO { req =>
        for
          traceId <- Random.nextUUID.map(_.toString.take(8))
          now     <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          _       <- ZIO.logAnnotate("traceId", traceId)(
                       ZIO.logInfo(s"→ ${req.method} ${req.url.path}")
                     )
        yield (now, req)
      },
      outgoing = Handler.fromFunctionZIO { (startMs, response) =>
        for
          now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          _   <- ZIO.logInfo(s"← ${response.status.code} (${now - startMs}ms)")
        yield response
      }
    )
```

### `MetricsMiddleware.scala`

```scala
package com.myassistant.api.middleware

import com.myassistant.monitoring.Metrics
import zio.*
import zio.http.*
import zio.metrics.*

object MetricsMiddleware:
  /**
   * Records http_requests_total (counter) and
   * http_request_duration_seconds (histogram) for every request.
   * Labels are set from method, URL path, and response status code.
   */
  val make: HttpMiddleware[Any] =
    Middleware.interceptHandlerStateful[Any, Nothing, Long](
      incoming = Handler.fromFunctionZIO { req =>
        Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).map((_, req))
      },
      outgoing = Handler.fromFunctionZIO { (startMs, response) =>
        for
          now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          dur  = (now - startMs).toDouble / 1000.0
          _   <- Metrics.httpRequestsTotal
                   .tagged("status", response.status.code.toString)
                   .increment
          _   <- Metrics.httpRequestDuration
                   .tagged("status", response.status.code.toString)
                   .update(dur)
        yield response
      }
    )
```

### `ErrorHandlerMiddleware.scala`

```scala
package com.myassistant.api.middleware

import com.myassistant.errors.*
import com.myassistant.api.models.{ErrorResponse, ReferencedErrorResponse}
import io.circe.syntax.*
import zio.*
import zio.http.*

object ErrorHandlerMiddleware:
  /**
   * Catches AppError failures from route handlers and maps them
   * to appropriate HTTP status codes with a JSON body.
   *
   *   NotFoundError       -> 404
   *   ValidationError     -> 400
   *   ConflictError       -> 409
   *   ReferencedError     -> 409 (with blockedBy list)
   *   AuthorizationError  -> 401
   *   DatabaseError       -> 500
   *   FileError           -> 500
   */
  val make: HttpMiddleware[Any] =
    Middleware.intercept { (req, handler) =>
      handler(req).catchAll {
        case NotFoundError(resource, id) =>
          ZIO.succeed(Response.json(
            ErrorResponse("not_found", s"$resource $id not found").asJson.noSpaces
          ).status(Status.NotFound))

        case ValidationError(field, msg) =>
          ZIO.succeed(Response.json(
            ErrorResponse("validation_error", s"$field: $msg").asJson.noSpaces
          ).status(Status.BadRequest))

        case ConflictError(_, msg) =>
          ZIO.succeed(Response.json(
            ErrorResponse("conflict", msg).asJson.noSpaces
          ).status(Status.Conflict))

        case ReferencedError(resource, id, blockedBy) =>
          ZIO.succeed(Response.json(
            ReferencedErrorResponse("referenced", s"Cannot delete $resource $id", blockedBy).asJson.noSpaces
          ).status(Status.Conflict))

        case AuthorizationError =>
          ZIO.succeed(Response.json(
            ErrorResponse("unauthorized", "Missing or invalid Bearer token").asJson.noSpaces
          ).status(Status.Unauthorized))

        case DatabaseError(msg, _) =>
          ZIO.logError(s"Database error: $msg") *>
          ZIO.succeed(Response.json(
            ErrorResponse("internal_error", "A database error occurred").asJson.noSpaces
          ).status(Status.InternalServerError))

        case FileError(msg, _) =>
          ZIO.logError(s"File error: $msg") *>
          ZIO.succeed(Response.json(
            ErrorResponse("file_error", "A file operation failed").asJson.noSpaces
          ).status(Status.InternalServerError))
      }
    }
```

---

## 13. Package: `com.myassistant.api.routes`

Each routes object exposes a single `def routes(service: XService): Routes[Any, AppError]`. JSON decoding uses Circe; encoding uses `Response.json(x.asJson.noSpaces)`.

All routes are prefixed with `/api/v1`.

### `PersonRoutes.scala`

```scala
package com.myassistant.api.routes

import com.myassistant.api.models.*
import com.myassistant.services.PersonService
import com.myassistant.errors.AppError
import io.circe.syntax.*
import io.circe.parser.decode
import zio.*
import zio.http.*

object PersonRoutes:
  def routes(svc: PersonService): Routes[Any, AppError] =
    Routes(
      // GET /api/v1/persons?fullName=&gender=&hasUserIdentifier=&limit=&offset=
      Method.GET / "api" / "v1" / "persons" ->
        handler { (req: Request) =>
          for
            fullName           <- ZIO.succeed(req.url.queryParams.get("fullName").flatMap(_.headOption))
            gender             <- ZIO.succeed(req.url.queryParams.get("gender").flatMap(_.headOption))
            hasUserIdentifier  <- ZIO.succeed(req.url.queryParams.get("hasUserIdentifier").flatMap(_.headOption).map(_.toBoolean))
            limit              <- ZIO.succeed(req.url.queryParams.get("limit").flatMap(_.headOption).map(_.toInt).getOrElse(50))
            offset             <- ZIO.succeed(req.url.queryParams.get("offset").flatMap(_.headOption).map(_.toInt).getOrElse(0))
            persons            <- svc.list(fullName, gender, hasUserIdentifier, limit, offset)
            response            = PersonListResponse(persons.map(toResponse), persons.length)
          yield Response.json(response.asJson.noSpaces)
        },

      // GET /api/v1/persons/:id
      Method.GET / "api" / "v1" / "persons" / uuid("id") ->
        handler { (id: java.util.UUID, _: Request) =>
          svc.getById(id).map(p => Response.json(toResponse(p).asJson.noSpaces))
        },

      // POST /api/v1/persons
      Method.POST / "api" / "v1" / "persons" ->
        handler { (req: Request) =>
          for
            body <- req.body.asString
            req  <- ZIO.fromEither(decode[CreatePersonRequest](body))
                       .mapError(e => ValidationError("body", e.getMessage))
            p    <- svc.create(req.fullName, req.gender, req.dateOfBirth, req.preferredName, req.userIdentifier)
          yield Response.json(toResponse(p).asJson.noSpaces).status(Status.Created)
        },

      // PATCH /api/v1/persons/:id
      Method.PATCH / "api" / "v1" / "persons" / uuid("id") ->
        handler { (id: java.util.UUID, req: Request) =>
          for
            body <- req.body.asString
            upd  <- ZIO.fromEither(decode[UpdatePersonRequest](body))
                       .mapError(e => ValidationError("body", e.getMessage))
            p    <- svc.update(id, upd.fullName, upd.preferredName, upd.dateOfBirth, upd.gender)
          yield Response.json(toResponse(p).asJson.noSpaces)
        },

      // DELETE /api/v1/persons/:id
      Method.DELETE / "api" / "v1" / "persons" / uuid("id") ->
        handler { (id: java.util.UUID, _: Request) =>
          svc.delete(id).as(Response.status(Status.NoContent))
        }
    )

  private def toResponse(p: com.myassistant.domain.Person): PersonResponse = ???
```

---

Routes for all other resources follow the same pattern. Below are the method signatures and URL patterns without full implementation bodies.

### `HouseholdRoutes.scala`

```
GET    /api/v1/households          list(nameLike, limit, offset)
GET    /api/v1/households/:id      getById
POST   /api/v1/households          create
PATCH  /api/v1/households/:id      update
DELETE /api/v1/households/:id      delete
```

### `PersonHouseholdRoutes.scala`

```
GET    /api/v1/persons/:personId/households           listByPerson
GET    /api/v1/households/:householdId/members        listByHousehold
POST   /api/v1/persons/:personId/households           addMember (body: {householdId})
DELETE /api/v1/persons/:personId/households/:hId      removeMember
```

### `RelationshipRoutes.scala`

```
GET    /api/v1/relationships/:id       getById
GET    /api/v1/persons/:id/relationships  listByPerson
POST   /api/v1/relationships           create
DELETE /api/v1/relationships/:id       delete
POST   /api/v1/relationships/resolve-kinship  resolveKinship (body: ResolveKinshipRequest)
```

### `DocumentRoutes.scala`

```
GET    /api/v1/documents              listFiltered(personId, householdId, sourceType, limit, offset)
GET    /api/v1/documents/:id          getById
POST   /api/v1/documents              create
POST   /api/v1/documents/search       searchBySimilarity (body: DocumentSearchRequest)
PATCH  /api/v1/documents/:id/embedding  updateEmbedding (body: UpdateEmbeddingRequest)
```

### `FactRoutes.scala`

```
POST   /api/v1/facts                          createFact
GET    /api/v1/facts/current                  listCurrentFacts(domain, entityType, personId, householdId, limit, offset)
GET    /api/v1/facts/current/:entityInstanceId  getCurrentFact
GET    /api/v1/facts/history/:entityInstanceId  getHistory
POST   /api/v1/facts/search                   searchCurrentFacts (body: FactSearchRequest)
PATCH  /api/v1/facts/:id/embedding            updateEmbedding (body: UpdateEmbeddingRequest)
GET    /api/v1/facts/stale                    listStaleFacts(limit)
```

### `SchemaRoutes.scala`

```
GET    /api/v1/schemas                        list(domain, activeOnly)
GET    /api/v1/schemas/:id                    getById
GET    /api/v1/schemas/current/:domain/:type  getCurrent
POST   /api/v1/schemas                        create
POST   /api/v1/schemas/:domain/:type/evolve   evolve (body: EvolveSchemaRequest)
DELETE /api/v1/schemas/:id                    deactivate
```

### `ReferenceRoutes.scala`

```
GET    /api/v1/reference/domains              listDomains
POST   /api/v1/reference/domains              createDomain
GET    /api/v1/reference/source-types         listSourceTypes
POST   /api/v1/reference/source-types         createSourceType
GET    /api/v1/reference/kinship-aliases      listKinshipAliases(language)
POST   /api/v1/reference/kinship-aliases      createKinshipAlias
```

### `AuditRoutes.scala`

```
POST   /api/v1/audit              log (body: CreateAuditLogRequest)
GET    /api/v1/audit/:id          getById
GET    /api/v1/audit              list(personId, jobType, status, limit, offset)
```

### `FileRoutes.scala`

```
POST   /api/v1/files/:personId    upload  (multipart/form-data; fields: file, fileType)
GET    /api/v1/files/*filePath    download
DELETE /api/v1/files/*filePath    delete
```

### `HealthRoutes.scala`

```scala
package com.myassistant.api.routes

import zio.http.*

object HealthRoutes:
  val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "health" ->
        handler { (_: Request) =>
          ZIO.succeed(Response.json("""{"status":"ok"}"""))
        }
    )
```

---

## 14. Package: `com.myassistant.api`

### `Router.scala`

```scala
package com.myassistant.api

import com.myassistant.api.middleware.*
import com.myassistant.api.routes.*
import com.myassistant.config.ServerConfig
import com.myassistant.monitoring.{MetricsExporter, MetricsRegistry}
import com.myassistant.services.*
import zio.*
import zio.http.*

object Router:
  /**
   * Assembles all routes and applies middleware.
   *
   * Middleware application order (outermost to innermost):
   *   ErrorHandler  — converts AppError to HTTP response; must be outermost
   *   Metrics       — records counters/histograms; wraps all routes
   *   Logging       — logs method/path/status/duration
   *   Auth          — checks Bearer token; applied ONLY to /api/* routes
   *
   * /health and /metrics are excluded from Auth middleware.
   */
  def make(
    serverConfig:    ServerConfig,
    personSvc:       PersonService,
    householdSvc:    HouseholdService,
    phSvc:           PersonHouseholdService,
    relSvc:          RelationshipService,
    documentSvc:     DocumentService,
    factSvc:         FactService,
    schemaSvc:       SchemaService,
    referenceSvc:    ReferenceService,
    auditSvc:        AuditService,
    fileSvc:         FileService,
    metricsRegistry: MetricsRegistry
  ): HttpApp[Any] =

    val publicRoutes: Routes[Any, Nothing] =
      HealthRoutes.routes ++
      MetricsExporter.routes.provideEnvironment(ZEnvironment(metricsRegistry))

    val protectedRoutes: Routes[Any, AppError] =
      PersonRoutes.routes(personSvc)          ++
      HouseholdRoutes.routes(householdSvc)    ++
      PersonHouseholdRoutes.routes(phSvc)     ++
      RelationshipRoutes.routes(relSvc)       ++
      DocumentRoutes.routes(documentSvc)      ++
      FactRoutes.routes(factSvc)              ++
      SchemaRoutes.routes(schemaSvc)          ++
      ReferenceRoutes.routes(referenceSvc)    ++
      AuditRoutes.routes(auditSvc)            ++
      FileRoutes.routes(fileSvc)

    val withAuth    = protectedRoutes @@ AuthMiddleware.make(serverConfig)
    val allRoutes   = publicRoutes ++ withAuth.handleError(identity)
    val withMetrics = allRoutes    @@ MetricsMiddleware.make
    val withLogging = withMetrics  @@ RequestLoggingMiddleware.make
    val withErrors  = withLogging  @@ ErrorHandlerMiddleware.make

    withErrors.toHttpApp
```

---

## 15. `Main.scala`

```scala
package com.myassistant

import com.myassistant.api.Router
import com.myassistant.config.AppConfig
import com.myassistant.db.DatabaseModule
import com.myassistant.db.migrations.MigrationRunner
import com.myassistant.db.repositories.*
import com.myassistant.monitoring.MetricsRegistry
import com.myassistant.services.*
import com.myassistant.logging.AppLogger
import zio.*
import zio.http.*
import zio.jdbc.*

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    AppLogger.live

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    val program = for
      cfg        <- ZIO.service[AppConfig]
      _          <- MigrationRunner.run.provide(ZLayer.succeed(cfg.database))
      _          <- ZIO.logInfo("Database migrations complete")

      // Build the HTTP app from assembled layers
      app        <- ZIO.service[HttpApp[Any]]

      _          <- ZIO.logInfo(s"Starting HTTP server on ${cfg.server.host}:${cfg.server.port}")
      _          <- Server.serve(app).provide(
                      Server.defaultWithPort(cfg.server.port)
                    )
    yield ()

    program.provide(
      AppConfig.live,

      // DB
      DatabaseModule.live,

      // Repositories
      PersonRepository.live,
      HouseholdRepository.live,
      PersonHouseholdRepository.live,
      RelationshipRepository.live,
      DocumentRepository.live,
      FactRepository.live,
      SchemaRepository.live,
      ReferenceRepository.live,
      AuditRepository.live,

      // Services
      PersonService.live,
      HouseholdService.live,
      PersonHouseholdService.live,
      RelationshipService.live,
      DocumentService.live,
      FactService.live,
      SchemaService.live,
      ReferenceService.live,
      AuditService.live,
      FileService.live,

      // Monitoring
      MetricsRegistry.live,

      // Router (assembled HttpApp)
      ZLayer.fromZIO {
        for
          cfg     <- ZIO.service[AppConfig]
          pSvc    <- ZIO.service[PersonService]
          hSvc    <- ZIO.service[HouseholdService]
          phSvc   <- ZIO.service[PersonHouseholdService]
          rSvc    <- ZIO.service[RelationshipService]
          dSvc    <- ZIO.service[DocumentService]
          fSvc    <- ZIO.service[FactService]
          sSvc    <- ZIO.service[SchemaService]
          refSvc  <- ZIO.service[ReferenceService]
          aSvc    <- ZIO.service[AuditService]
          fileSvc <- ZIO.service[FileService]
          mReg    <- ZIO.service[MetricsRegistry]
        yield Router.make(cfg.server, pSvc, hSvc, phSvc, rSvc, dSvc, fSvc, sSvc, refSvc, aSvc, fileSvc, mReg)
      }
    )
```

---

## 16. ZIO Layer Wiring Diagram

```
ZIOAppArgs
    │
    ▼
AppConfig.live                          (reads HOCON from application.conf)
    ├── ServerConfig
    ├── DatabaseConfig ──────────────────┐
    └── FileStorageConfig ───────────────┼──────────────────────┐
                                         │                       │
                                         ▼                       │
DatabaseModule.live                      │                       │
(ZConnectionPool)   ◄────────────────────┘                       │
    │                                                             │
    ├── PersonRepository.live ──────────────► PersonService.live
    ├── HouseholdRepository.live ───────────► HouseholdService.live
    ├── PersonHouseholdRepository.live ─────► PersonHouseholdService.live
    │       + PersonRepository               (also needs PersonRepo, HouseholdRepo)
    │       + HouseholdRepository
    ├── RelationshipRepository.live ────────► RelationshipService.live
    │       + ReferenceRepository
    ├── DocumentRepository.live ────────────► DocumentService.live
    ├── FactRepository.live ─────────────────► FactService.live
    │       + SchemaRepository                 (also needs SchemaRepo, DocumentRepo)
    │       + DocumentRepository
    ├── SchemaRepository.live ───────────────► SchemaService.live
    │       + ReferenceRepository
    ├── ReferenceRepository.live ───────────► ReferenceService.live
    ├── AuditRepository.live ────────────────► AuditService.live
    └── (DocumentRepository) ────────────────► FileService.live
                                                + FileStorageConfig ◄──────┘

MetricsRegistry.live ──────────────────────────────────┐
                                                        │
All Services + ServerConfig + MetricsRegistry ──────► Router (HttpApp)
                                                        │
                                                    Server.serve
```

---

## 17. Request Flow Walkthrough

**Request:** `POST /api/v1/facts`  
**Body:**
```json
{
  "documentId":    "...",
  "schemaId":      "...",
  "entityInstanceId": null,
  "operationType": "create",
  "fields": {"title": "renew passport", "status": "open"}
}
```

1. **TCP accept** — ZIO HTTP Netty layer accepts the TCP connection and parses the HTTP request.

2. **ErrorHandlerMiddleware** (outermost) — registers a `catchAll` on the downstream effect. No work yet.

3. **RequestLoggingMiddleware** — generates a random `traceId`, annotates the current fiber, logs `→ POST /api/v1/facts`.

4. **MetricsMiddleware** — records `startMs = now()`. Registers a finalizer to emit metrics when the response is produced.

5. **AuthMiddleware** — reads the `Authorization` header. Extracts the Bearer token. Calls `constantTimeEquals(token, cfg.authToken)`. Succeeds; passes request downstream.

6. **FactRoutes** — matches `Method.POST / "api" / "v1" / "facts"`. Reads request body as `String`. Calls `decode[CreateFactRequest](body)`. Calls `FactService.createFact(...)`.

7. **FactServiceLive.createFact**:
   a. Parses `"create"` to `OperationType.Create`.
   b. Generates a new `entityInstanceId` UUID (since input is `None`).
   c. Calls `pool.transaction { for schemaOpt <- schemaRepo.findById(schemaId); docOpt <- docRepo.findById(documentId); ... factRepo.insertFact(fact) }` — acquires a connection from HikariCP, runs all queries in one transaction.
   d. Returns the inserted `Fact`.

8. **FactRepository.insertFact** — executes:
   ```sql
   INSERT INTO fact (id, document_id, schema_id, entity_instance_id,
                     operation_type, fields, created_at)
   VALUES ($1, $2, $3, $4, $castEnum, $5::jsonb, now())
   RETURNING *
   ```
   Uses ZIO JDBC `sql` interpolator with custom `JdbcDecoder[Fact]`.

9. Response bubbles back up through the middleware stack:
   - **MetricsMiddleware** finalizer fires: increments `http_requests_total{method="POST",path="/api/v1/facts",status="201"}`, records duration in histogram.
   - **RequestLoggingMiddleware** logs `← 201 (12ms)`.
   - **ErrorHandlerMiddleware** passes the success response through unchanged.

10. ZIO HTTP serializes the `Response` to bytes and sends over TCP.

---

## 18. Test Structure

### Unit Tests (ZIO Test + mocked repositories)

**Location:** `src/test/scala/com/myassistant/unit/`

Each spec uses `ZIO Test` with `ZLayer`-based mock repositories via `zio-mock`.

```scala
// Example: PersonServiceSpec.scala
object PersonServiceSpec extends ZIOSpecDefault:
  val mockRepo = PersonRepository.mock    // generated by zio-mock
  
  def spec = suite("PersonService")(
    test("getById returns person when found") {
      val personId = UUID.randomUUID()
      for
        person <- PersonService.getById(personId)
      yield assertTrue(person.id == personId)
    }.provide(
      PersonService.live,
      mockRepo.of(PersonRepository.FindById)(
        Expectation.value(Some(testPerson))
      ),
      ZLayer.succeed(ZConnectionPool.test)  // in-memory test pool
    )
  )
```

Key specs:

- `PersonServiceSpec` — getById (found / not found), delete (clean / blocked by references)
- `KinshipResolverSpec` — BFS: direct relation, two-hop (bua = father→sister), no path, max-depth exceeded, alias lookup
- `FactServiceSpec` — createFact (new entity, existing entity), invalid operationType, missing schemaId
- `AuthMiddlewareSpec` — valid token passes, missing header returns 401, wrong token returns 401
- `ErrorHandlerSpec` — each AppError variant maps to correct HTTP status

### Integration Tests (ZIO Test + Testcontainers)

**Location:** `src/test/scala/com/myassistant/integration/`

`DatabaseTestSupport.scala` provides:

```scala
object DatabaseTestSupport:
  val postgresLayer: ZLayer[Any, Throwable, ZConnectionPool] =
    ZLayer.scoped {
      for
        container <- ZIO.acquireRelease(
                       ZIO.attempt {
                         val c = new PostgreSQLContainer("postgres:16")
                         c.withInitScript("db/test-init.sql")
                         c.start(); c
                       }
                     )(c => ZIO.attempt(c.stop()).orDie)
        pool      <- ZConnectionPool.postgres(
                       host     = container.getHost,
                       port     = container.getMappedPort(5432),
                       database = container.getDatabaseName,
                       props    = Map("user" -> container.getUsername, "password" -> container.getPassword),
                       config   = ZConnectionPoolConfig.default
                     )
        _         <- MigrationRunner.run.provide(ZLayer.succeed(
                       DatabaseConfig(container.getJdbcUrl, container.getUsername, container.getPassword, 5)
                     ))
      yield pool
    }
```

Key integration specs:

- `PersonRepositorySpec` — insert, findById, findAll with filters, update, delete, checkReferences
- `FactRepositorySpec` — insertFact, getHistory, getCurrentFact (patch merge), searchCurrentFacts (requires pgvector)
- `DocumentRepositorySpec` — insert, searchBySimilarity

### Cucumber E2E Tests

**Location:** `src/test/scala/com/myassistant/e2e/`

Uses `io.cucumber:cucumber-scala` + `sttp` HTTP client hitting a running instance of the server.

`person.feature`:
```gherkin
Feature: Person management
  Scenario: Create and retrieve a person
    Given the service is running
    When I POST /api/v1/persons with {"fullName":"Raj Sharma","gender":"male"}
    Then the response status is 201
    And the response contains "fullName":"Raj Sharma"
    When I GET /api/v1/persons/{id}
    Then the response status is 200
```

`fact_lifecycle.feature`:
```gherkin
Feature: Fact append-only lifecycle
  Scenario: Create, update, and soft-delete a todo fact
    Given a document exists for person "Raj"
    When I POST /api/v1/facts with operationType "create" and fields {"title":"renew passport","status":"open"}
    Then the entityInstanceId is captured
    When I POST /api/v1/facts with operationType "update" and fields {"status":"in_progress"}
    Then GET /api/v1/facts/current/{entityInstanceId} shows status "in_progress"
    When I POST /api/v1/facts with operationType "delete"
    Then GET /api/v1/facts/current/{entityInstanceId} returns 404
```

### k6 Performance Tests

**Location:** `src/test/resources/k6/load_test.js`

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 20 },   // ramp up to 20 VUs
    { duration: '1m',  target: 20 },   // hold
    { duration: '10s', target: 0 },    // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<200'],  // 95th percentile under 200ms
    http_req_failed:   ['rate<0.01'],  // error rate under 1%
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN    = __ENV.AUTH_TOKEN || 'change-me';
const HEADERS  = { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' };

export default function () {
  // Create person
  const create = http.post(`${BASE_URL}/api/v1/persons`,
    JSON.stringify({ fullName: 'Load Test User', gender: 'male' }),
    { headers: HEADERS }
  );
  check(create, { 'create person 201': r => r.status === 201 });

  const personId = JSON.parse(create.body).id;

  // List persons
  const list = http.get(`${BASE_URL}/api/v1/persons?limit=10`, { headers: HEADERS });
  check(list, { 'list persons 200': r => r.status === 200 });

  sleep(1);
}
```

Run with: `k6 run --env BASE_URL=http://localhost:8080 --env AUTH_TOKEN=secret src/test/resources/k6/load_test.js`

---

## Appendix: `build.sbt` (abbreviated)

```scala
val zioVersion     = "2.1.9"
val zioHttpVersion = "3.0.0-RC4"
val zioJdbcVersion = "0.1.2"
val circeVersion   = "0.14.9"

libraryDependencies ++= Seq(
  "dev.zio"       %% "zio"                   % zioVersion,
  "dev.zio"       %% "zio-http"              % zioHttpVersion,
  "dev.zio"       %% "zio-jdbc"              % zioJdbcVersion,
  "dev.zio"       %% "zio-config"            % "4.0.2",
  "dev.zio"       %% "zio-config-magnolia"   % "4.0.2",
  "dev.zio"       %% "zio-config-typesafe"   % "4.0.2",
  "dev.zio"       %% "zio-logging"           % "2.3.1",
  "dev.zio"       %% "zio-metrics-connectors-prometheus" % "2.3.1",
  "io.circe"      %% "circe-core"            % circeVersion,
  "io.circe"      %% "circe-generic"         % circeVersion,
  "io.circe"      %% "circe-parser"          % circeVersion,
  "org.flywaydb"   % "flyway-core"           % "10.10.0",
  "org.flywaydb"   % "flyway-database-postgresql" % "10.10.0",
  "org.postgresql" % "postgresql"            % "42.7.3",
  "io.prometheus"  % "simpleclient"          % "0.16.0",
  "io.prometheus"  % "simpleclient_common"   % "0.16.0",
  "com.zaxxer"     % "HikariCP"              % "5.1.0",

  // Test
  "dev.zio"       %% "zio-test"              % zioVersion    % Test,
  "dev.zio"       %% "zio-test-sbt"          % zioVersion    % Test,
  "dev.zio"       %% "zio-mock"              % "1.0.0-RC12"  % Test,
  "org.testcontainers" % "postgresql"        % "1.19.7"      % Test,
  "io.cucumber"   %% "cucumber-scala"        % "8.23.0"      % Test,
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
```
