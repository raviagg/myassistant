package com.myassistant.e2e.steps

import io.cucumber.scala.{EN, ScalaDsl}
import org.scalatest.matchers.should.Matchers

class HouseholdSteps extends ScalaDsl with EN with Matchers:

  import SharedHttpContext.*

  private var lastHouseholdId: String = ""
  private var memberPersonId: String  = ""

  When("I GET the created household by id") {
    doGet(s"/api/v1/households/$lastCreatedId")
  }

  When("I PATCH the created household with body:") { (body: String) =>
    doPatch(s"/api/v1/households/$lastCreatedId", body)
  }

  When("I DELETE the created household by id") {
    doDelete(s"/api/v1/households/$lastCreatedId")
  }

  Given("a person and household exist for membership tests") {
    doPost("/api/v1/persons", """{"fullName":"Membership Test Person","gender":"Female"}""")
    lastStatus shouldBe 201
    memberPersonId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract person id")
    )
    doPost("/api/v1/households", """{"name":"Membership Test Household"}""")
    lastStatus shouldBe 201
    lastHouseholdId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract household id")
    )
  }

  When("I add the person to the household") {
    doPut(s"/api/v1/households/$lastHouseholdId/members/$memberPersonId")
  }

  When("I remove the person from the household") {
    doDelete(s"/api/v1/households/$lastHouseholdId/members/$memberPersonId")
  }

  When("I GET members of the household") {
    doGet(s"/api/v1/households/$lastHouseholdId/members")
  }

  When("I GET households for the person") {
    doGet(s"/api/v1/persons/$memberPersonId/households")
  }

  Then("the household member list contains the person id") {
    lastBody should include(memberPersonId)
  }

  Then("the household list contains the household id") {
    lastBody should include(lastHouseholdId)
  }

  When("I add a non-existent person to the created household") {
    doPut(s"/api/v1/households/$lastCreatedId/members/00000000-0000-0000-0000-000000000000")
  }

  private var docConstrainedHouseholdId: String = ""

  Given("a household with a document exists for delete constraint tests") {
    doPost("/api/v1/households", """{"name":"Doc Constrained Household"}""")
    lastStatus shouldBe 201
    docConstrainedHouseholdId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract doc constrained household id")
    )
    val stId = sourceTypeIdByName.getOrElse("user_input", fail("user_input source type not found"))
    doPost("/api/v1/documents",
      s"""{"householdId":"$docConstrainedHouseholdId","contentText":"Blocking household doc","sourceTypeId":"$stId","embedding":$searchEmbedding1536,"files":[],"supersedesIds":[]}""")
    lastStatus shouldBe 201
  }

  When("I DELETE the household with document dependency") {
    doDelete(s"/api/v1/households/$docConstrainedHouseholdId")
  }
