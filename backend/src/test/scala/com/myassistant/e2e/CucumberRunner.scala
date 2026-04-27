package com.myassistant.e2e

import io.cucumber.junit.{Cucumber, CucumberOptions}
import org.junit.runner.RunWith

@RunWith(classOf[Cucumber])
@CucumberOptions(
  features = Array("src/test/scala/com/myassistant/e2e"),
  glue     = Array("com.myassistant.e2e.steps"),
  plugin   = Array("pretty", "summary"),
)
class CucumberRunner
