package zio.backtask.examples

import zio.Console.printLine
import zio.backtask.*
import zio.backtask.TaskGraph.{backtaskConversion, *}
import zio.logging.backend.SLF4J
import zio.stream.ZStream.fromIterable
import zio.{backtask, IO, Runtime, ZIO, ZIOAppDefault}

import scala.annotation.targetName
import scala.concurrent.duration.*

private[this] object UserTasks:
  case class Say(input: String) extends Backtask[Any] { def run = printLine(input); }

private[this] object UserGraphs:
  import UserTasks.*

  val g1: TaskGraph[Any] = Say("A") ++ Say("B").withDelay(10.seconds) ++ Say("C") ++ Say("D")

  val g2: TaskGraph[Any] = (
    Say("A") ~> Say("B") ~> Say("C").withDelay(1.hour)
  ) ++ Say("X") ++ Say("Z") ~> Say("Y")

  val g3: TaskGraph[Any] =
    Say("send email") ~> Say("send another email") ++
      Say("scrape something").withDelay(1.day) ~> Say("send another email")
      ~> Say("have fun").repeatN(2)

  val g4 = (Say("Do this") ~> Say("Do that")).withDelay(10.seconds) ++ Say("I was here.")

object GraphApp extends ZIOAppDefault:
  import UserGraphs.*
  override val bootstrap = Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  def program =
    for
      _ <- g1.performAsync
      _ <- g2.performAsync
      _ <- g3.performAsync
      _ <- g4.performAsync
    yield ()

  def run = program.provideLayer(RedisClient.live)
