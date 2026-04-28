package com.myassistant.e2e.steps

import io.cucumber.scala.{EN, ScalaDsl}
import org.scalatest.matchers.should.Matchers

class PersonSteps extends ScalaDsl with EN with Matchers:

  import SharedHttpContext.*

  Given("the server is running and I am authenticated") {
    doGet("/health")
    lastStatus shouldBe 200
  }

  When("I POST to {string} with body:") { (path: String, body: String) =>
    doPost(path, body)
  }

  When("I GET {string}") { (path: String) =>
    doGet(path)
  }

  When("I GET the created person by id") {
    doGet(s"/api/v1/persons/$lastCreatedId")
  }

  When("I PATCH the created person with body:") { (body: String) =>
    doPatch(s"/api/v1/persons/$lastCreatedId", body)
  }

  When("I DELETE the created person by id") {
    doDelete(s"/api/v1/persons/$lastCreatedId")
  }

  Then("the response status is {int}") { (expected: Int) =>
    lastStatus shouldBe expected
  }

  Then("the response body contains {string}") { (expected: String) =>
    lastBody should include(expected)
  }

  When("I DELETE {string}") { (path: String) =>
    doDelete(path)
  }

  When("I PATCH {string} with body:") { (path: String, body: String) =>
    doPatch(path, body)
  }

  private var constrainedPersonId: String = ""

  Given("a person with a relationship exists for delete constraint tests") {
    doPost("/api/v1/persons", """{"fullName":"Constrained Person","gender":"Male"}""")
    lastStatus shouldBe 201
    constrainedPersonId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract constrained person id")
    )
    doPost("/api/v1/persons", """{"fullName":"Related Person","gender":"Female"}""")
    lastStatus shouldBe 201
    val relatedPersonId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract related person id")
    )
    doPost("/api/v1/relationships",
      s"""{"fromPersonId":"$constrainedPersonId","toPersonId":"$relatedPersonId","relationType":"father"}""")
    lastStatus shouldBe 201
  }

  When("I DELETE the constrained person") {
    doDelete(s"/api/v1/persons/$constrainedPersonId")
  }

  private var filterPersonId: String    = ""
  private var filterHouseholdId: String = ""

  Given("a person and household exist for person filter tests") {
    doPost("/api/v1/persons", """{"fullName":"Filter Test Person","gender":"Male"}""")
    lastStatus shouldBe 201
    filterPersonId = extractJsonStringField(lastBody, "id").getOrElse(fail("Could not extract person id"))
    doPost("/api/v1/households", """{"name":"Filter Test Household"}""")
    lastStatus shouldBe 201
    filterHouseholdId = extractJsonStringField(lastBody, "id").getOrElse(fail("Could not extract household id"))
  }

  When("I add the person to the household for person filter") {
    doPut(s"/api/v1/households/$filterHouseholdId/members/$filterPersonId")
  }

  When("I GET persons in the household") {
    doGet(s"/api/v1/persons?householdId=$filterHouseholdId")
  }

  Then("the person filter list contains the created person") {
    lastBody should include(filterPersonId)
  }

  private var docConstrainedPersonId: String = ""

  Given("a person with a document exists for delete constraint tests") {
    doPost("/api/v1/persons", """{"fullName":"Doc Constrained Person","gender":"Male"}""")
    lastStatus shouldBe 201
    docConstrainedPersonId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract doc constrained person id")
    )
    val stId = sourceTypeIdByName.getOrElse("user_input", fail("user_input source type not found"))
    doPost("/api/v1/documents",
      s"""{"personId":"$docConstrainedPersonId","contentText":"Blocking document","sourceTypeId":"$stId","embedding":$searchEmbedding1536,"files":[],"supersedesIds":[]}""")
    lastStatus shouldBe 201
  }

  When("I DELETE the person with document dependency") {
    doDelete(s"/api/v1/persons/$docConstrainedPersonId")
  }

  private var phConstrainedPersonId: String = ""

  Given("a person with household membership exists for delete constraint tests") {
    doPost("/api/v1/persons", """{"fullName":"PH Constrained Person","gender":"Female"}""")
    lastStatus shouldBe 201
    phConstrainedPersonId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract ph constrained person id")
    )
    doPost("/api/v1/households", """{"name":"PH Constraint Household"}""")
    lastStatus shouldBe 201
    val householdId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract household id")
    )
    doPut(s"/api/v1/households/$householdId/members/$phConstrainedPersonId")
    lastStatus shouldBe 204
  }

  When("I DELETE the person with household dependency") {
    doDelete(s"/api/v1/persons/$phConstrainedPersonId")
  }
