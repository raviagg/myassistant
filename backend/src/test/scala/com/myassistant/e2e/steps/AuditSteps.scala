package com.myassistant.e2e.steps

import io.cucumber.scala.{EN, ScalaDsl}
import org.scalatest.matchers.should.Matchers

class AuditSteps extends ScalaDsl with EN with Matchers:

  import SharedHttpContext.*

  When("I POST an audit interaction for the created person") {
    val body =
      s"""{"personId":"$lastCreatedId","message":"Agent processed request","toolCalls":[],"status":"partial"}"""
    doPost("/api/v1/audit/interactions", body)
  }
