package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{DomainResponse, KinshipAliasResponse, SourceTypeResponse}
import com.myassistant.services.ReferenceService
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

/** HTTP routes for reference data (domains, source types, kinship aliases).
 *
 *  GET    /api/v1/reference/domains           — list all life domains
 *  GET    /api/v1/reference/source-types      — list all data source types
 *  GET    /api/v1/reference/kinship-aliases   — list kinship aliases (query: language)
 */
object ReferenceRoutes:

  /** Build reference data routes requiring ReferenceService and ZConnectionPool in the environment. */
  val routes: Routes[ReferenceService & ZConnectionPool, Nothing] =
    Routes(
      Method.GET / "api" / "v1" / "reference" / "domains" ->
        handler { (_: Request) =>
          ZIO.serviceWithZIO[ReferenceService](_.listDomains)
            .foldZIO(
              err     => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              domains =>
                ZIO.succeed(Response.json(
                  domains.map(d =>
                    DomainResponse(name = d.name, description = d.description, createdAt = d.createdAt)
                  ).asJson.noSpaces
                ))
            )
        },

      Method.GET / "api" / "v1" / "reference" / "source-types" ->
        handler { (_: Request) =>
          ZIO.serviceWithZIO[ReferenceService](_.listSourceTypes)
            .foldZIO(
              err         => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              sourceTypes =>
                ZIO.succeed(Response.json(
                  sourceTypes.map(st =>
                    SourceTypeResponse(name = st.name, description = st.description, createdAt = st.createdAt)
                  ).asJson.noSpaces
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
                ZIO.succeed(Response.json(
                  aliases.map(a =>
                    KinshipAliasResponse(
                      id            = a.id,
                      relationChain = a.relationChain,
                      language      = a.language,
                      alias         = a.alias,
                      description   = a.description,
                      createdAt     = a.createdAt,
                    )
                  ).asJson.noSpaces
                ))
            )
        },
    )
