package com.myassistant.db.repositories

import com.myassistant.api.models.*
import com.myassistant.api.schemas.NativeSchemaRegistry
import com.myassistant.domain.{CreateUnifiedSchema, PatchUnifiedSchema, UnifiedSchema}
import com.myassistant.errors.AppError
import io.circe.Json
import io.circe.parser as circeParser
import io.circe.syntax.*
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

trait UnifiedSchemaRepository:
  def create(req: CreateUnifiedSchema): ZIO[ZConnectionPool, AppError, UnifiedSchema]
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[UnifiedSchema]]
  def list(personId: Option[UUID], householdId: Option[UUID]): ZIO[ZConnectionPool, AppError, List[UnifiedSchema]]
  def patch(id: UUID, req: PatchUnifiedSchema): ZIO[ZConnectionPool, AppError, Option[UnifiedSchema]]
  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean]
  def sourceSchemas(personId: Option[UUID], householdId: Option[UUID]): ZIO[ZConnectionPool, AppError, SourceSchemasResponse]
  def sampleRows(sourceType: String, tableName: String, sourceConnectionId: Option[UUID], personId: Option[UUID], householdId: Option[UUID], limit: Int): ZIO[ZConnectionPool, AppError, List[Json]]
  def data(id: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, UnifiedDataResponse]

object UnifiedSchemaRepository:

  // id, person_id (text|null), household_id (text|null), name, description,
  // status, field_definitions (text), created_at, updated_at
  private type SchemaRow =
    (String, Option[String], Option[String], String, Option[String],
     String, String, java.sql.Timestamp, java.sql.Timestamp)

  private val cols = SqlFragment(
    """id::text, person_id::text, household_id::text, name, description,
       status, field_definitions::text, created_at, updated_at"""
  )

  private def rowToSchema(row: SchemaRow): UnifiedSchema =
    val (id, personId, householdId, name, description, status, fieldDefsJson, createdAt, updatedAt) = row
    UnifiedSchema(
      id               = UUID.fromString(id),
      personId         = personId.map(UUID.fromString),
      householdId      = householdId.map(UUID.fromString),
      name             = name,
      description      = description,
      status           = status,
      fieldDefinitions = circeParser.parse(fieldDefsJson).getOrElse(Json.arr()),
      createdAt        = createdAt.toInstant,
      updatedAt        = updatedAt.toInstant,
    )

  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  final class Live extends UnifiedSchemaRepository:

    def create(req: CreateUnifiedSchema): ZIO[ZConnectionPool, AppError, UnifiedSchema] =
      val id           = UUID.randomUUID()
      val fieldDefsStr = req.fieldDefinitions.noSpaces
      val q = sql"""
        INSERT INTO unified_schema(id, person_id, household_id, name, description, status, field_definitions)
        VALUES (
          ${id.toString}::uuid,
          ${req.personId.map(_.toString)}::uuid,
          ${req.householdId.map(_.toString)}::uuid,
          ${req.name},
          ${req.description},
          ${req.status},
          ${fieldDefsStr}::jsonb
        )
        RETURNING """ ++ cols
      transaction(q.query[SchemaRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT unified_schema returned no row"))))
        .map(rowToSchema)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[UnifiedSchema]] =
      val q = sql"SELECT " ++ cols ++ sql" FROM unified_schema WHERE id = ${id.toString}::uuid"
      transaction(q.query[SchemaRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToSchema))

    def list(personId: Option[UUID], householdId: Option[UUID]): ZIO[ZConnectionPool, AppError, List[UnifiedSchema]] =
      val conds = List.concat(
        personId.map(v    => sql"person_id = ${v.toString}::uuid"),
        householdId.map(v => sql"household_id = ${v.toString}::uuid"),
      )
      val where = conds match
        case Nil => SqlFragment("")
        case cs  => SqlFragment(" WHERE ") ++ cs.reduce(_ ++ SqlFragment(" AND ") ++ _)
      val q = sql"SELECT " ++ cols ++ sql" FROM unified_schema" ++ where ++ sql" ORDER BY created_at"
      transaction(q.query[SchemaRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToSchema))

    def patch(id: UUID, req: PatchUnifiedSchema): ZIO[ZConnectionPool, AppError, Option[UnifiedSchema]] =
      val sets = List.concat(
        req.name.map(v             => sql"name = $v"),
        req.description.map(v      => sql"description = $v"),
        req.status.map(v           => sql"status = $v"),
        req.fieldDefinitions.map(v => sql"field_definitions = ${v.noSpaces}::jsonb"),
      )
      if sets.isEmpty then findById(id)
      else
        val setClause = sets.reduce(_ ++ SqlFragment(", ") ++ _)
        val q = sql"UPDATE unified_schema SET " ++ setClause ++
                sql" WHERE id = ${id.toString}::uuid RETURNING " ++ cols
        transaction(q.query[SchemaRow].selectOne)
          .mapError(mapSqlError)
          .map(_.map(rowToSchema))

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      transaction(
        sql"DELETE FROM unified_schema WHERE id = ${id.toString}::uuid".delete
      ).mapError(mapSqlError).map(_ > 0)

    def sourceSchemas(
        personId:    Option[UUID],
        householdId: Option[UUID],
    ): ZIO[ZConnectionPool, AppError, SourceSchemasResponse] =
      val scopeFilter = (personId, householdId) match
        case (Some(pid), _) => sql"(sc.person_id = ${pid.toString}::uuid)"
        case (_, Some(hid)) => sql"(sc.household_id = ${hid.toString}::uuid)"
        case _              => sql"(1=1)"

      // Fetch source_connections for the pivot — returns (id, source_type, connection_name)
      val connQ = sql"""
        SELECT sc.id::text, sc.source_type, sc.connection_name
        FROM source_connections sc
        WHERE """ ++ scopeFilter ++ sql" ORDER BY sc.created_at"

      type ConnRow = (String, String, String)

      // Fetch entity_type_schema groups for entity_type_schema-type connections
      val etsQ = sql"""
        SELECT sc.id::text, ets.entity_type, ets.field_definitions::text
        FROM source_connections sc
        JOIN fact f ON f.source_connection_id = sc.id
        JOIN entity_type_schema ets ON f.schema_id = ets.id AND ets.is_active = true
        WHERE """ ++ scopeFilter ++ sql"""
        GROUP BY sc.id, ets.entity_type, ets.field_definitions
        ORDER BY sc.id, ets.entity_type"""

      type EtsRow = (String, String, String)

      // Fetch entity_type_schemas from facts that have no source_connection (chatbot / AI-extracted)
      val personIdStr    = personId.map(_.toString)
      val householdIdStr = householdId.map(_.toString)
      val chatbotQ = sql"""
        SELECT ets.entity_type, ets.field_definitions::text
        FROM fact f
        JOIN document d ON f.document_id = d.id
        JOIN entity_type_schema ets ON f.schema_id = ets.id AND ets.is_active = true
        WHERE f.source_connection_id IS NULL
          AND (${personIdStr}::uuid IS NULL OR d.person_id = ${personIdStr}::uuid)
          AND (${householdIdStr}::uuid IS NULL OR d.household_id = ${householdIdStr}::uuid)
        GROUP BY ets.entity_type, ets.field_definitions
        ORDER BY ets.entity_type"""

      type ChatbotRow = (String, String)

      for
        conns       <- transaction(connQ.query[ConnRow].selectAll)
                         .mapError(mapSqlError)
                         .map(_.toList)
        etss        <- transaction(etsQ.query[EtsRow].selectAll)
                         .mapError(mapSqlError)
                         .map(_.toList)
        chatbotEts  <- transaction(chatbotQ.query[ChatbotRow].selectAll)
                         .mapError(mapSqlError)
                         .map(_.toList)
      yield buildSourceSchemasResponse(conns, etss, chatbotEts)

    private def buildSourceSchemasResponse(
        conns:      List[(String, String, String)],
        etss:       List[(String, String, String)],
        chatbotEts: List[(String, String)],
    ): SourceSchemasResponse =
      val profile = SourceGroupResponse(
        sourceConnectionId = None,
        sourceType         = "profile",
        connectionName     = "Profile",
        tables             = NativeSchemaRegistry.profileTables,
      )

      val etsMap: Map[String, List[(String, String)]] =
        etss.groupMap(_._1)(r => (r._2, r._3))

      def etsTables(sourceType: String, connId: String): List[SourceTableResponse] =
        etsMap.getOrElse(connId, Nil).map: (entityType, fieldDefsJson) =>
          val fields = circeParser.parse(fieldDefsJson).getOrElse(Json.arr())
          val columns = fields.asArray.getOrElse(Vector.empty).toList.flatMap: fieldDef =>
            for
              name  <- fieldDef.hcursor.get[String]("name").toOption
              ftype <- fieldDef.hcursor.get[String]("type").toOption
            yield SourceColumnResponse(name, ftype)
          SourceTableResponse(
            tableName   = s"$sourceType/$entityType",
            columns     = columns,
            foreignKeys = Nil,
          )

      val sourceGroups = conns.map: (connId, sourceType, connName) =>
        val tables: List[SourceTableResponse] = sourceType match
          case "plaid_poll" => NativeSchemaRegistry.plaidTables
          case "news_poll"  => NativeSchemaRegistry.newsTables
          case _            => etsTables(sourceType, connId)

        SourceGroupResponse(
          sourceConnectionId = Some(UUID.fromString(connId)),
          sourceType         = sourceType,
          connectionName     = connName,
          tables             = tables,
        )

      val chatbotTables = chatbotEts.map: (entityType, fieldDefsJson) =>
        val fields = circeParser.parse(fieldDefsJson).getOrElse(Json.arr())
        val columns = fields.asArray.getOrElse(Vector.empty).toList.flatMap: fieldDef =>
          for
            name  <- fieldDef.hcursor.get[String]("name").toOption
            ftype <- fieldDef.hcursor.get[String]("type").toOption
          yield SourceColumnResponse(name, ftype)
        SourceTableResponse(
          tableName   = s"chatbot/$entityType",
          columns     = columns,
          foreignKeys = Nil,
        )

      val chatbotGroup =
        if chatbotTables.isEmpty then Nil
        else List(SourceGroupResponse(
          sourceConnectionId = None,
          sourceType         = "chatbot",
          connectionName     = "Chatbot",
          tables             = chatbotTables,
        ))

      SourceSchemasResponse(profile = profile, sources = chatbotGroup ++ sourceGroups)

    def sampleRows(
        sourceType:         String,
        tableName:          String,
        sourceConnectionId: Option[UUID],
        personId:           Option[UUID],
        householdId:        Option[UUID],
        limit:              Int,
    ): ZIO[ZConnectionPool, AppError, List[Json]] =
      val scIdStr        = sourceConnectionId.map(_.toString)
      val personIdStr    = personId.map(_.toString)
      val householdIdStr = householdId.map(_.toString)
      val safeLimit      = limit.max(1).min(20)

      def parseRows(strs: Chunk[String]): List[Json] =
        strs.toList.flatMap(s => circeParser.parse(s).toOption)

      sourceType match
        case "plaid_poll" =>
          val q = tableName match
            case "plaid.transactions" => sql"""
              SELECT jsonb_build_object(
                'amount', t.amount::text, 'date', t.date::text,
                'merchant_name', t.merchant_name,
                'category', array_to_string(t.category, ', '),
                'payment_channel', t.payment_channel,
                'pending', t.pending::text
              )::text
              FROM plaid.transactions t
              WHERE t.source_connection_id = ${scIdStr}::uuid
              ORDER BY t.date DESC LIMIT $safeLimit"""
            case "plaid.bank_accounts" => sql"""
              SELECT jsonb_build_object(
                'name', ba.name, 'account_type', ba.account_type,
                'current_balance', ba.current_balance::text
              )::text
              FROM plaid.bank_accounts ba
              WHERE ba.source_connection_id = ${scIdStr}::uuid
              LIMIT $safeLimit"""
            case "plaid.connections" => sql"""
              SELECT jsonb_build_object(
                'institution_name', c.institution_name,
                'plaid_item_id', c.plaid_item_id
              )::text
              FROM plaid.connections c
              WHERE c.source_connection_id = ${scIdStr}::uuid
              LIMIT $safeLimit"""
            case _ => sql"SELECT NULL::text WHERE false"
          transaction(q.query[String].selectAll).mapError(mapSqlError).map(parseRows)

        case "chatbot" =>
          val entityType = tableName.stripPrefix("chatbot/")
          val q = sql"""
            SELECT DISTINCT ON (f.entity_instance_id) f.fields::text
            FROM fact f
            JOIN document d ON f.document_id = d.id
            JOIN entity_type_schema ets ON f.schema_id = ets.id
            WHERE ets.entity_type = $entityType
              AND f.source_connection_id IS NULL
              AND (${personIdStr}::uuid IS NULL OR d.person_id = ${personIdStr}::uuid)
              AND (${householdIdStr}::uuid IS NULL OR d.household_id = ${householdIdStr}::uuid)
            ORDER BY f.entity_instance_id, f.created_at DESC
            LIMIT $safeLimit"""
          transaction(q.query[String].selectAll).mapError(mapSqlError).map(parseRows)

        case "news_poll" =>
          val entityType = tableName.stripPrefix("news_poll/")
          val q = sql"""
            SELECT DISTINCT ON (f.entity_instance_id) f.fields::text
            FROM fact f
            JOIN document d ON f.document_id = d.id
            JOIN source_type st ON d.source_type_id = st.id
            JOIN entity_type_schema ets ON f.schema_id = ets.id
            WHERE ets.entity_type = $entityType
              AND st.name = 'news_poll'
              AND (${personIdStr}::uuid IS NULL OR d.person_id = ${personIdStr}::uuid)
              AND (${householdIdStr}::uuid IS NULL OR d.household_id = ${householdIdStr}::uuid)
            ORDER BY f.entity_instance_id, f.created_at DESC
            LIMIT $safeLimit"""
          transaction(q.query[String].selectAll).mapError(mapSqlError).map(parseRows)

        case _ => ZIO.succeed(Nil)

    def data(id: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, UnifiedDataResponse] =
      findById(id).flatMap:
        case None => ZIO.succeed(UnifiedDataResponse(items = Nil, total = 0, limit = limit, offset = offset))
        case Some(schema) =>
          val personIdStr    = schema.personId.map(_.toString)
          val householdIdStr = schema.householdId.map(_.toString)

          val fieldDefs = schema.fieldDefinitions.asArray.getOrElse(Vector.empty).toList
          // Gate: only query if at least one field is approved — full column projection handled client-side
          val hasApprovedFields = fieldDefs.exists: fd =>
            fd.hcursor.get[String]("status").toOption.exists(_ == "approved")

          if !hasApprovedFields then
            ZIO.succeed(UnifiedDataResponse(items = Nil, total = 0, limit = limit, offset = offset))
          else
            val countQ = sql"""
              SELECT COUNT(*)::text
              FROM plaid.transactions t
              JOIN source_connections sc ON t.source_connection_id = sc.id
              WHERE (${personIdStr}::uuid IS NULL OR sc.person_id = ${personIdStr}::uuid)
                AND (${householdIdStr}::uuid IS NULL OR sc.household_id = ${householdIdStr}::uuid)"""

            val plaidQ = sql"""
              SELECT
                t.source_connection_id::text AS source_connection_id,
                'plaid_poll'                 AS source_type,
                t.id::text                   AS row_id,
                t.amount::text               AS amount,
                t.date::text                 AS date,
                t.merchant_name              AS merchant_name,
                array_to_string(t.category, ',') AS category,
                t.payment_channel            AS payment_channel,
                t.pending::text              AS pending
              FROM plaid.transactions t
              JOIN source_connections sc ON t.source_connection_id = sc.id
              WHERE (${personIdStr}::uuid IS NULL OR sc.person_id = ${personIdStr}::uuid)
                AND (${householdIdStr}::uuid IS NULL OR sc.household_id = ${householdIdStr}::uuid)
              ORDER BY t.date DESC
              LIMIT $limit OFFSET $offset"""

            type DataRow = (String, String, String, Option[String], Option[String],
                            Option[String], Option[String], Option[String], Option[String])

            for
              countStr <- transaction(countQ.query[String].selectOne)
                            .mapError(mapSqlError)
                            .map(_.flatMap(_.toLongOption).getOrElse(0L).toInt)
              rows     <- transaction(plaidQ.query[DataRow].selectAll)
                            .mapError(mapSqlError)
              items     = rows.toList.map: row =>
                            val (scId, srcType, rowId, amount, date, merchant, category, channel, pending) = row
                            val fields = Map(
                              "id"              -> Json.fromString(rowId),
                              "amount"          -> amount.map(Json.fromString).getOrElse(Json.Null),
                              "date"            -> date.map(Json.fromString).getOrElse(Json.Null),
                              "merchant_name"   -> merchant.map(Json.fromString).getOrElse(Json.Null),
                              "category"        -> category.map(Json.fromString).getOrElse(Json.Null),
                              "payment_channel" -> channel.map(Json.fromString).getOrElse(Json.Null),
                              "pending"         -> pending.map(Json.fromString).getOrElse(Json.Null),
                            )
                            UnifiedDataRow(
                              sourceConnectionId = Some(UUID.fromString(scId)),
                              sourceType         = srcType,
                              fields             = fields,
                            )
            yield UnifiedDataResponse(items = items, total = countStr, limit = limit, offset = offset)

  val live: ZLayer[Any, Nothing, UnifiedSchemaRepository] =
    ZLayer.succeed(new Live)
