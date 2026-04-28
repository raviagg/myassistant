package com.myassistant.e2e.steps

import io.cucumber.scala.{EN, ScalaDsl}
import org.scalatest.matchers.should.Matchers

/** Cucumber step definitions for fact management E2E scenarios.
 *
 *  Makes real HTTP calls against a running server.  Prerequisite data (person,
 *  document, schema) is created via the API in the Given step.
 *  Shared response state lives in SharedHttpContext so PersonSteps assertions
 *  (`the response status is {int}`, `the response body contains {string}`) work
 *  across both step-definition classes.
 */
class FactSteps extends ScalaDsl with EN with Matchers:

  import SharedHttpContext.*

  // ── Per-scenario state ────────────────────────────────────────────────────
  // lastStatus / lastBody / lastCreatedId are in SharedHttpContext.
  // FactSteps adds fact-specific state for chaining operations.
  private var lastDocumentId: String       = ""
  private var lastSchemaId: String         = ""
  private var lastEntityInstanceId: String = ""
  private var lastFactId: String           = ""

  // ── Given: create prerequisite person, schema, and document ─────────────

  Given("a person and document exist in the system") {
    // 1. Create a person
    doPost("/api/v1/persons", """{"fullName":"Fact E2E Person","gender":"Male"}""")
    lastStatus shouldBe 201
    val personId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract person id from create person response")
    )

    // 2. Fetch first schema id (seeded by V5 migration)
    doGet("/api/v1/schemas")
    lastSchemaId = extractJsonStringField(lastBody, "id")
      .orElse(extractFromItems(lastBody, "id"))
      .getOrElse(fail("Could not extract schema id from schemas response"))

    // 3. Create a document owned by the person
    val docBody =
      s"""{"personId":"$personId","contentText":"Test document for E2E fact tests","sourceType":"user_input","files":[],"supersedesIds":[]}"""
    doPost("/api/v1/documents", docBody)
    lastStatus shouldBe 201
    lastDocumentId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract document id from create document response")
    )
  }

  // ── Fact creation ─────────────────────────────────────────────────────────

  When("I POST a fact for the existing document with operation type {string}") { (opType: String) =>
    val body =
      s"""{"documentId":"$lastDocumentId","schemaId":"$lastSchemaId","operationType":"$opType","fields":{"title":"Test task","status":"open"}}"""
    doPost("/api/v1/facts", body)
    extractJsonStringField(lastBody, "entityInstanceId").foreach(id => lastEntityInstanceId = id)
    extractJsonStringField(lastBody, "id").foreach(id => lastFactId = id)
  }

  When("I POST an update fact for the same entity instance with status {string}") { (status: String) =>
    val body =
      s"""{"documentId":"$lastDocumentId","schemaId":"$lastSchemaId","entityInstanceId":"$lastEntityInstanceId","operationType":"update","fields":{"status":"$status"}}"""
    doPost("/api/v1/facts", body)
  }

  When("I POST a fact with non-existent document ID") {
    val body =
      s"""{"documentId":"00000000-0000-0000-0000-000000000000","schemaId":"$lastSchemaId","operationType":"create","fields":{"title":"Test"}}"""
    doPost("/api/v1/facts", body)
  }

  // ── Fact retrieval ────────────────────────────────────────────────────────

  When("I GET the fact history for the entity instance") {
    doGet(s"/api/v1/facts/$lastEntityInstanceId/history")
  }

  When("I GET facts for the document") {
    doPost("/api/v1/facts/search", s"""{"query":"$lastDocumentId"}""")
  }

  When("I GET the current fact for the entity instance") {
    doGet(s"/api/v1/facts/$lastFactId/current")
  }

  When("I GET facts by schema") {
    doGet(s"/api/v1/facts/current?schemaId=$lastSchemaId")
  }

  // ── Fact-specific assertions ──────────────────────────────────────────────

  Then("the fact response status is {int}") { (expected: Int) =>
    lastStatus shouldBe expected
  }

  Then("the fact response body contains {string}") { (expected: String) =>
    lastBody should include(expected)
  }

  Then("the fact history contains at least {int} entries") { (min: Int) =>
    // Count occurrences of "operationType" as a proxy for entry count in the items array
    val count = "operationType".r.findAllIn(lastBody).length
    count should be >= min
  }
