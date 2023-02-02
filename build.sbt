import Dependencies._

ThisBuild / scalaVersion := "3.2.2"
ThisBuild / version      := "0.0.1"

// Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val root = 
  project
    .in(file("."))
    .aggregate(library, examples)
    .settings(publish / skip := true)

lazy val library =
  project
    .in(file("modules/library"))
    .settings(
      name := "zio-backtask",
      libraryDependencies ++= { zio ++ logging ++ circe ++ redis },
    )
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
  "-encoding", "UTF-8", "-feature", "-unchecked",
  "-deprecation", 
  "-Yretain-trees", 
  "-language:higherKinds",
)
