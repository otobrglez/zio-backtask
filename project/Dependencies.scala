import sbt._

object Dependencies {
  type Version = String
  type Modules = Seq[ModuleID]

  object Versions {
    val zio: Version        = "2.0.6"
    val zioLogging: Version = "2.1.8"
  }

  lazy val logging: Modules = Seq(
    "ch.qos.logback" % "logback-classic" % "1.4.5"
  ) ++ Seq(
    "dev.zio" %% "zio-logging",
    "dev.zio" %% "zio-logging-slf4j"
  ).map(_ % Versions.zioLogging)

  lazy val zio: Modules = Seq(
    "dev.zio" %% "zio",
    "dev.zio" %% "zio-streams",
    "dev.zio" %% "zio-macros"
  ).map(_ % Versions.zio) ++ Seq(
    "dev.zio" %% "zio-cli" % "0.2.8"
  ) ++ Seq(
    "dev.zio" %% "zio-test",
    "dev.zio" %% "zio-test-junit",
    "dev.zio" %% "zio-test-magnolia",
    "dev.zio" %% "zio-test-sbt"
  ).map(_ % Versions.zio % "it,test")

  lazy val circe: Modules = Seq(
    "io.circe" %% "circe-core",
    "io.circe" %% "circe-generic",
    "io.circe" %% "circe-parser"
  ).map(_ % "0.15.0-M1")

  lazy val jsoniter: Modules = Seq(
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.20.7"
  )

  lazy val redis: Modules = Seq(
    "io.lettuce" % "lettuce-core" % "6.2.2.RELEASE"
  )

  lazy val testcontainers: Modules = Seq(
    "org.testcontainers" % "testcontainers" % "1.17.6" % "it,test"
  )

  lazy val projectResolvers: Seq[MavenRepository] = Seq(
    "Sonatype releases" at "https://oss.sonatype.org/content/repositories/releases",
    "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Sonatype staging" at "https://oss.sonatype.org/content/repositories/staging",
    "Java.net Maven2 Repository" at "https://download.java.net/maven/2/"
  )
}
