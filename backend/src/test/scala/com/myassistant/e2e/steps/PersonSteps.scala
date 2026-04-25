package com.myassistant.e2e.steps

import io.cucumber.scala.{EN, ScalaDsl}
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

/** Cucumber step definitions for person management E2E scenarios.
 *
 *  Makes real HTTP calls against a running server.  The base URL and auth token
 *  are read from TEST_BASE_URL and TEST_AUTH_TOKEN environment variables.
 *  All state is stored in SharedHttpContext so FactSteps can read it too.
 */
class PersonSteps extends ScalaDsl with EN with Matchers:

  import SharedHttpContext.*

  // ── Background ─────────────────────────────────────────────────────────────

  Given("the server is running and I am authenticated") {
    // Verify the server is up by hitting /health
    doGet("/health")
    lastStatus shouldBe 200
  }

  // ── POST ────────────────────────────────────────────────────────────────────

  When("I POST to {string} with body:") { (path: String, body: String) =>
    doPost(path, body)
  }

  // ── GET ─────────────────────────────────────────────────────────────────────

  When("I GET {string}") { (path: String) =>
    doGet(path)
  }

  When("I GET the created person by id") {
    doGet(s"/api/v1/persons/$lastCreatedId")
  }

  // ── PATCH ────────────────────────────────────────────────────────────────────

  When("I PATCH the created person with body:") { (body: String) =>
    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/api/v1/persons/$lastCreatedId"))
      .header("Content-Type", "application/json")
      .header("Authorization", s"Bearer $authToken")
      .method("PATCH", BodyPublishers.ofString(body))
      .build()
    val resp = client.send(req, BodyHandlers.ofString())
    lastStatus = resp.statusCode()
    lastBody   = resp.body()
  }

  // ── DELETE ────────────────────────────────────────────────────────────────────

  When("I DELETE the created person by id") {
    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/api/v1/persons/$lastCreatedId"))
      .header("Authorization", s"Bearer $authToken")
      .DELETE()
      .build()
    val resp = client.send(req, BodyHandlers.ofString())
    lastStatus = resp.statusCode()
    lastBody   = resp.body()
  }

  // ── Shared assertions (used by both PersonFeature and FactFeature) ───────────

  Then("the response status is {int}") { (expected: Int) =>
    lastStatus shouldBe expected
  }

  Then("the response body contains {string}") { (expected: String) =>
    lastBody should include(expected)
  }
