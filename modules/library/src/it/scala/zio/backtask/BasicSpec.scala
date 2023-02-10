package zio.backtask

import zio.*
import zio.Console.printLine
import zio.logging.backend.SLF4J
import zio.test.*
import zio.test.TestAspect.*
import zio.test.Assertion.*
import zio.test.TestSystem.putEnv
import zio.logging.backend.SLF4J

private object tasks:
  case class Echo(input: String) extends Backtask[Redis]:
    def run: ZIO[Redis, Throwable, Unit] = printLine(input)

object BasicSpec extends ZIOSpecDefault:
  import tasks.*
  override val bootstrap = zio.Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> testEnvironment

  def spec = (suite("Basic usage")(
    test("adds a few tasks") {
      val clientProgram =
        for
          _ <- Echo("This should happen on default.").performAsync
          _ <- Echo("Example.").performIn(5.seconds, "examples")
        yield ()

      val program =
        for
          f1 <- clientProgram.fork
          f2 <- BacktaskScheduler.run.fork
          _  <- f1.join
          _  <- f2.join
        yield ()

      assertZIO(
        (program.provideLayer(RedisClient.live) <&>
          Worker.run.delay(2.seconds).provideLayer(RedisClient.live))
          .timeout(7.seconds)
          *> ZIO.succeed(1)
      )(
        equalTo(1)
      )
    }
  ) @@ withLiveClock
    @@ before {
      RedisContainer.port.flatMap(port => putEnv("REDIS_URL", s"redis://127.0.0.1:$port"))
    }
    @@ aroundAllWith(RedisContainer.start)(_ => RedisContainer.stop.orDie))
    .provideSomeLayerShared(RedisContainer.live)
