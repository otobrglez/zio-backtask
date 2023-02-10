package zio.backtask.examples

import zio.{IO, Runtime, ZIO, ZIOAppDefault}
import zio.stream.ZStream.fromIterable
import zio.Console.printLine
import zio.logging.backend.SLF4J
import zio.backtask.{Backtask, JobID, Redis, RedisClient}

import scala.annotation.targetName
import scala.concurrent.duration.*

type TaskGraph[Env] = List[Backtask[Env]]
object TaskGraph { def apply[T <: Backtask[Env], Env](task: T): TaskGraph[Env] = List(task); }
given backtaskConversion[Env]: Conversion[Backtask[Env], TaskGraph[Env]] = task => TaskGraph(task)

object Extensions:
  extension [T <: Backtask[Env], Env](task: T)
    def ++(otherTask: Backtask[Env]): TaskGraph[Env] = List(task, otherTask)
    def ~>(otherTask: Backtask[Env]): Backtask[Env]  = task.withAfterTasks(otherTask)

  extension [T >: Backtask[Env], Env](tasks: TaskGraph[Env])
    def ++(otherTask: Backtask[Env]): TaskGraph[Env] = tasks ++ List(otherTask)
    def performAsync: ZIO[Redis, Throwable, Unit]    = fromIterable(tasks).mapZIO(_.performAsync).runDrain

private[this] object UserTasks:
  case class Say(input: String) extends Backtask[Any] { def run = printLine(input); }

private[this] object UserGraphs:
  import Extensions.*; import UserTasks.*

  val g1: TaskGraph[Any] = Say("A") ++ Say("B").withDelay(10.seconds) ++ Say("C") ++ Say("D")

  val g2: TaskGraph[Any] = (
    Say("A") ~> Say("B") ~> Say("C").withDelay(1.hour)
  ) ++ Say("X") ++ Say("Z") ~> Say("Y")

  val g3: TaskGraph[Any] =
    Say("send email") ~> Say("send another email") ++
      Say("scrape something").withDelay(1.day) ~> Say("send another email")
      ~> Say("have fun").repeatN(2)

object GraphApp extends ZIOAppDefault:
  import Extensions.*; import UserGraphs.*
  override val bootstrap = Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  def program =
    for
      _ <- g1.performAsync
      _ <- g2.performAsync
      _ <- g3.performAsync
    yield ()

  def run = program.provideLayer(RedisClient.live)
