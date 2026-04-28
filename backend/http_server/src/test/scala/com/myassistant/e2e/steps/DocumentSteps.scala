package com.myassistant.e2e.steps

import io.cucumber.scala.{EN, ScalaDsl}
import org.scalatest.matchers.should.Matchers

class DocumentSteps extends ScalaDsl with EN with Matchers:

  import SharedHttpContext.*

  private var lastPersonId: String   = ""
  private var lastDocumentId: String = ""

  Given("a person exists for document tests") {
    doPost("/api/v1/persons", """{"fullName":"Document Test Person","gender":"Male"}""")
    lastStatus shouldBe 201
    lastPersonId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract person id")
    )
  }

  When("I POST a document for the person with content {string}") { (content: String) =>
    val stId = sourceTypeIdByName.getOrElse("user_input", fail("user_input source type not found"))
    val body =
      s"""{"personId":"$lastPersonId","contentText":"$content","sourceTypeId":"$stId","embedding":$zeroEmbedding1536,"files":[],"supersedesIds":[]}"""
    doPost("/api/v1/documents", body)
    lastDocumentId = extractJsonStringField(lastBody, "id").getOrElse("")
  }

  When("I GET the created document by id") {
    doGet(s"/api/v1/documents/$lastDocumentId")
  }

  When("I GET documents for the person") {
    doGet(s"/api/v1/documents?personId=$lastPersonId")
  }

  When("I POST a document superseding the created document with content {string}") { (content: String) =>
    val supersededId = lastDocumentId
    val stId = sourceTypeIdByName.getOrElse("user_input", fail("user_input source type not found"))
    val body =
      s"""{"personId":"$lastPersonId","contentText":"$content","sourceTypeId":"$stId","embedding":$zeroEmbedding1536,"files":[],"supersedesIds":["$supersededId"]}"""
    doPost("/api/v1/documents", body)
  }

  When("I search documents by embedding") {
    doPost("/api/v1/documents/search", s"""{"embedding":$zeroEmbedding1536}""")
  }

  When("I GET documents with source type filter {string}") { (sourceTypeName: String) =>
    val stId = sourceTypeIdByName.getOrElse(sourceTypeName, fail(s"source type '$sourceTypeName' not found"))
    doGet(s"/api/v1/documents?sourceTypeId=$stId")
  }

  When("I POST a document with non-existent person for constraint test") {
    val stId = sourceTypeIdByName.getOrElse("user_input", fail("user_input source type not found"))
    val body =
      s"""{"personId":"00000000-0000-0000-0000-000000000001","contentText":"FK violation document","sourceTypeId":"$stId","embedding":$zeroEmbedding1536,"files":[],"supersedesIds":[]}"""
    doPost("/api/v1/documents", body)
  }

  private var lastHouseholdId: String = ""

  Given("a household exists for document tests") {
    doPost("/api/v1/households", """{"name":"Document Test Household"}""")
    lastStatus shouldBe 201
    lastHouseholdId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract household id")
    )
  }

  When("I POST a document for the household with content {string}") { (content: String) =>
    val stId = sourceTypeIdByName.getOrElse("user_input", fail("user_input source type not found"))
    val body =
      s"""{"householdId":"$lastHouseholdId","contentText":"$content","sourceTypeId":"$stId","embedding":$zeroEmbedding1536,"files":[],"supersedesIds":[]}"""
    doPost("/api/v1/documents", body)
    lastDocumentId = extractJsonStringField(lastBody, "id").getOrElse("")
  }

  When("I GET documents for the household") {
    doGet(s"/api/v1/documents?householdId=$lastHouseholdId")
  }

  When("I GET documents for the household filtered by source type") {
    val stId = sourceTypeIdByName.getOrElse("user_input", fail("user_input source type not found"))
    doGet(s"/api/v1/documents?householdId=$lastHouseholdId&sourceTypeId=$stId")
  }

  When("I POST a document without any owner") {
    val stId = sourceTypeIdByName.getOrElse("user_input", fail("user_input source type not found"))
    doPost("/api/v1/documents",
      s"""{"contentText":"Orphan document","sourceTypeId":"$stId","embedding":$zeroEmbedding1536,"files":[],"supersedesIds":[]}""")
  }
