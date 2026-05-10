# News Feature Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a personal news experience — subscribe to topics, store daily article facts, drill down via web fetch/search, and manage schedules via chat.

**Architecture:** A new `scheduler` Python service polls `scheduled_job` rows every 60s and writes raw articles as facts via the http-server REST API. Two new MCP web tools (`fetch_url`, `web_search`) enable drill-down in chat. Four new MCP tools manage scheduled jobs. No LLM is involved at ingestion time — Claude only runs at query time.

**Tech Stack:** Scala/ZIO HTTP (http-server), Python/FastAPI (chatbot-server), Python (scheduler), PostgreSQL/Flyway, ZIO JDBC, Circe, httpx, BeautifulSoup4, croniter, NewsAPI.

---

## File Map

| File | Action |
|---|---|
| `backend/http_server/schema/09_news_scheduler.sql` | Create — reference copy of migration |
| `backend/http_server/src/main/resources/db/migration/V11__news_scheduler.sql` | Create — Flyway migration |
| `backend/http_server/src/main/scala/com/myassistant/domain/ScheduledJob.scala` | Create |
| `backend/http_server/src/main/scala/com/myassistant/db/repositories/ScheduledJobRepository.scala` | Create |
| `backend/http_server/src/main/scala/com/myassistant/services/ScheduledJobService.scala` | Create |
| `backend/http_server/src/main/scala/com/myassistant/api/models/ScheduledJobModels.scala` | Create |
| `backend/http_server/src/main/scala/com/myassistant/api/routes/ScheduledJobRoutes.scala` | Create |
| `backend/http_server/src/main/scala/com/myassistant/api/Router.scala` | Modify — add ScheduledJobService to AppEnv + routes |
| `backend/http_server/src/main/scala/com/myassistant/Main.scala` | Modify — wire scheduledJob repo + service layers |
| `backend/mcp_server/tools/scheduled_jobs.py` | Create |
| `backend/mcp_server/tools/web.py` | Create |
| `backend/chatbot_server/core/tool_definitions.py` | Modify — add 6 new tool schemas, update assert to 47 |
| `backend/chatbot_server/core/live_executor.py` | Modify — import + register 6 new tools |
| `backend/chatbot_server/pyproject.toml` | Modify — add beautifulsoup4 |
| `backend/scheduler/main.py` | Create |
| `backend/scheduler/handlers/__init__.py` | Create |
| `backend/scheduler/handlers/base.py` | Create |
| `backend/scheduler/handlers/news_poll.py` | Create |
| `backend/scheduler/providers/__init__.py` | Create |
| `backend/scheduler/providers/news_source.py` | Create |
| `backend/scheduler/providers/newsapi_source.py` | Create |
| `backend/scheduler/pyproject.toml` | Create |
| `backend/scheduler/Dockerfile` | Create |
| `docker-compose.yml` | Modify — add scheduler service |
| `docs/http-contract.md` | Modify — add scheduled-jobs endpoints |
| `docs/mcp-tools.md` | Modify — add 6 new tools |

---

### Task 1: Flyway Migration

**Files:**
- Create: `backend/http_server/schema/09_news_scheduler.sql`
- Create: `backend/http_server/src/main/resources/db/migration/V11__news_scheduler.sql`

Both files have identical content.

- [ ] **Step 1: Write the migration SQL**

Create `backend/http_server/src/main/resources/db/migration/V11__news_scheduler.sql`:

```sql
-- Rename news_preferences domain to news
INSERT INTO domain (name, description)
VALUES ('news', 'News articles and topic preferences');

UPDATE entity_type_schema SET domain = 'news' WHERE domain = 'news_preferences';

DELETE FROM domain WHERE name = 'news_preferences';

-- Add is_scheduled flag to source_type
ALTER TABLE source_type ADD COLUMN is_scheduled BOOLEAN NOT NULL DEFAULT false;

INSERT INTO source_type (name, description, is_scheduled)
VALUES ('news_poll', 'News articles fetched from NewsAPI', true);

-- scheduled_job: stores all recurring job definitions
CREATE TABLE scheduled_job (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_type     TEXT        NOT NULL REFERENCES source_type(name),
    person_id       UUID        REFERENCES person(id),
    household_id    UUID        REFERENCES household(id),
    cron_expression TEXT        NOT NULL,
    config          JSONB       NOT NULL DEFAULT '{}',
    enabled         BOOLEAN     NOT NULL DEFAULT true,
    next_run_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT scheduled_job_scope CHECK (
        (person_id IS NOT NULL) != (household_id IS NOT NULL)
    )
);

-- scheduled_job_run: one row per execution of a scheduled_job
CREATE TABLE scheduled_job_run (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_id          UUID        NOT NULL REFERENCES scheduled_job(id),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    status          TEXT        NOT NULL CHECK (status IN ('success', 'error', 'partial')),
    error           TEXT,
    articles_stored INT         NOT NULL DEFAULT 0
);

-- news_topic entity type schema
INSERT INTO entity_type_schema (domain, entity_type, schema_version, description, field_definitions, extraction_prompt, change_description)
VALUES (
    'news',
    'news_topic',
    1,
    'A news topic the user wants to follow',
    '[
        {"name": "name",   "type": "text",    "mandatory": true,  "description": "Topic label. Example: AI, climate change, personal finance"},
        {"name": "active", "type": "boolean", "mandatory": false, "description": "Whether this topic is currently active. Default true."}
    ]'::jsonb,
    'Extract news topic preferences. Create a news_topic fact for each distinct topic the user wants to follow. Set active=true when adding, active=false when removing.',
    'Initial version'
);

-- news_article entity type schema
INSERT INTO entity_type_schema (domain, entity_type, schema_version, description, field_definitions, extraction_prompt, change_description)
VALUES (
    'news',
    'news_article',
    1,
    'A news article fetched by the scheduler',
    '[
        {"name": "headline",       "type": "text", "mandatory": true,  "description": "Article headline"},
        {"name": "source",         "type": "text", "mandatory": false, "description": "News outlet name. Example: BBC News, Reuters"},
        {"name": "url",            "type": "text", "mandatory": true,  "description": "Direct URL to the article"},
        {"name": "published_date", "type": "date", "mandatory": true,  "description": "Publication date YYYY-MM-DD"},
        {"name": "topic",          "type": "text", "mandatory": false, "description": "Which news_topic this article relates to"},
        {"name": "description",    "type": "text", "mandatory": false, "description": "Short 2-3 sentence summary from the news source"}
    ]'::jsonb,
    'Extract news article details. Create one news_article fact per article.',
    'Initial version'
);
```

Copy identical content to `backend/http_server/schema/09_news_scheduler.sql`.

- [ ] **Step 2: Apply the migration locally**

```bash
cd backend/http_server
sbt flywayMigrate
```

Expected output: `Successfully applied 1 migration to schema "public" (execution time 00:00.XXXs)`

- [ ] **Step 3: Verify tables exist**

```bash
psql -h localhost -p 5433 -U <POSTGRES_USER> -d <POSTGRES_DB> -c "\dt scheduled_job*"
```

Expected: two rows — `scheduled_job` and `scheduled_job_run`.

```bash
psql -h localhost -p 5433 -U <POSTGRES_USER> -d <POSTGRES_DB> \
  -c "SELECT name, description FROM domain ORDER BY name;"
```

Expected: `news` present, `news_preferences` absent.

- [ ] **Step 4: Commit**

```bash
git add backend/http_server/schema/09_news_scheduler.sql \
        backend/http_server/src/main/resources/db/migration/V11__news_scheduler.sql
git commit -m "feat(db): V11 migration — news domain, scheduled_job, news entity schemas"
```

---

### Task 2: Scala Domain Model + Repository

**Files:**
- Create: `backend/http_server/src/main/scala/com/myassistant/domain/ScheduledJob.scala`
- Create: `backend/http_server/src/main/scala/com/myassistant/db/repositories/ScheduledJobRepository.scala`

- [ ] **Step 1: Write the domain model**

Create `backend/http_server/src/main/scala/com/myassistant/domain/ScheduledJob.scala`:

```scala
package com.myassistant.domain

import io.circe.Json
import java.time.Instant
import java.util.UUID

final case class ScheduledJob(
    id:             UUID,
    sourceType:     String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    cronExpression: String,
    config:         Json,
    enabled:        Boolean,
    nextRunAt:      Option[Instant],
    createdAt:      Instant,
)

final case class CreateScheduledJob(
    sourceType:     String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    cronExpression: String,
    config:         Json,
)

final case class UpdateScheduledJob(
    cronExpression: Option[String],
    config:         Option[Json],
    enabled:        Option[Boolean],
    nextRunAt:      Option[Instant],
)

final case class ScheduledJobRun(
    id:             UUID,
    jobId:          UUID,
    startedAt:      Instant,
    finishedAt:     Option[Instant],
    status:         String,
    error:          Option[String],
    articlesStored: Int,
)
```

- [ ] **Step 2: Write the repository**

Create `backend/http_server/src/main/scala/com/myassistant/db/repositories/ScheduledJobRepository.scala`:

```scala
package com.myassistant.db.repositories

import com.myassistant.domain.{CreateScheduledJob, ScheduledJob, ScheduledJobRun, UpdateScheduledJob}
import com.myassistant.errors.AppError
import io.circe.Json
import io.circe.parser as circeParser
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

trait ScheduledJobRepository:
  def create(req: CreateScheduledJob): ZIO[ZConnectionPool, AppError, ScheduledJob]
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[ScheduledJob]]
  def listByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJob]]
  def listDue: ZIO[ZConnectionPool, AppError, List[ScheduledJob]]
  def update(id: UUID, req: UpdateScheduledJob): ZIO[ZConnectionPool, AppError, Option[ScheduledJob]]
  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean]
  def createRun(run: ScheduledJobRun): ZIO[ZConnectionPool, AppError, ScheduledJobRun]

object ScheduledJobRepository:
  private type JobRow =
    (String, String, Option[String], Option[String], String, String, Boolean, Option[java.sql.Timestamp], java.sql.Timestamp)
  private type RunRow =
    (String, String, java.sql.Timestamp, Option[java.sql.Timestamp], String, Option[String], Int)

  private def rowToJob(row: JobRow): ScheduledJob =
    val (id, sourceType, personId, householdId, cronExpr, configJson, enabled, nextRunAt, createdAt) = row
    ScheduledJob(
      id             = UUID.fromString(id),
      sourceType     = sourceType,
      personId       = personId.map(UUID.fromString),
      householdId    = householdId.map(UUID.fromString),
      cronExpression = cronExpr,
      config         = circeParser.parse(configJson).getOrElse(Json.obj()),
      enabled        = enabled,
      nextRunAt      = nextRunAt.map(_.toInstant),
      createdAt      = createdAt.toInstant,
    )

  private def rowToRun(row: RunRow): ScheduledJobRun =
    val (id, jobId, startedAt, finishedAt, status, error, articlesStored) = row
    ScheduledJobRun(
      id             = UUID.fromString(id),
      jobId          = UUID.fromString(jobId),
      startedAt      = startedAt.toInstant,
      finishedAt     = finishedAt.map(_.toInstant),
      status         = status,
      error          = error,
      articlesStored = articlesStored,
    )

  private val selectCols =
    "id::text, source_type, person_id::text, household_id::text, cron_expression, config::text, enabled, next_run_at, created_at"

  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" => AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  final class Live extends ScheduledJobRepository:

    def create(req: CreateScheduledJob): ZIO[ZConnectionPool, AppError, ScheduledJob] =
      val id = UUID.randomUUID()
      transaction {
        sql"""
          INSERT INTO scheduled_job (id, source_type, person_id, household_id, cron_expression, config)
          VALUES (
            ${id.toString}::uuid,
            ${req.sourceType},
            ${req.personId.map(_.toString)}::uuid,
            ${req.householdId.map(_.toString)}::uuid,
            ${req.cronExpression},
            ${req.config.noSpaces}::jsonb
          )
          RETURNING $selectCols
        """.query[JobRow].selectOne
      }.mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ => AppError.InternalError(new RuntimeException("INSERT returned no row"))))
        .map(rowToJob)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[ScheduledJob]] =
      transaction {
        sql"SELECT $selectCols FROM scheduled_job WHERE id = ${id.toString}::uuid".query[JobRow].selectOne
      }.mapError(mapSqlError).map(_.map(rowToJob))

    def listByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJob]] =
      transaction {
        sql"""
          SELECT $selectCols FROM scheduled_job
          WHERE person_id = ${personId.toString}::uuid ORDER BY created_at DESC
        """.query[JobRow].selectAll
      }.mapError(mapSqlError).map(_.toList.map(rowToJob))

    def listDue: ZIO[ZConnectionPool, AppError, List[ScheduledJob]] =
      transaction {
        sql"""
          SELECT $selectCols FROM scheduled_job
          WHERE enabled = true AND (next_run_at IS NULL OR next_run_at <= now())
          ORDER BY next_run_at ASC NULLS FIRST
        """.query[JobRow].selectAll
      }.mapError(mapSqlError).map(_.toList.map(rowToJob))

    def update(id: UUID, req: UpdateScheduledJob): ZIO[ZConnectionPool, AppError, Option[ScheduledJob]] =
      val setClauses = List.concat(
        req.cronExpression.map(v => s"cron_expression = '$v'"),
        req.config.map(v        => s"config = '${v.noSpaces}'::jsonb"),
        req.enabled.map(v       => s"enabled = $v"),
        req.nextRunAt.map(v     => s"next_run_at = '$v'::timestamptz"),
      )
      if setClauses.isEmpty then findById(id)
      else
        transaction {
          SqlFragment(s"""
            UPDATE scheduled_job SET ${setClauses.mkString(", ")}
            WHERE id = '${id.toString}'::uuid
            RETURNING $selectCols
          """).query[JobRow].selectOne
        }.mapError(mapSqlError).map(_.map(rowToJob))

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      transaction {
        sql"DELETE FROM scheduled_job WHERE id = ${id.toString}::uuid".update.run
      }.mapError(mapSqlError).map(_ > 0)

    def createRun(run: ScheduledJobRun): ZIO[ZConnectionPool, AppError, ScheduledJobRun] =
      transaction {
        sql"""
          INSERT INTO scheduled_job_run (id, job_id, started_at, finished_at, status, error, articles_stored)
          VALUES (
            ${run.id.toString}::uuid,
            ${run.jobId.toString}::uuid,
            ${run.startedAt.toString}::timestamptz,
            ${run.finishedAt.map(_.toString)}::timestamptz,
            ${run.status},
            ${run.error.orNull},
            ${run.articlesStored}
          )
          RETURNING id::text, job_id::text, started_at, finished_at, status, error, articles_stored
        """.query[RunRow].selectOne
      }.mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ => AppError.InternalError(new RuntimeException("INSERT run returned no row"))))
        .map(rowToRun)

  val live: ZLayer[Any, Nothing, ScheduledJobRepository] =
    ZLayer.succeed(new Live)
```

- [ ] **Step 3: Compile**

```bash
cd backend/http_server && sbt compile
```

Expected: `[success]` with no errors.

- [ ] **Step 4: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/domain/ScheduledJob.scala \
        backend/http_server/src/main/scala/com/myassistant/db/repositories/ScheduledJobRepository.scala
git commit -m "feat(http-server): ScheduledJob domain model and repository"
```

---

### Task 3: Scala Service, Models, Routes

**Files:**
- Create: `backend/http_server/src/main/scala/com/myassistant/services/ScheduledJobService.scala`
- Create: `backend/http_server/src/main/scala/com/myassistant/api/models/ScheduledJobModels.scala`
- Create: `backend/http_server/src/main/scala/com/myassistant/api/routes/ScheduledJobRoutes.scala`

- [ ] **Step 1: Write the service**

Create `backend/http_server/src/main/scala/com/myassistant/services/ScheduledJobService.scala`:

```scala
package com.myassistant.services

import com.myassistant.db.repositories.ScheduledJobRepository
import com.myassistant.domain.*
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*
import java.util.UUID

trait ScheduledJobService:
  def create(req: CreateScheduledJob): ZIO[ZConnectionPool, AppError, ScheduledJob]
  def get(id: UUID): ZIO[ZConnectionPool, AppError, ScheduledJob]
  def listByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJob]]
  def listDue: ZIO[ZConnectionPool, AppError, List[ScheduledJob]]
  def update(id: UUID, req: UpdateScheduledJob): ZIO[ZConnectionPool, AppError, ScheduledJob]
  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Unit]
  def recordRun(run: ScheduledJobRun): ZIO[ZConnectionPool, AppError, ScheduledJobRun]

object ScheduledJobService:
  final class Live(repo: ScheduledJobRepository) extends ScheduledJobService:
    def create(req: CreateScheduledJob) = repo.create(req)
    def get(id: UUID) = repo.findById(id).flatMap:
      case Some(j) => ZIO.succeed(j)
      case None    => ZIO.fail(AppError.NotFound("scheduled_job", id.toString))
    def listByPerson(personId: UUID) = repo.listByPerson(personId)
    def listDue = repo.listDue
    def update(id: UUID, req: UpdateScheduledJob) = repo.update(id, req).flatMap:
      case Some(j) => ZIO.succeed(j)
      case None    => ZIO.fail(AppError.NotFound("scheduled_job", id.toString))
    def delete(id: UUID) = repo.delete(id).flatMap:
      case true  => ZIO.unit
      case false => ZIO.fail(AppError.NotFound("scheduled_job", id.toString))
    def recordRun(run: ScheduledJobRun) = repo.createRun(run)

  val live: ZLayer[ScheduledJobRepository, Nothing, ScheduledJobService] =
    ZLayer.fromFunction(new Live(_))
```

- [ ] **Step 2: Write the API models**

Create `backend/http_server/src/main/scala/com/myassistant/api/models/ScheduledJobModels.scala`:

```scala
package com.myassistant.api.models

import com.myassistant.domain.*
import io.circe.{Codec, Json}
import io.circe.syntax.*
import java.time.Instant
import java.util.UUID

final case class CreateScheduledJobRequest(
    sourceType:     String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    cronExpression: String,
    config:         Option[Json],
) derives Codec.AsObject:
  def toDomain: CreateScheduledJob = CreateScheduledJob(
    sourceType     = sourceType,
    personId       = personId,
    householdId    = householdId,
    cronExpression = cronExpression,
    config         = config.getOrElse(Json.obj()),
  )

final case class UpdateScheduledJobRequest(
    cronExpression: Option[String],
    config:         Option[Json],
    enabled:        Option[Boolean],
    nextRunAt:      Option[Instant],
) derives Codec.AsObject:
  def toDomain: UpdateScheduledJob = UpdateScheduledJob(
    cronExpression = cronExpression,
    config         = config,
    enabled        = enabled,
    nextRunAt      = nextRunAt,
  )

final case class ScheduledJobResponse(
    id:             UUID,
    sourceType:     String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    cronExpression: String,
    config:         Json,
    enabled:        Boolean,
    nextRunAt:      Option[Instant],
    createdAt:      Instant,
) derives Codec.AsObject

object ScheduledJobResponse:
  def fromDomain(j: ScheduledJob): ScheduledJobResponse =
    ScheduledJobResponse(
      id             = j.id,
      sourceType     = j.sourceType,
      personId       = j.personId,
      householdId    = j.householdId,
      cronExpression = j.cronExpression,
      config         = j.config,
      enabled        = j.enabled,
      nextRunAt      = j.nextRunAt,
      createdAt      = j.createdAt,
    )
```

- [ ] **Step 3: Write the routes**

Create `backend/http_server/src/main/scala/com/myassistant/api/routes/ScheduledJobRoutes.scala`:

```scala
package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.*
import com.myassistant.services.ScheduledJobService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*
import java.util.UUID
import scala.util.Try

object ScheduledJobRoutes:
  val routes: Routes[ScheduledJobService & ZConnectionPool, Nothing] =
    Routes(
      Method.POST / "api" / "v1" / "scheduled-jobs" ->
        handler { (req: Request) =>
          for
            bodyStr <- req.body.asString.orDie
            resp    <- decode[CreateScheduledJobRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(s"""{"error":"bad_request","message":"${err.getMessage.replace("\"","'")}"}""").status(Status.BadRequest))
              case Right(r) =>
                ZIO.serviceWithZIO[ScheduledJobService](_.create(r.toDomain))
                  .foldZIO(
                    err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    job => ZIO.succeed(Response.json(ScheduledJobResponse.fromDomain(job).asJson.noSpaces).status(Status.Created)),
                  )
          yield resp
        },

      Method.GET / "api" / "v1" / "scheduled-jobs" ->
        handler { (req: Request) =>
          req.queryParam("personId").flatMap(s => Try(UUID.fromString(s)).toOption) match
            case None =>
              ZIO.succeed(Response.json("""{"error":"bad_request","message":"personId query param required"}""").status(Status.BadRequest))
            case Some(pid) =>
              ZIO.serviceWithZIO[ScheduledJobService](_.listByPerson(pid))
                .foldZIO(
                  err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  jobs => ZIO.succeed(Response.json(jobs.map(ScheduledJobResponse.fromDomain).asJson.noSpaces)),
                )
        },

      Method.GET / "api" / "v1" / "scheduled-jobs" / "due" ->
        handler { (_: Request) =>
          ZIO.serviceWithZIO[ScheduledJobService](_.listDue)
            .foldZIO(
              err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              jobs => ZIO.succeed(Response.json(jobs.map(ScheduledJobResponse.fromDomain).asJson.noSpaces)),
            )
        },

      Method.PUT / "api" / "v1" / "scheduled-jobs" / string("id") ->
        handler { (id: String, req: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_) =>
              ZIO.succeed(Response.json(s"""{"error":"bad_request","message":"Invalid UUID: $id"}""").status(Status.BadRequest))
            case Right(uuid) =>
              for
                bodyStr <- req.body.asString.orDie
                resp    <- decode[UpdateScheduledJobRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(s"""{"error":"bad_request","message":"${err.getMessage.replace("\"","'")}"}""").status(Status.BadRequest))
                  case Right(r) =>
                    ZIO.serviceWithZIO[ScheduledJobService](_.update(uuid, r.toDomain))
                      .foldZIO(
                        err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        job => ZIO.succeed(Response.json(ScheduledJobResponse.fromDomain(job).asJson.noSpaces)),
                      )
              yield resp
        },

      Method.DELETE / "api" / "v1" / "scheduled-jobs" / string("id") ->
        handler { (id: String, _: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_) =>
              ZIO.succeed(Response.json(s"""{"error":"bad_request","message":"Invalid UUID: $id"}""").status(Status.BadRequest))
            case Right(uuid) =>
              ZIO.serviceWithZIO[ScheduledJobService](_.delete(uuid))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  _   => ZIO.succeed(Response.status(Status.NoContent)),
                )
        },
    )
```

- [ ] **Step 4: Compile**

```bash
cd backend/http_server && sbt compile
```

Expected: `[success]`

- [ ] **Step 5: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/services/ScheduledJobService.scala \
        backend/http_server/src/main/scala/com/myassistant/api/models/ScheduledJobModels.scala \
        backend/http_server/src/main/scala/com/myassistant/api/routes/ScheduledJobRoutes.scala
git commit -m "feat(http-server): ScheduledJob service, models, and routes"
```

---

### Task 4: Wire ScheduledJob into Router + Main

**Files:**
- Modify: `backend/http_server/src/main/scala/com/myassistant/api/Router.scala`
- Modify: `backend/http_server/src/main/scala/com/myassistant/Main.scala`

- [ ] **Step 1: Add ScheduledJobService to Router.scala**

In `Router.scala`, add `ScheduledJobService` to `AppEnv` and add `ScheduledJobRoutes.routes` to `protectedRoutes`:

```scala
// In AppEnv type alias, add:
    & ScheduledJobService

// In protectedRoutes, add ScheduledJobRoutes.routes ++:
          ScheduledJobRoutes.routes ++
          FileRoutes.routes) @@ AuthMiddleware(authCfg.token)
```

Full updated `Router.scala`:

```scala
package com.myassistant.api

import com.myassistant.api.middleware.{AuthMiddleware, LoggingMiddleware}
import com.myassistant.api.routes.*
import com.myassistant.config.AuthConfig
import com.myassistant.services.*
import zio.*
import zio.http.*
import zio.jdbc.*

object Router:

  type AppEnv =
    PersonService
      & HouseholdService
      & RelationshipService
      & KinshipResolver
      & DocumentService
      & FactService
      & SchemaService
      & ReferenceService
      & AuditService
      & FileService
      & ScheduledJobService
      & ZConnectionPool
      & AuthConfig

  val app: ZIO[AppEnv, Nothing, Routes[AppEnv, Nothing]] =
    ZIO.serviceWith[AuthConfig] { authCfg =>
      val publicRoutes: Routes[AppEnv, Nothing] =
        HealthRoutes.routes

      val protectedRoutes: Routes[AppEnv, Nothing] =
        (PersonRoutes.routes ++
          HouseholdRoutes.routes ++
          PersonHouseholdRoutes.routes ++
          RelationshipRoutes.routes ++
          DocumentRoutes.routes ++
          FactRoutes.routes ++
          SchemaRoutes.routes ++
          ReferenceRoutes.routes ++
          AuditRoutes.routes ++
          FileRoutes.routes ++
          ScheduledJobRoutes.routes) @@ AuthMiddleware(authCfg.token)

      (publicRoutes ++ protectedRoutes) @@ LoggingMiddleware.logRequests
    }
```

- [ ] **Step 2: Wire layers in Main.scala**

In `Main.scala`, add `scheduledJobRepoLayer` and `scheduledJobSvcLayer` following the existing pattern. Add to `appLayer` and the `poolLayer ++` chain.

In the repositories section add:
```scala
val scheduledJobRepoLayer = ScheduledJobRepository.live
```

In the services section add:
```scala
val scheduledJobSvcLayer = scheduledJobRepoLayer >>> ScheduledJobService.live
```

In the `poolLayer ++` chain add:
```scala
      scheduledJobSvcLayer ++
```

Also add to imports at the top:
```scala
import com.myassistant.db.repositories.ScheduledJobRepository
```

- [ ] **Step 3: Compile and test**

```bash
cd backend/http_server && sbt compile
```

Expected: `[success]`

Then start the server locally and test:
```bash
sbt run &
curl -s -X POST http://localhost:8080/api/v1/scheduled-jobs \
  -H "Authorization: Bearer <AUTH_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"sourceType":"news_poll","cronExpression":"0 7 * * *","personId":"<person_uuid>"}' | jq .
```

Expected: `201 Created` with a JSON body containing `id`, `sourceType: "news_poll"`, `enabled: true`.

```bash
curl -s "http://localhost:8080/api/v1/scheduled-jobs?personId=<person_uuid>" \
  -H "Authorization: Bearer <AUTH_TOKEN>" | jq .
```

Expected: array containing the job just created.

- [ ] **Step 4: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/api/Router.scala \
        backend/http_server/src/main/scala/com/myassistant/Main.scala
git commit -m "feat(http-server): wire ScheduledJobService into Router and Main"
```

---

### Task 5: Python MCP Tools for Schedule Management

**Files:**
- Create: `backend/mcp_server/tools/scheduled_jobs.py`

- [ ] **Step 1: Write the tools**

Create `backend/mcp_server/tools/scheduled_jobs.py`:

```python
import httpx
from typing import Optional


def _check(resp: httpx.Response) -> None:
    if resp.status_code >= 400:
        raise RuntimeError(f"HTTP {resp.status_code}: {resp.text}")


def create_scheduled_job(
    http: httpx.Client,
    source_type: str,
    cron_expression: str,
    person_id: Optional[str] = None,
    household_id: Optional[str] = None,
    config: Optional[dict] = None,
) -> dict:
    body = {
        "sourceType": source_type,
        "cronExpression": cron_expression,
        "personId": person_id,
        "householdId": household_id,
        "config": config or {},
    }
    resp = http.post("/api/v1/scheduled-jobs", json=body)
    _check(resp)
    return resp.json()


def list_scheduled_jobs(
    http: httpx.Client,
    person_id: str,
) -> dict:
    resp = http.get("/api/v1/scheduled-jobs", params={"personId": person_id})
    _check(resp)
    return resp.json()


def update_scheduled_job(
    http: httpx.Client,
    job_id: str,
    cron_expression: Optional[str] = None,
    config: Optional[dict] = None,
    enabled: Optional[bool] = None,
) -> dict:
    body: dict = {}
    if cron_expression is not None:
        body["cronExpression"] = cron_expression
    if config is not None:
        body["config"] = config
    if enabled is not None:
        body["enabled"] = enabled
    resp = http.put(f"/api/v1/scheduled-jobs/{job_id}", json=body)
    _check(resp)
    return resp.json()


def delete_scheduled_job(
    http: httpx.Client,
    job_id: str,
) -> dict:
    resp = http.delete(f"/api/v1/scheduled-jobs/{job_id}")
    _check(resp)
    return {"deleted": True, "id": job_id}
```

- [ ] **Step 2: Verify import works**

```bash
cd backend/mcp_server && python3 -c "from tools import scheduled_jobs; print('ok')"
```

Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add backend/mcp_server/tools/scheduled_jobs.py
git commit -m "feat(mcp): scheduled_jobs MCP tools (create/list/update/delete)"
```

---

### Task 6: Python Web Tools

**Files:**
- Create: `backend/mcp_server/tools/web.py`
- Modify: `backend/chatbot_server/pyproject.toml`

- [ ] **Step 1: Add beautifulsoup4 to chatbot-server dependencies**

In `backend/chatbot_server/pyproject.toml`, add `"beautifulsoup4>=4.12"` to the `dependencies` list:

```toml
dependencies = [
    "fastapi>=0.111",
    "uvicorn[standard]>=0.30",
    "httpx>=0.27",
    "anthropic>=0.40",
    "boto3>=1.34",
    "python-multipart>=0.0.9",
    "pydantic>=2.0",
    "sentence-transformers>=3.0",
    "beautifulsoup4>=4.12",
]
```

- [ ] **Step 2: Write the web tools**

Create `backend/mcp_server/tools/web.py`:

```python
import os
import httpx
from bs4 import BeautifulSoup
from typing import Optional


def fetch_url(http: httpx.Client, url: str) -> dict:
    """Fetch a webpage and return its readable plain text."""
    with httpx.Client(follow_redirects=True, timeout=15.0) as client:
        resp = client.get(url, headers={"User-Agent": "Mozilla/5.0 (compatible; myassistant/1.0)"})
        resp.raise_for_status()
    soup = BeautifulSoup(resp.text, "html.parser")
    for tag in soup(["script", "style", "nav", "footer", "header"]):
        tag.decompose()
    text = soup.get_text(separator="\n", strip=True)
    truncated = len(text) > 8000
    return {"url": url, "text": text[:8000], "truncated": truncated}


def web_search(http: httpx.Client, query: str, num_results: int = 5) -> dict:
    """Search the web and return top results."""
    provider = os.getenv("WEB_SEARCH_PROVIDER", "duckduckgo")
    if provider == "brave":
        return _brave_search(query, num_results)
    return _ddg_search(query, num_results)


def _ddg_search(query: str, num_results: int) -> dict:
    with httpx.Client(timeout=10.0) as client:
        resp = client.get(
            "https://api.duckduckgo.com/",
            params={"q": query, "format": "json", "no_redirect": "1", "no_html": "1"},
        )
        resp.raise_for_status()
    data = resp.json()
    results = []
    for item in data.get("RelatedTopics", [])[:num_results]:
        if isinstance(item, dict) and "Text" in item:
            results.append({
                "title": item.get("Text", "")[:120],
                "snippet": item.get("Text", ""),
                "url": item.get("FirstURL", ""),
            })
    return {"query": query, "provider": "duckduckgo", "results": results}


def _brave_search(query: str, num_results: int) -> dict:
    api_key = os.getenv("BRAVE_API_KEY", "")
    if not api_key:
        raise RuntimeError("BRAVE_API_KEY env var not set but WEB_SEARCH_PROVIDER=brave")
    with httpx.Client(timeout=10.0) as client:
        resp = client.get(
            "https://api.search.brave.com/res/v1/web/search",
            params={"q": query, "count": num_results},
            headers={"Accept": "application/json", "X-Subscription-Token": api_key},
        )
        resp.raise_for_status()
    data = resp.json()
    results = [
        {"title": r.get("title", ""), "snippet": r.get("description", ""), "url": r.get("url", "")}
        for r in data.get("web", {}).get("results", [])[:num_results]
    ]
    return {"query": query, "provider": "brave", "results": results}
```

- [ ] **Step 3: Verify import works**

```bash
cd backend/mcp_server && python3 -c "from tools import web; print('ok')"
```

Expected: `ok`

- [ ] **Step 4: Commit**

```bash
git add backend/mcp_server/tools/web.py backend/chatbot_server/pyproject.toml
git commit -m "feat(mcp): web tools — fetch_url and web_search with duckduckgo/brave providers"
```

---

### Task 7: Register New Tools in tool_definitions.py + live_executor.py

**Files:**
- Modify: `backend/chatbot_server/core/tool_definitions.py`
- Modify: `backend/chatbot_server/core/live_executor.py`

- [ ] **Step 1: Add 6 tool definitions to tool_definitions.py**

In `backend/chatbot_server/core/tool_definitions.py`, before the closing `]` of `ALL_TOOLS`, add:

```python
    {
        "name": "create_scheduled_job",
        "description": (
            "Create a scheduled job that runs on a cron schedule. "
            "Use source_type='news_poll' to schedule news fetching. "
            "cron_expression format: '0 7 * * *' = every day at 7am, '0 7 * * 1' = every Monday at 7am. "
            "Exactly one of person_id or household_id must be set."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "source_type":     {"type": "string", "description": "Job type. Use 'news_poll' for news."},
                "cron_expression": {"type": "string", "description": "Cron schedule. Example: '0 7 * * *' for 7am daily."},
                "person_id":       {"type": "string", "description": "UUID of the person this job runs for."},
                "household_id":    {"type": "string", "description": "UUID of the household (use instead of person_id for household jobs)."},
                "config":          {"type": "object", "description": "Job-specific config JSONB. Optional."},
            },
            "required": ["source_type", "cron_expression"],
        },
    },
    {
        "name": "list_scheduled_jobs",
        "description": "List all scheduled jobs for a person.",
        "input_schema": {
            "type": "object",
            "properties": {
                "person_id": {"type": "string", "description": "UUID of the person."},
            },
            "required": ["person_id"],
        },
    },
    {
        "name": "update_scheduled_job",
        "description": (
            "Update a scheduled job's cron expression, config, or enabled status. "
            "To pause a job: enabled=false. To resume: enabled=true. "
            "To change schedule: update cron_expression."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "job_id":          {"type": "string", "description": "UUID of the scheduled job."},
                "cron_expression": {"type": "string", "description": "New cron schedule. Optional."},
                "config":          {"type": "object", "description": "New config JSONB. Optional."},
                "enabled":         {"type": "boolean", "description": "Enable or pause the job. Optional."},
            },
            "required": ["job_id"],
        },
    },
    {
        "name": "delete_scheduled_job",
        "description": "Permanently delete a scheduled job.",
        "input_schema": {
            "type": "object",
            "properties": {
                "job_id": {"type": "string", "description": "UUID of the scheduled job to delete."},
            },
            "required": ["job_id"],
        },
    },
    {
        "name": "fetch_url",
        "description": (
            "Fetch a webpage and return its plain text content. "
            "Use this when the user wants to know more about a specific article — "
            "retrieve the URL from the stored news_article fact, then call this tool to get the full content. "
            "Returns up to 8000 characters of readable text."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "URL of the webpage to fetch."},
            },
            "required": ["url"],
        },
    },
    {
        "name": "web_search",
        "description": (
            "Search the web for background information. "
            "Use this when the user asks about a concept, person, organisation, or event that needs explanation. "
            "Returns top results with title, snippet, and URL. "
            "Always explain results in plain language — avoid jargon."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "query":       {"type": "string", "description": "Search query."},
                "num_results": {"type": "integer", "description": "Number of results to return. Default 5."},
            },
            "required": ["query"],
        },
    },
```

- [ ] **Step 2: Update the assert count**

Change line 698:
```python
assert len(ALL_TOOLS) == 41, f"Expected 41 tools, got {len(ALL_TOOLS)}"
```
to:
```python
assert len(ALL_TOOLS) == 47, f"Expected 47 tools, got {len(ALL_TOOLS)}"
```

- [ ] **Step 3: Register tools in live_executor.py**

In `backend/chatbot_server/core/live_executor.py`, update the imports:

```python
    from tools import (
        persons, households, person_household, relationships,
        documents, facts, schemas, reference, audit, files,
        scheduled_jobs, web,
    )
```

Add to `_build_tool_map()` return dict:

```python
        "create_scheduled_job":  scheduled_jobs.create_scheduled_job,
        "list_scheduled_jobs":   scheduled_jobs.list_scheduled_jobs,
        "update_scheduled_job":  scheduled_jobs.update_scheduled_job,
        "delete_scheduled_job":  scheduled_jobs.delete_scheduled_job,
        "fetch_url":             web.fetch_url,
        "web_search":            web.web_search,
```

- [ ] **Step 4: Verify**

```bash
cd backend/chatbot_server && python3 -c "from core.tool_definitions import ALL_TOOLS; print(len(ALL_TOOLS))"
```

Expected: `47`

```bash
python3 -c "from core.live_executor import LiveExecutor; print('ok')"
```

Expected: `ok`

- [ ] **Step 5: Commit**

```bash
git add backend/chatbot_server/core/tool_definitions.py \
        backend/chatbot_server/core/live_executor.py
git commit -m "feat(chatbot): register 6 new MCP tools — scheduled_jobs and web tools"
```

---

### Task 8: Scheduler Service

**Files:**
- Create: `backend/scheduler/pyproject.toml`
- Create: `backend/scheduler/handlers/__init__.py`
- Create: `backend/scheduler/handlers/base.py`
- Create: `backend/scheduler/providers/__init__.py`
- Create: `backend/scheduler/providers/news_source.py`
- Create: `backend/scheduler/providers/newsapi_source.py`
- Create: `backend/scheduler/handlers/news_poll.py`
- Create: `backend/scheduler/main.py`
- Create: `backend/scheduler/Dockerfile`

- [ ] **Step 1: Create pyproject.toml**

Create `backend/scheduler/pyproject.toml`:

```toml
[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[project]
name = "myassistant-scheduler"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = [
    "httpx>=0.27",
    "croniter>=2.0",
]
```

- [ ] **Step 2: Create handler base**

Create `backend/scheduler/handlers/__init__.py` (empty):
```python
```

Create `backend/scheduler/handlers/base.py`:

```python
from abc import ABC, abstractmethod


class BaseHandler(ABC):
    def __init__(self, http):
        self.http = http

    @abstractmethod
    def run(self, job: dict) -> dict:
        """Execute the job. Returns {"articles_stored": int, "error": str|None}."""
        ...
```

- [ ] **Step 3: Create news source provider**

Create `backend/scheduler/providers/__init__.py` (empty):
```python
```

Create `backend/scheduler/providers/news_source.py`:

```python
from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime


@dataclass
class Article:
    headline: str
    source: str
    url: str
    published_date: str  # YYYY-MM-DD
    topic: str
    description: str


class NewsSource(ABC):
    @abstractmethod
    def fetch(self, topic: str, since: datetime) -> list[Article]:
        ...
```

Create `backend/scheduler/providers/newsapi_source.py`:

```python
import os
import httpx
from datetime import datetime, timezone
from .news_source import Article, NewsSource


class NewsApiSource(NewsSource):
    def __init__(self):
        self.api_key = os.environ["NEWSAPI_KEY"]
        self.page_size = int(os.getenv("NEWSAPI_PAGE_SIZE", "10"))

    def fetch(self, topic: str, since: datetime) -> list[Article]:
        from_str = since.strftime("%Y-%m-%dT%H:%M:%S")
        with httpx.Client(timeout=15.0) as client:
            resp = client.get(
                "https://newsapi.org/v2/everything",
                params={
                    "q": topic,
                    "from": from_str,
                    "sortBy": "publishedAt",
                    "pageSize": self.page_size,
                    "language": "en",
                    "apiKey": self.api_key,
                },
            )
            resp.raise_for_status()
        articles = []
        for item in resp.json().get("articles", []):
            pub = item.get("publishedAt", "")[:10]  # YYYY-MM-DD
            articles.append(Article(
                headline=item.get("title", ""),
                source=item.get("source", {}).get("name", ""),
                url=item.get("url", ""),
                published_date=pub,
                topic=topic,
                description=item.get("description", "") or "",
            ))
        return articles
```

- [ ] **Step 4: Create the news poll handler**

Create `backend/scheduler/handlers/news_poll.py`:

```python
import logging
import uuid
from datetime import datetime, timedelta, timezone

from .base import BaseHandler
from providers.news_source import NewsSource

logger = logging.getLogger(__name__)


def _build_news_source() -> NewsSource:
    import os
    provider = os.getenv("NEWS_SOURCE_PROVIDER", "newsapi")
    if provider == "newsapi":
        from providers.newsapi_source import NewsApiSource
        return NewsApiSource()
    raise ValueError(f"Unknown NEWS_SOURCE_PROVIDER: {provider}")


class NewsPollHandler(BaseHandler):
    def __init__(self, http):
        super().__init__(http)
        self._source = _build_news_source()

    def run(self, job: dict) -> dict:
        assert job.get("personId"), "news_poll requires personId"

        # Get the last run time to fetch only new articles
        since = self._last_run_at(job)

        # Get active topics for this person
        topics = self._get_topics(job["personId"])
        if not topics:
            logger.info(f"No active news topics for person {job['personId']}, skipping")
            return {"articles_stored": 0, "error": None}

        # Get schema IDs needed for fact creation
        news_article_schema_id = self._get_schema_id("news", "news_article")

        stored = 0
        for topic in topics:
            try:
                articles = self._source.fetch(topic, since)
                for article in articles:
                    if not article.url or not article.headline:
                        continue
                    doc = self._create_document(article, job["personId"])
                    self._create_fact(doc["id"], news_article_schema_id, article)
                    stored += 1
            except Exception as e:
                logger.error(f"Error fetching topic '{topic}': {e}")

        return {"articles_stored": stored, "error": None}

    def _last_run_at(self, job: dict) -> datetime:
        """Return the start time of the last successful run, or 24h ago if no runs."""
        runs_resp = self.http.get(f"/api/v1/scheduled-jobs/{job['id']}/runs")
        if runs_resp.status_code == 200:
            runs = runs_resp.json()
            successful = [r for r in runs if r.get("status") == "success"]
            if successful:
                ts = successful[0].get("startedAt", "")
                if ts:
                    return datetime.fromisoformat(ts.replace("Z", "+00:00"))
        return datetime.now(timezone.utc) - timedelta(hours=24)

    def _get_topics(self, person_id: str) -> list[str]:
        """Get all active news_topic names for this person."""
        resp = self.http.get(
            "/api/v1/facts/current",
            params={"personId": person_id, "entityType": "news_topic"},
        )
        if resp.status_code != 200:
            return []
        items = resp.json().get("items", [])
        # API response uses "fields" (from CurrentFactResponse.fromDomain)
        return [
            item["fields"].get("name", "")
            for item in items
            if item.get("fields", {}).get("active") is not False
            and item.get("fields", {}).get("name")
        ]

    def _get_schema_id(self, domain: str, entity_type: str) -> str:
        resp = self.http.get(
            "/api/v1/schemas/current",
            params={"domain": domain, "entityType": entity_type},
        )
        resp.raise_for_status()
        return resp.json()["id"]

    def _create_document(self, article, person_id: str) -> dict:
        body = {
            "contentText": f"{article.headline}. {article.description}",
            "sourceTypeId": "news_poll",
            "personId": person_id,
        }
        resp = self.http.post("/api/v1/documents", json=body)
        resp.raise_for_status()
        return resp.json()

    def _create_fact(self, document_id: str, schema_id: str, article) -> dict:
        body = {
            "documentId": document_id,
            "schemaId": schema_id,
            "entityInstanceId": str(uuid.uuid4()),
            "operationType": "create",
            "fields": {
                "headline": article.headline,
                "source": article.source,
                "url": article.url,
                "published_date": article.published_date,
                "topic": article.topic,
                "description": article.description,
            },
        }
        resp = self.http.post("/api/v1/facts", json=body)
        resp.raise_for_status()
        return resp.json()
```

- [ ] **Step 5: Create main.py**

Create `backend/scheduler/main.py`:

```python
import logging
import os
import time
import uuid
from datetime import datetime, timezone

import httpx
from croniter import croniter

from handlers.news_poll import NewsPollHandler

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

HTTP_URL   = os.environ["HTTP_SERVER_URL"]
AUTH_TOKEN = os.environ["AUTH_TOKEN"]

HANDLERS = {
    "news_poll": NewsPollHandler,
}


def make_client() -> httpx.Client:
    return httpx.Client(
        base_url=HTTP_URL,
        headers={"Authorization": f"Bearer {AUTH_TOKEN}"},
        timeout=30.0,
    )


def compute_next_run(cron_expr: str) -> str:
    cron = croniter(cron_expr, datetime.now(timezone.utc))
    return cron.get_next(datetime).isoformat()


def main() -> None:
    logger.info("Scheduler starting, polling every 60s...")
    while True:
        try:
            with make_client() as http:
                resp = http.get("/api/v1/scheduled-jobs/due")
                if resp.status_code != 200:
                    logger.error(f"Failed to fetch due jobs: {resp.status_code} {resp.text}")
                    time.sleep(60)
                    continue

                jobs = resp.json()
                if jobs:
                    logger.info(f"Found {len(jobs)} due jobs")

                for job in jobs:
                    job_type = job.get("sourceType")
                    handler_cls = HANDLERS.get(job_type)
                    if handler_cls is None:
                        logger.warning(f"No handler for job type: {job_type}")
                        continue

                    run_id  = str(uuid.uuid4())
                    started = datetime.now(timezone.utc)
                    status  = "success"
                    error   = None
                    stored  = 0

                    try:
                        logger.info(f"Running job {job['id']} ({job_type})")
                        result = handler_cls(http).run(job)
                        stored = result.get("articles_stored", 0)
                        logger.info(f"Job {job['id']} done: {stored} articles stored")
                    except Exception as e:
                        status = "error"
                        error  = str(e)
                        logger.error(f"Job {job['id']} failed: {e}")

                    finished = datetime.now(timezone.utc)

                    # Record the run
                    http.post("/api/v1/scheduled-job-runs", json={
                        "id":             run_id,
                        "jobId":          job["id"],
                        "startedAt":      started.isoformat(),
                        "finishedAt":     finished.isoformat(),
                        "status":         status,
                        "error":          error,
                        "articlesStored": stored,
                    })

                    # Update next_run_at
                    next_run = compute_next_run(job["cronExpression"])
                    http.put(f"/api/v1/scheduled-jobs/{job['id']}", json={"nextRunAt": next_run})

        except Exception as e:
            logger.error(f"Scheduler tick error: {e}")

        time.sleep(60)


if __name__ == "__main__":
    main()
```

- [ ] **Step 6: Add scheduled-job-runs POST + job runs GET endpoints to Scala**

The main.py above calls `POST /api/v1/scheduled-job-runs` and `GET /api/v1/scheduled-jobs/{id}/runs`. Add both to `ScheduledJobRoutes.scala`.

First add `listRuns(jobId: UUID)` to `ScheduledJobService` and `ScheduledJobRepository` (follow the same pattern as `listByPerson` — query `scheduled_job_run WHERE job_id = ?`).

Then add these routes:

```scala
      Method.POST / "api" / "v1" / "scheduled-job-runs" ->
        handler { (req: Request) =>
          for
            bodyStr <- req.body.asString.orDie
            resp    <- decode[CreateScheduledJobRunRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(s"""{"error":"bad_request","message":"${err.getMessage.replace("\"","'")}"}""").status(Status.BadRequest))
              case Right(r) =>
                ZIO.serviceWithZIO[ScheduledJobService](_.recordRun(r.toDomain))
                  .foldZIO(
                    err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    run => ZIO.succeed(Response.json(run.asJson.noSpaces).status(Status.Created)),
                  )
          yield resp
        },
```

Add the corresponding model to `ScheduledJobModels.scala`:

```scala
final case class CreateScheduledJobRunRequest(
    id:             UUID,
    jobId:          UUID,
    startedAt:      Instant,
    finishedAt:     Option[Instant],
    status:         String,
    error:          Option[String],
    articlesStored: Int,
) derives Codec.AsObject:
  def toDomain: ScheduledJobRun = ScheduledJobRun(
    id             = id,
    jobId          = jobId,
    startedAt      = startedAt,
    finishedAt     = finishedAt,
    status         = status,
    error          = error,
    articlesStored = articlesStored,
  )
```

Recompile: `cd backend/http_server && sbt compile`

- [ ] **Step 7: Create Dockerfile**

- [ ] **Step 7a: Create rss_source.py stub**

Create `backend/scheduler/providers/rss_source.py`:

```python
from datetime import datetime
from .news_source import Article, NewsSource


class RssSource(NewsSource):
    """RSS feed news source. Not yet implemented — stub for future use."""

    def fetch(self, topic: str, since: datetime) -> list[Article]:
        raise NotImplementedError("RssSource not yet implemented. Use NEWS_SOURCE_PROVIDER=newsapi")
```

- [ ] **Step 8: Create Dockerfile**

Create `backend/scheduler/Dockerfile`:

```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY pyproject.toml .
RUN pip install --no-cache-dir .
COPY . .
CMD ["python", "main.py"]
```

- [ ] **Step 8: Test locally**

```bash
cd backend/scheduler
pip install -e .
HTTP_SERVER_URL=http://localhost:8080 AUTH_TOKEN=<token> NEWS_SOURCE_PROVIDER=newsapi NEWSAPI_KEY=<key> python main.py
```

Expected log: `Scheduler starting, polling every 60s...` — then either `No active news topics` (if none set) or article ingestion.

- [ ] **Step 9: Commit**

```bash
git add backend/scheduler/
git commit -m "feat(scheduler): generic scheduler service with news_poll handler"
```

---

### Task 9: Docker Compose + Documentation

**Files:**
- Modify: `docker-compose.yml`
- Modify: `docs/http-contract.md`
- Modify: `docs/mcp-tools.md`

- [ ] **Step 1: Add scheduler to docker-compose.yml**

Add the `scheduler` service after `chatbot-server`:

```yaml
  scheduler:
    image: ghcr.io/raviagg/myassistant/scheduler:latest
    restart: unless-stopped
    environment:
      HTTP_SERVER_URL: http://http-server:8080
      AUTH_TOKEN: ${AUTH_TOKEN}
      NEWS_SOURCE_PROVIDER: newsapi
      NEWSAPI_KEY: ${NEWSAPI_KEY}
      WEB_SEARCH_PROVIDER: duckduckgo
    depends_on:
      http-server:
        condition: service_healthy
    networks:
      - app-net
```

Also add `NEWSAPI_KEY: ${NEWSAPI_KEY}` and `WEB_SEARCH_PROVIDER` to the `chatbot-server` environment block:

```yaml
      WEB_SEARCH_PROVIDER: ${WEB_SEARCH_PROVIDER:-duckduckgo}
      BRAVE_API_KEY: ${BRAVE_API_KEY:-}
```

- [ ] **Step 2: Add scheduler to CI/CD**

In `.github/workflows/deploy.yml`, add a build step for the scheduler image. Follow the same pattern as the chatbot-server build step:
- Build context: `backend/scheduler`
- Image: `ghcr.io/raviagg/myassistant/scheduler:latest`

- [ ] **Step 3: Update docs/http-contract.md**

Add the following endpoints to `docs/http-contract.md` under a new `## Scheduled Jobs` section:

```
POST   /api/v1/scheduled-jobs
       Body: { sourceType, cronExpression, personId?, householdId?, config? }
       Returns: 201 ScheduledJobResponse

GET    /api/v1/scheduled-jobs?personId={uuid}
       Returns: 200 ScheduledJobResponse[]

GET    /api/v1/scheduled-jobs/due
       Returns: 200 ScheduledJobResponse[] (enabled jobs where next_run_at <= now())

PUT    /api/v1/scheduled-jobs/{id}
       Body: { cronExpression?, config?, enabled?, nextRunAt? }
       Returns: 200 ScheduledJobResponse

DELETE /api/v1/scheduled-jobs/{id}
       Returns: 204 No Content

POST   /api/v1/scheduled-job-runs
       Body: { id, jobId, startedAt, finishedAt?, status, error?, articlesStored }
       Returns: 201 ScheduledJobRunResponse
```

- [ ] **Step 4: Update docs/mcp-tools.md**

Add entries for all 6 new tools to `docs/mcp-tools.md` following the existing format.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml docs/http-contract.md docs/mcp-tools.md
git commit -m "feat: wire scheduler into docker-compose and update docs"
```

---

## Verification

1. **Migration**: `psql` → confirm `scheduled_job`, `scheduled_job_run` tables exist; `news` domain exists; `news_poll` source_type exists; `news_topic` and `news_article` schemas present in `entity_type_schema`.

2. **Schedule management via chat**: Say "Schedule news about AI every morning at 7am" → agent should call `create_scheduled_job(source_type="news_poll", cron_expression="0 7 * * *", person_id=<your_id>)`. Verify with "What jobs are scheduled?" → `list_scheduled_jobs`.

3. **Scheduler fires**: Insert a job with `next_run_at = now() - interval '1 minute'` directly in psql, start the scheduler, confirm a `scheduled_job_run` row appears and `news_article` facts are stored.

4. **News query via chat**: Say "What's in the news today?" → agent should call `search_current_facts` with `entityType=news_article` and return articles.

5. **Drill-down**: Say "Tell me more about [article headline]" → agent should call `search_current_facts` to find the URL, then `fetch_url(url)` and explain in plain language.

6. **Web search**: Say "What is quantitative easing?" → agent should call `web_search("quantitative easing simple explanation")` and return a plain-language explanation.
