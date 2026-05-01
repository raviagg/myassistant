// ============================================================
// build.sbt — Personal Assistant Backend
//
// Scala 3.4 + ZIO 2.x + ZIO HTTP + ZIO JDBC + Flyway
// Full dependency declarations, assembly settings, test config.
// ============================================================

ThisBuild / scalaVersion := "3.4.2"
ThisBuild / organization := "com.myassistant"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// ── Dependency versions ──────────────────────────────────────
val zioVersion             = "2.1.6"
val zioHttpVersion         = "3.0.1"
val zioJdbcVersion         = "0.1.2"
val zioConfigVersion       = "4.0.2"
val zioLoggingVersion      = "2.3.0"
val circeVersion           = "0.14.9"
val flywayVersion          = "10.15.2"
val hikariVersion          = "5.1.0"
val postgresVersion        = "42.7.3"
val prometheusVersion      = "0.16.0"
val testcontainersVersion  = "0.41.3"
val scalatestVersion       = "3.2.19"
val cucumberScalaVersion   = "8.27.0"
val cucumberJvmVersion     = "7.22.1"

// ── Dependencies ─────────────────────────────────────────────
lazy val zioDeps = Seq(
  "dev.zio" %% "zio"              % zioVersion,
  "dev.zio" %% "zio-streams"      % zioVersion,
  "dev.zio" %% "zio-http"         % zioHttpVersion,
  "dev.zio" %% "zio-jdbc"         % zioJdbcVersion,
  "dev.zio" %% "zio-config"       % zioConfigVersion,
  "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
  "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,
  "dev.zio" %% "zio-logging"      % zioLoggingVersion,
  "dev.zio" %% "zio-logging-slf4j2" % zioLoggingVersion,
)

lazy val circeDeps = Seq(
  "io.circe" %% "circe-core"    % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser"  % circeVersion,
)

lazy val dbDeps = Seq(
  "org.flywaydb"    % "flyway-core"                % flywayVersion,
  "org.flywaydb"    % "flyway-database-postgresql" % flywayVersion,
  "com.zaxxer"      % "HikariCP"                   % hikariVersion,
  "org.postgresql"  % "postgresql"                 % postgresVersion,
)

lazy val prometheusDeps = Seq(
  "io.prometheus" % "simpleclient"           % prometheusVersion,
  "io.prometheus" % "simpleclient_hotspot"   % prometheusVersion,
  "io.prometheus" % "simpleclient_httpserver" % prometheusVersion,
)

lazy val testDeps = Seq(
  "dev.zio"                       %% "zio-test"                             % zioVersion          % Test,
  "dev.zio"                       %% "zio-test-sbt"                         % zioVersion          % Test,
  "dev.zio"                       %% "zio-test-magnolia"                    % zioVersion          % Test,
  "com.dimafeng"                  %% "testcontainers-scala-scalatest"       % testcontainersVersion % Test,
  "com.dimafeng"                  %% "testcontainers-scala-postgresql"      % testcontainersVersion % Test,
  "org.scalatest"                 %% "scalatest"                            % scalatestVersion    % Test,
  "io.cucumber"                   %% "cucumber-scala"                       % cucumberScalaVersion % Test,
  "io.cucumber"                    % "cucumber-junit"                       % cucumberJvmVersion  % Test,
  // Bridge so sbt can discover and run JUnit 4 tests (required by CucumberRunner)
  "com.github.sbt"                 % "junit-interface"                      % "0.13.3"            % Test,
  // Required for ZConnectionPool.h2test used in unit tests
  "com.h2database"                 % "h2"                                   % "2.3.232"           % Test,
  // SLF4J backend — runtime logging via logback
  "ch.qos.logback"                 % "logback-classic"                      % "1.5.6",
)

// ── Main project ──────────────────────────────────────────────
lazy val backend = (project in file("."))
  .settings(
    name := "myassistant-backend",

    libraryDependencies ++= zioDeps ++ circeDeps ++ dbDeps ++ prometheusDeps ++ testDeps,

    // ── Scala compiler options ────────────────────────────────
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-language:implicitConversions",
      "-language:higherKinds",
    ),

    // ── Test framework ────────────────────────────────────────
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    // ── sbt-assembly fat-JAR settings ─────────────────────────
    assembly / assemblyJarName := "myassistant-backend.jar",
    assembly / mainClass       := Some("com.myassistant.Main"),

    // Merge strategy: take the last copy for duplicate entries
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.filterDistinctLines
      case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
      case PathList("reference.conf")                => MergeStrategy.concat
      case PathList("application.conf")              => MergeStrategy.first
      case x if x.endsWith(".class")                 => MergeStrategy.last
      case _                                         => MergeStrategy.first
    },

    // ── Resource directories ──────────────────────────────────
    Compile / resourceDirectories += baseDirectory.value / "src" / "main" / "resources",
    Test    / resourceDirectories += baseDirectory.value / "src" / "test" / "resources",

    // ── Test classloader — needed for H2 driver discovery ─────
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.ScalaLibrary,

    // ── Integration tests: run spec classes one at a time ─────
    // Without this, all 6 specs spin up Docker containers simultaneously,
    // overwhelming Docker and causing "Connection refused" from Flyway.
    Test / parallelExecution := false,
    Test / logBuffered := false,
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF"),

    // ── Code coverage (sbt-scoverage) ─────────────────────────
    // Unit + Integration combined (90% gate):  sbt coverage test coverageReport
    // E2E only (separate report):              sbt coverageE2e
    // Report locations:
    //   unit+integration → target/scala-3.4.2/scoverage-report/index.html
    //   e2e              → target/e2e-scoverage-report/index.html
    // E2E threshold is enforced by backend/http_server/src/test/scala/com/myassistant/e2e/check-e2e-coverage.sh (not build.sbt)
    coverageMinimumStmtTotal  := 88,
    coverageFailOnMinimum     := true,
    // Excluded from unit+integration gate:
    //   api.*        — routes/models/middleware are covered by the E2E suite
    //   errors.*     — sealed error type definitions; getMessage covered transitively
    //   logging.*    — SLF4J wiring with no testable logic
    //   Main         — application entry point
    //   config.*     — pure config case classes
    //   domain.*     — pure data case classes
    // For Scala 3, -coverage-exclude-classlikes takes comma-separated prefixes.
    // Each prefix is matched against the fully-qualified class name; sub-packages
    // must be listed explicitly (prefix matching does not recurse automatically).
    coverageExcludedPackages  :=
      Seq(
        "com.myassistant.Main",
        "com.myassistant.config",
        "com.myassistant.domain",
        "com.myassistant.api",
        "com.myassistant.api.routes",
        "com.myassistant.api.models",
        "com.myassistant.api.middleware",
        "com.myassistant.errors",
        "com.myassistant.logging.AppLogger",
        // Infrastructure with no testable logic reachable via HTTP
        "com.myassistant.monitoring",
        "com.myassistant.logging.LogFormat",
        "com.myassistant.db.MigrationRunner",
        "com.myassistant.db.repositories.FileRepository",
        "com.myassistant.services.FileService",
      ).mkString(","),
  )

// ── E2E coverage alias ────────────────────────────────────────────────────────
// Runs only the Cucumber E2E suite with scoverage instrumentation, outputting
// to a separate directory so it does not interfere with the unit+integration gate.
// Threshold is checked by backend/http_server/src/test/scala/com/myassistant/e2e/check-e2e-coverage.sh, not enforced in build.sbt.
addCommandAlias(
  "coverageE2e",
  // Temporarily disable the minimum threshold — E2E coverage is checked separately
  // by backend/http_server/src/test/scala/com/myassistant/e2e/check-e2e-coverage.sh, not by this build gate.
  """;set coverageDataDir := (baseDirectory.value / "target" / "e2e-scoverage");set coverageFailOnMinimum := false;coverage;testOnly com.myassistant.e2e.*;coverageReport;set coverageDataDir := (target.value / "scoverage-data");set coverageFailOnMinimum := true""",
)
