package com.myassistant.api.embed

import com.myassistant.config.EmbedConfig
import io.circe.parser.parse
import zio.*

import java.net.URI
import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

trait EmbedClient:
  def embed(text: String): Task[List[Double]]

object EmbedClient:

  final class Live(cfg: EmbedConfig) extends EmbedClient:
    private val http = JHttpClient.newHttpClient()

    def embed(text: String): Task[List[Double]] =
      ZIO.attemptBlocking {
        val body = s"""{"text":${io.circe.Json.fromString(text).noSpaces}}"""
        val req  = HttpRequest.newBuilder()
          .uri(URI.create(s"${cfg.serviceUrl}/api/embed"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if resp.statusCode() >= 400 then
          throw RuntimeException(s"Embed service error ${resp.statusCode()}: ${resp.body().take(200)}")
        parse(resp.body()).flatMap(_.hcursor.downField("embedding").as[List[Double]]) match
          case Right(v) => v
          case Left(e)  => throw RuntimeException(s"Embed decode error: $e")
      }

  val live: ZLayer[EmbedConfig, Nothing, EmbedClient] =
    ZLayer.fromFunction(new Live(_))
