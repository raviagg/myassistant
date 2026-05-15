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
  def createLinkToken(clientUserId: String): Task[String]
  def exchangePublicToken(publicToken: String): Task[(String, String)]
  def getAccounts(accessToken: String): Task[PlaidAccountsResp]
  def getInstitutionName(institutionId: String): Task[String]
  def syncTransactions(accessToken: String, cursor: Option[String]): Task[PlaidSyncResp]

object PlaidClient:

  final class Live(cfg: PlaidConfig) extends PlaidClient:

    private val http = JHttpClient.newHttpClient()

    private val baseUrl: String = cfg.env match
      case "production"  => "https://production.plaid.com"
      case "development" => "https://development.plaid.com"
      case _             => "https://sandbox.plaid.com"

    private def withAuth(body: Json): Json =
      Json.fromJsonObject(
        body.asObject.getOrElse(io.circe.JsonObject.empty)
          .add("client_id", Json.fromString(cfg.clientId))
          .add("secret",    Json.fromString(cfg.secret))
      )

    private def post[A: io.circe.Decoder](path: String, body: Json): Task[A] =
      ZIO.attemptBlocking {
        val req = HttpRequest.newBuilder()
          .uri(URI.create(s"$baseUrl$path"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(withAuth(body).noSpaces, StandardCharsets.UTF_8))
          .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if resp.statusCode() >= 400 then
          throw RuntimeException(s"Plaid API error ${resp.statusCode()}: ${resp.body().take(500)}")
        decode[A](resp.body()) match
          case Right(v) => v
          case Left(e)  => throw RuntimeException(s"Plaid decode error for $path: $e")
      }

    def createLinkToken(clientUserId: String): Task[String] =
      post[PlaidLinkTokenResp]("/link/token/create", Json.obj(
        "user"         -> Json.obj("client_user_id" -> Json.fromString(clientUserId)),
        "client_name"  -> Json.fromString("myassistant"),
        "products"     -> Json.arr(Json.fromString("transactions")),
        "country_codes"-> Json.arr(Json.fromString("US")),
        "language"     -> Json.fromString("en"),
      )).map(_.link_token)

    def exchangePublicToken(publicToken: String): Task[(String, String)] =
      post[PlaidExchangeResp]("/item/public_token/exchange", Json.obj(
        "public_token" -> Json.fromString(publicToken),
      )).map(r => (r.access_token, r.item_id))

    def getAccounts(accessToken: String): Task[PlaidAccountsResp] =
      post[PlaidAccountsResp]("/accounts/get", Json.obj(
        "access_token" -> Json.fromString(accessToken),
      ))

    def getInstitutionName(institutionId: String): Task[String] =
      post[PlaidInstitutionResp]("/institutions/get_by_id", Json.obj(
        "institution_id" -> Json.fromString(institutionId),
        "country_codes"  -> Json.arr(Json.fromString("US")),
      )).map(_.institution.name)

    def syncTransactions(accessToken: String, cursor: Option[String]): Task[PlaidSyncResp] =
      val body = cursor.foldLeft(Json.obj("access_token" -> Json.fromString(accessToken))) { (b, c) =>
        Json.fromJsonObject(b.asObject.getOrElse(io.circe.JsonObject.empty).add("cursor", Json.fromString(c)))
      }
      post[PlaidSyncResp]("/transactions/sync", body)

  val live: ZLayer[PlaidConfig, Nothing, PlaidClient] =
    ZLayer.fromFunction(new Live(_))
