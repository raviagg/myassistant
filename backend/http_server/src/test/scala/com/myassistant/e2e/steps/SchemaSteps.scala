package com.myassistant.e2e.steps

import io.cucumber.scala.{EN, ScalaDsl}
import org.scalatest.matchers.should.Matchers

class SchemaSteps extends ScalaDsl with EN with Matchers:

  import SharedHttpContext.*

  When("I GET the first schema by id") {
    val schemaId = extractJsonStringField(lastBody, "id")
      .orElse(extractFromItems(lastBody, "id"))
      .getOrElse(fail("Could not extract schema id from previous response"))
    doGet(s"/api/v1/schemas/$schemaId")
  }

  When("I deactivate the proposed schema for {string} {string}") { (domain: String, entityType: String) =>
    val schemaId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract schema id from previous response")
    )
    doDelete(s"/api/v1/schemas/$domain/$entityType/active?id=$schemaId")
  }
