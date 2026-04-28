package com.myassistant.e2e.steps

import io.cucumber.scala.{EN, ScalaDsl}
import org.scalatest.matchers.should.Matchers

class RelationshipSteps extends ScalaDsl with EN with Matchers:

  import SharedHttpContext.*

  private var personAId: String = ""
  private var personBId: String = ""
  private var personCId: String = ""

  Given("two persons exist for relationship tests") {
    doPost("/api/v1/persons", """{"fullName":"Relationship Person A","gender":"Male"}""")
    lastStatus shouldBe 201
    personAId = extractJsonStringField(lastBody, "id").getOrElse(fail("Could not extract person A id"))
    doPost("/api/v1/persons", """{"fullName":"Relationship Person B","gender":"Female"}""")
    lastStatus shouldBe 201
    personBId = extractJsonStringField(lastBody, "id").getOrElse(fail("Could not extract person B id"))
  }

  Given("three persons exist for kinship chain tests") {
    doPost("/api/v1/persons", """{"fullName":"Kinship Person A","gender":"Male"}""")
    lastStatus shouldBe 201
    personAId = extractJsonStringField(lastBody, "id").getOrElse(fail("Could not extract person A id"))
    doPost("/api/v1/persons", """{"fullName":"Kinship Person B","gender":"Female"}""")
    lastStatus shouldBe 201
    personBId = extractJsonStringField(lastBody, "id").getOrElse(fail("Could not extract person B id"))
    doPost("/api/v1/persons", """{"fullName":"Kinship Person C","gender":"Male"}""")
    lastStatus shouldBe 201
    personCId = extractJsonStringField(lastBody, "id").getOrElse(fail("Could not extract person C id"))
  }

  When("I create a {string} relationship from person A to person B") { (relType: String) =>
    val body = s"""{"fromPersonId":"$personAId","toPersonId":"$personBId","relationType":"$relType"}"""
    doPost("/api/v1/relationships", body)
  }

  When("I GET the relationship between person A and person B") {
    doGet(s"/api/v1/relationships/$personAId/$personBId")
  }

  When("I GET relationships for person A") {
    doGet(s"/api/v1/relationships?person_id=$personAId")
  }

  When("I PATCH the relationship between person A and person B with type {string}") { (relType: String) =>
    doPatch(s"/api/v1/relationships/$personAId/$personBId", s"""{"relationType":"$relType"}""")
  }

  When("I DELETE the relationship between person A and person B") {
    doDelete(s"/api/v1/relationships/$personAId/$personBId")
  }

  When("I GET kinship between person A and person B") {
    doGet(s"/api/v1/relationships/$personAId/$personBId/kinship")
  }

  When("I GET kinship between person B and person A") {
    doGet(s"/api/v1/relationships/$personBId/$personAId/kinship")
  }

  When("I GET kinship between person A and person C") {
    doGet(s"/api/v1/relationships/$personAId/$personCId/kinship")
  }

  When("I create a {string} relationship from person B to person C") { (relType: String) =>
    val body = s"""{"fromPersonId":"$personBId","toPersonId":"$personCId","relationType":"$relType"}"""
    doPost("/api/v1/relationships", body)
  }

  When("I create a self-relationship for person A") {
    val body = s"""{"fromPersonId":"$personAId","toPersonId":"$personAId","relationType":"father"}"""
    doPost("/api/v1/relationships", body)
  }

  When("I GET kinship from person A to themselves") {
    doGet(s"/api/v1/relationships/$personAId/$personAId/kinship")
  }
