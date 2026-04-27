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
    val body =
      s"""{"personId":"$lastPersonId","contentText":"$content","sourceType":"user_input","files":[],"supersedesIds":[]}"""
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
    val body =
      s"""{"personId":"$lastPersonId","contentText":"$content","sourceType":"user_input","files":[],"supersedesIds":["$supersededId"]}"""
    doPost("/api/v1/documents", body)
  }
