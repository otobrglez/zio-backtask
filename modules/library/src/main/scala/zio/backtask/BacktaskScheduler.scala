package zio.backtask

import zio.*
import zio.ZIO.{logDebug, logInfo, when}
import zio.stream.ZStream

import java.time.Instant

object BacktaskScheduler:
  import Backtask.{QueueName, default}

  def run: ZIO[Redis, Throwable, Unit] =
    for
      _   <- logDebug("Booting scheduler.")
      // TODO: This thing should run only once. Lock needed
      out <- {
        ZStream
          .fromZIO(Redis.asyncSMembers("queues"))
          .mapConcat(identity)
          // TODO: Should default queue also be handled? => yes.
          .filterNot(_ == Backtask.default)
          // .tap(q => Console.printLine(s"q => ${q}"))
          .mapZIO { queueName =>
            for {
              // _    <- ZIO.fail(s"Q => ${queueName}")
              jobs <- Redis.asyncZrangebyscore(
                s"queue:$queueName",
                (Double.NegativeInfinity, Instant.now.getEpochSecond.toDouble),
                (0, 100)
              )
              _    <- when(jobs.nonEmpty) {
                (Redis.asyncLpush("now", jobs: _*) <&>
                  Redis.asyncZrem(s"queue:$queueName", jobs: _*)) *>
                  logDebug(s"Jobs moved to now: ${jobs.length}")
              }
            } yield jobs.length
          }
          .runDrain
      }.repeat(Schedule.spaced(1.seconds) && Schedule.forever).fork
      _   <- out.join
      _   <- logDebug("Scheduler stopped.")
    yield ()
