package com.myassistant.api.plaid

import com.myassistant.config.PlaidConfig
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*

import java.net.URI
import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

trait PlaidClient:
  def createLinkToken(clientUserId: String, clientId: String, secret: String): Task[String]
  def exchangePublicToken(publicToken: String, clientId: String, secret: String): Task[(String, String)]
  def getAccounts(accessToken: String, clientId: String, secret: String): Task[PlaidAccountsResp]
  def getInstitutionName(institutionId: String, clientId: String, secret: String): Task[String]
  def syncTransactions(accessToken: String, cursor: Option[String], clientId: String, secret: String): Task[PlaidSyncResp]

object PlaidClient:

  final class Live(cfg: PlaidConfig) extends PlaidClient:

    private val http = JHttpClient.newHttpClient()

    private val baseUrl: String = cfg.env match
      case "production"  => "https://production.plaid.com"
      case "development" => "https://development.plaid.com"
      case _             => "https://sandbox.plaid.com"

    private def withAuth(body: Json, clientId: String, secret: String): Json =
      Json.fromJsonObject(
        body.asObject.getOrElse(io.circe.JsonObject.empty)
          .add("client_id", Json.fromString(clientId))
          .add("secret",    Json.fromString(secret))
      )

    private def post[A: io.circe.Decoder](path: String, body: Json): Task[A] =
      ZIO.attemptBlocking {
        val req = HttpRequest.newBuilder()
          .uri(URI.create(s"$baseUrl$path"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body.noSpaces, StandardCharsets.UTF_8))
          .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if resp.statusCode() >= 400 then
          throw RuntimeException(s"Plaid API error ${resp.statusCode()}: ${resp.body().take(500)}")
        decode[A](resp.body()) match
          case Right(v) => v
          case Left(e)  => throw RuntimeException(s"Plaid decode error for $path: $e")
      }

    def createLinkToken(clientUserId: String, clientId: String, secret: String): Task[String] =
      val base = Json.obj(
        "user"          -> Json.obj("client_user_id" -> Json.fromString(clientUserId)),
        "client_name"   -> Json.fromString("myassistant"),
        "products"      -> Json.arr(Json.fromString("transactions")),
        "country_codes" -> Json.arr(Json.fromString("US")),
        "language"      -> Json.fromString("en"),
      )
      val body = if cfg.redirectUri.nonEmpty then
        Json.fromJsonObject(
          base.asObject.getOrElse(io.circe.JsonObject.empty)
            .add("redirect_uri", Json.fromString(cfg.redirectUri))
        )
      else base
      post[PlaidLinkTokenResp]("/link/token/create", withAuth(body, clientId, secret))
        .map(_.link_token)

    def exchangePublicToken(publicToken: String, clientId: String, secret: String): Task[(String, String)] =
      post[PlaidExchangeResp]("/item/public_token/exchange",
        withAuth(Json.obj("public_token" -> Json.fromString(publicToken)), clientId, secret)
      ).map(r => (r.access_token, r.item_id))

    def getAccounts(accessToken: String, clientId: String, secret: String): Task[PlaidAccountsResp] =
      post[PlaidAccountsResp]("/accounts/get",
        withAuth(Json.obj("access_token" -> Json.fromString(accessToken)), clientId, secret)
      )

    def getInstitutionName(institutionId: String, clientId: String, secret: String): Task[String] =
      post[PlaidInstitutionResp]("/institutions/get_by_id",
        withAuth(Json.obj(
          "institution_id" -> Json.fromString(institutionId),
          "country_codes"  -> Json.arr(Json.fromString("US")),
        ), clientId, secret)
      ).map(_.institution.name)

    def syncTransactions(accessToken: String, cursor: Option[String], clientId: String, secret: String): Task[PlaidSyncResp] =
      val base = Json.obj("access_token" -> Json.fromString(accessToken))
      val withCursor = cursor.foldLeft(base) { (b, c) =>
        Json.fromJsonObject(b.asObject.getOrElse(io.circe.JsonObject.empty).add("cursor", Json.fromString(c)))
      }
      post[PlaidSyncResp]("/transactions/sync", withAuth(withCursor, clientId, secret))

  val live: ZLayer[PlaidConfig, Nothing, PlaidClient] =
    ZLayer.fromFunction(new Live(_))
