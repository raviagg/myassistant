package com.myassistant.e2e.steps

import java.net.HttpURLConnection
import java.io.{DataOutputStream, InputStreamReader, BufferedReader, InputStream}
import java.nio.charset.StandardCharsets

/** Shared HTTP state used by all Cucumber step definition classes.
 *
 *  Uses java.net.HttpURLConnection (no NIO) to avoid Java 21 HttpClient
 *  lifecycle issues that produce ClosedChannelException under sbt's
 *  ClassLoaderLayeringStrategy.ScalaLibrary test classloader.
 */
object SharedHttpContext:

  lazy val baseUrl: String =
    sys.props.get("TEST_BASE_URL")
      .orElse(sys.env.get("TEST_BASE_URL"))
      .getOrElse("http://localhost:8080")

  val authToken: String = sys.env.getOrElse("TEST_AUTH_TOKEN", "test-token")

  var lastStatus: Int       = 0
  var lastBody: String      = ""
  var lastCreatedId: String = ""

  def extractJsonStringField(json: String, key: String): Option[String] =
    val pattern = s""""$key":"([^"]+)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1))

  def extractFromItems(json: String, key: String): Option[String] =
    val itemsPattern = """"items":\s*\[([^\]]+)""".r
    itemsPattern.findFirstMatchIn(json).flatMap(m =>
      val firstItem = m.group(1)
      val fieldPattern = s""""$key":"([^"]+)"""".r
      fieldPattern.findFirstMatchIn(firstItem).map(_.group(1))
    )

  private def readBody(conn: HttpURLConnection): String =
    val stream = if conn.getResponseCode() < 400 then conn.getInputStream()
                 else conn.getErrorStream()
    if stream == null then ""
    else
      val reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
      val sb     = new StringBuilder
      var line   = reader.readLine()
      while line != null do
        sb.append(line)
        line = reader.readLine()
      reader.close()
      sb.toString

  def doGet(path: String): Unit =
    val conn = java.net.URI.create(s"$baseUrl$path").toURL().openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("GET")
    conn.setRequestProperty("Authorization", s"Bearer $authToken")
    conn.connect()
    lastStatus = conn.getResponseCode()
    lastBody   = readBody(conn)
    conn.disconnect()

  def doPost(path: String, body: String): Unit =
    val conn = java.net.URI.create(s"$baseUrl$path").toURL().openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Authorization", s"Bearer $authToken")
    conn.setDoOutput(true)
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    conn.setRequestProperty("Content-Length", bytes.length.toString)
    val out = new DataOutputStream(conn.getOutputStream())
    out.write(bytes)
    out.flush()
    out.close()
    lastStatus = conn.getResponseCode()
    lastBody   = readBody(conn)
    conn.disconnect()
    extractJsonStringField(lastBody, "id").foreach(id => lastCreatedId = id)

  def doPatch(path: String, body: String): Unit =
    // HttpURLConnection does not support PATCH; use a fresh HttpClient per request.
    import java.net.http.{HttpClient, HttpRequest, HttpResponse}
    import java.net.http.HttpRequest.BodyPublishers
    import java.net.http.HttpResponse.BodyHandlers
    val client = HttpClient.newHttpClient()
    val req = HttpRequest.newBuilder()
      .uri(java.net.URI.create(s"$baseUrl$path"))
      .header("Content-Type", "application/json")
      .header("Authorization", s"Bearer $authToken")
      .method("PATCH", BodyPublishers.ofString(body))
      .build()
    val resp = client.send(req, BodyHandlers.ofString())
    lastStatus = resp.statusCode()
    lastBody   = resp.body()
    client.close()

  def doPut(path: String): Unit =
    val conn = java.net.URI.create(s"$baseUrl$path").toURL().openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("PUT")
    conn.setRequestProperty("Authorization", s"Bearer $authToken")
    conn.setRequestProperty("Content-Length", "0")
    conn.connect()
    lastStatus = conn.getResponseCode()
    lastBody   = readBody(conn)
    conn.disconnect()

  def doDelete(path: String): Unit =
    val conn = java.net.URI.create(s"$baseUrl$path").toURL().openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("DELETE")
    conn.setRequestProperty("Authorization", s"Bearer $authToken")
    conn.connect()
    lastStatus = conn.getResponseCode()
    lastBody   = readBody(conn)
    conn.disconnect()
