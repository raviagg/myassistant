package com.myassistant.e2e.steps

import io.cucumber.scala.{EN, ScalaDsl}
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class FactSteps extends ScalaDsl with EN with Matchers:

  import SharedHttpContext.*

  private var lastDocumentId: String       = ""
  private var lastSchemaId: String         = ""
  private var lastEntityInstanceId: String = ""
  private var lastFactId: String           = ""

  Given("a person and document exist in the system") {
    doPost("/api/v1/persons", """{"fullName":"Fact E2E Person","gender":"Male"}""")
    lastStatus shouldBe 201
    val personId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract person id from create person response")
    )

    val todoDomainId = domainIdByName.getOrElse("todo", fail("todo domain not found"))
    doGet(s"/api/v1/schemas?domainId=$todoDomainId")
    lastSchemaId = extractJsonStringField(lastBody, "id")
      .orElse(extractFromItems(lastBody, "id"))
      .getOrElse(fail("Could not extract schema id from schemas response"))

    val stId = sourceTypeIdByName.getOrElse("user_input", fail("user_input source type not found"))
    val docBody =
      s"""{"personId":"$personId","contentText":"Test document for E2E fact tests","sourceTypeId":"$stId","embedding":$searchEmbedding1536,"files":[],"supersedesIds":[]}"""
    doPost("/api/v1/documents", docBody)
    lastStatus shouldBe 201
    lastDocumentId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract document id from create document response")
    )
  }

  When("I POST a fact for the existing document with operation type {string}") { (opType: String) =>
    val entityId = if lastEntityInstanceId.nonEmpty then lastEntityInstanceId
                   else UUID.randomUUID().toString
    if lastEntityInstanceId.isEmpty then lastEntityInstanceId = entityId
    val body =
      s"""{"documentId":"$lastDocumentId","schemaId":"$lastSchemaId","entityInstanceId":"$entityId","operationType":"$opType","fields":{"title":"Test task","status":"open"},"embedding":$zeroEmbedding1536}"""
    doPost("/api/v1/facts", body)
    extractJsonStringField(lastBody, "entityInstanceId").foreach(id => lastEntityInstanceId = id)
    extractJsonStringField(lastBody, "id").foreach(id => lastFactId = id)
  }

  When("I POST an update fact for the same entity instance with status {string}") { (status: String) =>
    val body =
      s"""{"documentId":"$lastDocumentId","schemaId":"$lastSchemaId","entityInstanceId":"$lastEntityInstanceId","operationType":"update","fields":{"status":"$status"},"embedding":$zeroEmbedding1536}"""
    doPost("/api/v1/facts", body)
  }

  When("I POST a fact with non-existent document ID") {
    val entityId = UUID.randomUUID().toString
    val body =
      s"""{"documentId":"00000000-0000-0000-0000-000000000000","schemaId":"$lastSchemaId","entityInstanceId":"$entityId","operationType":"create","fields":{"title":"Test"},"embedding":$zeroEmbedding1536}"""
    doPost("/api/v1/facts", body)
  }

  When("I GET the fact history for the entity instance") {
    doGet(s"/api/v1/facts/$lastEntityInstanceId/history")
  }

  When("I GET facts for the document") {
    doGet(s"/api/v1/facts/$lastEntityInstanceId/history")
  }

  When("I GET the current fact for the entity instance") {
    doGet(s"/api/v1/facts/$lastEntityInstanceId/current")
  }

  When("I GET facts by domain") {
    val domainId = domainIdByName.getOrElse("todo", fail("todo domain not found"))
    doGet(s"/api/v1/facts/current?domainId=$domainId")
  }

  Then("the fact response status is {int}") { (expected: Int) =>
    lastStatus shouldBe expected
  }

  Then("the fact response body contains {string}") { (expected: String) =>
    lastBody should include(expected)
  }

  Then("the fact history contains at least {int} entries") { (min: Int) =>
    val count = "operationType".r.findAllIn(lastBody).length
    count should be >= min
  }
