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
    val domainId = domainIdByName.getOrElse(domain, fail(s"Unknown domain: $domain"))
    val schemaId = extractJsonStringField(lastBody, "id").getOrElse(
      fail("Could not extract schema id from previous response")
    )
    doDelete(s"/api/v1/schemas/$domainId/$entityType/active")
  }

  When("I GET the current schema for domain {string} entity type {string}") {
    (domainName: String, entityType: String) =>
      val domainId = domainIdByName.getOrElse(domainName, fail(s"Unknown domain: $domainName"))
      doGet(s"/api/v1/schemas/current?domainId=$domainId&entityType=$entityType")
  }

  When("I GET schemas filtered by domain {string}") { (domainName: String) =>
    val domainId = domainIdByName.getOrElse(domainName, fail(s"Unknown domain: $domainName"))
    doGet(s"/api/v1/schemas?domainId=$domainId")
  }

  When("I propose a schema for domain {string} entity type {string}") {
    (domainName: String, entityType: String) =>
      val domainId = domainIdByName.getOrElse(domainName, fail(s"Unknown domain: $domainName"))
      val body =
        s"""{"domainId":"$domainId","entityType":"$entityType","description":"A schema for $entityType","fieldDefinitions":[{"name":"value","type":"text","mandatory":true}]}"""
      doPost("/api/v1/schemas", body)
  }

  When("I post a new version for domain {string} entity type {string} with body:") {
    (domainName: String, entityType: String, body: String) =>
      val domainId = domainIdByName.getOrElse(domainName, fail(s"Unknown domain: $domainName"))
      doPost(s"/api/v1/schemas/$domainId/$entityType/versions", body)
  }

  When("I DELETE the active schema for domain {string} entity type {string} with schema id {string}") {
    (domainName: String, entityType: String, schemaId: String) =>
      val domainId = domainIdByName.getOrElse(domainName, fail(s"Unknown domain: $domainName"))
      doDelete(s"/api/v1/schemas/$domainId/$entityType/active")
  }
