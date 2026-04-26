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
