package com.myassistant.api

import com.myassistant.api.middleware.{AuthMiddleware, LoggingMiddleware}
import com.myassistant.api.routes.*
import com.myassistant.config.AuthConfig
import com.myassistant.services.*
import zio.*
import zio.http.*
import zio.jdbc.*

/** Combines all route groups into a single ZIO HTTP application.
 *
 *  Health is unauthenticated.  All other routes require a valid bearer token.
 *  Logging middleware is applied to all routes.
 */
object Router:

  /** Type alias for the full environment required by all routes. */
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
      & ZConnectionPool
      & AuthConfig

  /** Build the complete HTTP routes combining public and protected route groups.
   *
   *  Returns `Routes[AppEnv, Nothing]` which is directly accepted by `Server.serve`.
   */
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
          FileRoutes.routes) @@ AuthMiddleware(authCfg.token)

      (publicRoutes ++ protectedRoutes) @@ LoggingMiddleware.logRequests
    }
