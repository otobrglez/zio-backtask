import Dependencies._
import sbt.librarymanagement.Configurations.IntegrationTest

ThisBuild / scalaVersion := "3.2.2"
ThisBuild / version      := "0.0.1"

// Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val root =
  project
    .in(file("."))
    .aggregate(library, examples)
    .settings(name := "backtask")
    .settings(publish / skip := true)

lazy val library =
  project
    .in(file("modules/library"))
    .settings(
      name           := "zio-backtask",
      libraryDependencies ++= { zio ++ logging ++ circe ++ redis ++ testcontainers },
      testFrameworks := List(new TestFramework("zio.test.sbt.ZTestFramework"))
    )
    .configs(IntegrationTest)
    .settings(Defaults.itSettings)
    .settings(publish / skip := true)

lazy val examples =
  project
    .in(file("modules/examples"))
    .dependsOn(library)
    .settings(
      name := "examples"
    )
    .settings(publish / skip := true)

scalacOptions ++= Seq(
  "-encoding",
  "UTF-8",
  "-feature",
  "-unchecked",
  "-deprecation",
  "-Yretain-trees",
  "-language:higherKinds"
)
