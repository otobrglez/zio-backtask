package zio.backtask

import zio.*
import zio.ZIO.{logDebug, logInfo, succeed, when}
import zio.stream.ZStream
import Redis.{lpush, rpop, smembers, zrangebyscore, zrem}
import java.time.Instant

object BacktaskScheduler:
  import Backtask.{default, QueueName}
  private val now: QueueName = "now"
  private val batchSize      = 100

  private def logMove(queueName: QueueName)(size: Long) =
    logDebug(s"Jobs moved from $queueName to now: $size")

  private def handleQueue(queueName: QueueName): ZIO[Redis, Throwable, Unit] =
    if queueName == default then
      for
        key  <- succeed(s"queue:$default")
        jobs <- rpop(key, batchSize)
        _    <- when(jobs.nonEmpty)(lpush(now, jobs: _*).flatMap(logMove(key)))
      yield ()
    else
      for
        jobs <- zrangebyscore(
          s"queue:$queueName",
          (Double.NegativeInfinity, Instant.now.getEpochSecond.toDouble),
          (0, batchSize)
        )
        _    <- when(jobs.nonEmpty) {
          lpush(now, jobs: _*).flatMap(logMove("*")) <&> zrem(s"queue:$queueName", jobs: _*)
        }
      yield ()

  def run: ZIO[Redis, Throwable, Unit] =
    for
      _   <- logDebug("Booting scheduler.")
      // TODO: This thing should run only once. Locking mechanism needed here!
      out <- {
        ZStream
          .fromZIO(smembers("queues"))
          .mapConcat(identity)
          .mapZIO(handleQueue)
          .runDrain
      }.repeat(Schedule.spaced(1.seconds) && Schedule.forever).fork
      _   <- out.join
      _   <- logDebug("Scheduler stopped.")
    yield ()
