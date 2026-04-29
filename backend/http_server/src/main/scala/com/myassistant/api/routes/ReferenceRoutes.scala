package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{DomainResponse, KinshipAliasResponse, SourceTypeResponse}
import com.myassistant.services.ReferenceService
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

object ReferenceRoutes:

  val routes: Routes[ReferenceService & ZConnectionPool, Nothing] =
    Routes(
      Method.GET / "api" / "v1" / "reference" / "domains" ->
        handler { (_: Request) =>
          ZIO.serviceWithZIO[ReferenceService](_.listDomains)
            .foldZIO(
              err     => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              domains =>
                val items = domains.map(d =>
                  DomainResponse(id = d.id, name = d.name, description = d.description, createdAt = d.createdAt)
                )
                ZIO.succeed(Response.json(
                  io.circe.Json.obj("items" -> io.circe.Json.arr(items.map(_.asJson)*)).noSpaces
                ))
            )
        },

      Method.GET / "api" / "v1" / "reference" / "source-types" ->
        handler { (_: Request) =>
          ZIO.serviceWithZIO[ReferenceService](_.listSourceTypes)
            .foldZIO(
              err         => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              sourceTypes =>
                val items = sourceTypes.map(st =>
                  SourceTypeResponse(id = st.id, name = st.name, description = st.description, createdAt = st.createdAt)
                )
                ZIO.succeed(Response.json(
                  io.circe.Json.obj("items" -> io.circe.Json.arr(items.map(_.asJson)*)).noSpaces
                ))
            )
        },

      Method.GET / "api" / "v1" / "reference" / "kinship-aliases" ->
        handler { (req: Request) =>
          val language = req.queryParam("language")
          ZIO.serviceWithZIO[ReferenceService](_.listKinshipAliases(language))
            .foldZIO(
              err     => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              aliases =>
                val items = aliases.map(a =>
                  KinshipAliasResponse(
                    id            = a.id,
                    relationChain = a.relationChain,
                    language      = a.language,
                    alias         = a.alias,
                    description   = a.description,
                    createdAt     = a.createdAt,
                  )
                )
                ZIO.succeed(Response.json(
                  io.circe.Json.obj("items" -> io.circe.Json.arr(items.map(_.asJson)*)).noSpaces
                ))
            )
        },
    )
