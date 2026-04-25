package com.myassistant.e2e.steps

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

/** Shared HTTP state and client used by all Cucumber step definition classes.
 *
 *  Cucumber instantiates each step-definition class separately per scenario;
 *  using a singleton object here keeps the shared HTTP state consistent across
 *  all step files within the same scenario execution.
 */
object SharedHttpContext:

  val baseUrl: String   = sys.env.getOrElse("TEST_BASE_URL", "http://localhost:8080")
  val authToken: String = sys.env.getOrElse("TEST_AUTH_TOKEN", "test-token")
  val client: HttpClient = HttpClient.newHttpClient()

  /** Mutable scenario state — reset before each scenario via Cucumber hooks if needed. */
  var lastStatus: Int    = 0
  var lastBody: String   = ""
  var lastCreatedId: String = ""

  /** Naive JSON field extractor — finds the first occurrence of "key":"value". */
  def extractJsonStringField(json: String, key: String): Option[String] =
    val pattern = s""""$key":"([^"]+)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1))

  /** Extract a field from the first element of a JSON "items" array. */
  def extractFromItems(json: String, key: String): Option[String] =
    val itemsPattern = """"items":\s*\[([^\]]+)""".r
    itemsPattern.findFirstMatchIn(json).flatMap(m =>
      val firstItem = m.group(1)
      val fieldPattern = s""""$key":"([^"]+)"""".r
      fieldPattern.findFirstMatchIn(firstItem).map(_.group(1))
    )

  /** Perform a GET request and update shared state. */
  def doGet(path: String): Unit =
    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Authorization", s"Bearer $authToken")
      .GET()
      .build()
    val resp = client.send(req, BodyHandlers.ofString())
    lastStatus = resp.statusCode()
    lastBody   = resp.body()

  /** Perform a POST request with JSON body and update shared state. */
  def doPost(path: String, body: String): Unit =
    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Content-Type", "application/json")
      .header("Authorization", s"Bearer $authToken")
      .POST(BodyPublishers.ofString(body))
      .build()
    val resp = client.send(req, BodyHandlers.ofString())
    lastStatus = resp.statusCode()
    lastBody   = resp.body()
    extractJsonStringField(lastBody, "id").foreach(id => lastCreatedId = id)
