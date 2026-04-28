package com.myassistant.e2e.steps

import com.myassistant.e2e.CucumberServer
import io.cucumber.scala.{EN, ScalaDsl}

// JUnit 4 Cucumber runner does not invoke BeforeAll/AfterAll static hooks.
// Using Before instead — CucumberServer is a Scala object (singleton), so
// the first scenario pays the startup cost; all others are a no-op.
class ServerHooks extends ScalaDsl with EN:

  Before { _ =>
    val _ = CucumberServer
    waitForPort(CucumberServer.TestPort, maxRetries = 120, delayMs = 500)
  }

  private def waitForPort(port: Int, maxRetries: Int, delayMs: Long): Unit =
    var retries = 0
    var up      = false
    while retries < maxRetries && !up do
      try
        val socket = new java.net.Socket()
        socket.connect(new java.net.InetSocketAddress("localhost", port), 500)
        socket.close()
        up = true
        println(s"[ServerHooks] Server up on port $port after ${retries * delayMs}ms")
      catch
        case _: Exception =>
          retries += 1
          if retries % 5 == 0 then
            println(s"[ServerHooks] Waiting for server on port $port (attempt $retries)...")
          Thread.sleep(delayMs)
    if !up then
      throw new RuntimeException(s"Server did not start on port $port after ${retries * delayMs}ms")
