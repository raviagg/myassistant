package com.myassistant.e2e.steps

import com.myassistant.e2e.CucumberServer
import io.cucumber.scala.{EN, ScalaDsl}

class ServerHooks extends ScalaDsl with EN:

  BeforeAll {
    val _ = CucumberServer
  }
