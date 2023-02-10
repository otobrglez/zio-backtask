package zio.backtask.examples

import zio.Console.printLine
import zio.ZIO.{fail, logDebug, logError, logInfo, when}
import zio.backtask.{Backtask, BacktaskScheduler, Redis, RedisClient, Worker}
import zio.logging.backend.SLF4J
import zio.{ZIOAppDefault, *}

import java.time.LocalDateTime

private[this] object tasks:
  case class add(a: Int, b: Int, yes: Option[String] = None) extends Backtask[Any]:
    override def queueName = "math"

    def run = printLine(s"Add: $a + $b = ${a + b}")

  case class taskThatFails(raw: String) extends Backtask[Any]:
    def run = for
      _ <- printLine("Gotta fail you")
      _ <- fail(new RuntimeException(s"💥 with ${raw}"))
    yield ()

  case class saySomething(raw: String) extends Backtask[Any]:
    def run = printLine(s"Said ${raw}")

  case class countFromTo(from: Int, to: Int, delay: Int) extends Backtask[Redis]:
    override def queueName = "counting"

    def run =
      for
        _ <- printLine(s"n: $from, queueName: $queueName")
        _ <-
          if (from + 1 <= to)
            countFromTo(from + 1, to, delay).performIn(delay.seconds)
          else ZIO.unit
      yield ()

object BacktaskApp extends ZIOAppDefault:
  import tasks.*
  override val bootstrap = Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  def clientProgram: ZIO[Redis, Throwable, Unit] =
    for
      _ <- logInfo("Booting.")
      _ <- Redis.flushdb() *> logInfo("Flushed db.")
      _ <- add(40, 2).performAsync
      _ <- add(20, 22).performAsync("math")
      _ <- saySomething("Hello world!").performAsync
      _ <- saySomething("Doing this in one hour").performAt(LocalDateTime.now.plusHours(1), "hello")
      _ <- saySomething("Foo 10").performIn(10.seconds, "hello")
      _ <- saySomething("Bar 20").performIn(20.seconds, "hello")
      _ <- saySomething("Baz 60").performIn(60.seconds, "hello")
      _ <- saySomething("Do this in 1 minute!").performIn(1.minute, "delayed")
      _ <- saySomething("Experiment is done!").performIn(90.seconds, "hello")
      _ <- countFromTo(0, 20, 3).performAsync
      _ <- taskThatFails("Please fail me.").performIn(5.seconds, "failures")
      _ <- saySomething("Mail was sent! ✉️").performIn(5.seconds, "mail")
      _ <- logInfo("Done.")
    yield ()

  def program =
    for
      fib1 <- clientProgram.fork
      fib2 <- BacktaskScheduler.run.fork
      _    <- fib1.join
      _    <- fib2.join
    yield ()

  def run =
    (program.provideLayer(RedisClient.live) <&>
      Worker.run.delay(2.seconds).provideLayer(RedisClient.live) <&>
      Worker.run.delay(3.seconds).provideLayer(RedisClient.live))
      .timeout(2.minutes)
