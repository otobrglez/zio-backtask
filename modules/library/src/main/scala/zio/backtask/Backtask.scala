package zio.backtask

import io.circe.*
import zio.Console.printLine
import zio.ZIO.{attempt, attemptBlocking, fail, logDebug, logError, logInfo, succeed, when}
import zio.backtask.Redis.{lpush, sadd, zadd}
import zio.logging.backend.SLF4J
import zio.stream.{ZSink, ZStream}
import zio.{ZIOAppDefault, *}

import java.math.BigInteger
import java.security.SecureRandom
import java.time.{Clock, Instant, LocalDateTime, ZoneOffset}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

trait Backtask[-Env]:
  self =>
  import Backtask.{default, QueueName}

  def queueName: QueueName = default

  def run: ZIO[Env, Throwable, Unit]

  def performAsync[T >: Backtask[Env]]: ZIO[Redis, Throwable, JobID] =
    Backtask.enqueue(self, queueName = default, None)

  def performAsync[T >: Backtask[Env]](queueName: QueueName): ZIO[Redis, Throwable, JobID] =
    Backtask.enqueue(self, queueName, None)

  def performIn[T >: Backtask[Env]](delay: FiniteDuration): ZIO[Redis, Throwable, JobID] =
    Backtask.enqueue(self, queueName = default, Some(delay))

  def performIn[T >: Backtask[Env]](delay: FiniteDuration, queueName: QueueName): ZIO[Redis, Throwable, JobID] =
    Backtask.enqueue(self, queueName, Some(delay))

  def performIn[T >: Backtask[Env]](delay: zio.Duration): ZIO[Redis, Throwable, JobID] =
    Backtask.enqueue(self, queueName = default, Some(delay.toSeconds.seconds))

  def performIn[T >: Backtask[Env]](delay: zio.Duration, queueName: QueueName): ZIO[Redis, Throwable, JobID] =
    Backtask.enqueue(self, queueName, Some(delay.toSeconds.seconds))

  def performAt[T >: Backtask[Env]](
    at: LocalDateTime,
    queueName: QueueName = default
  ): ZIO[Redis, Throwable, JobID] =
    attempt(
      FiniteDuration.apply(Instant.now.getEpochSecond - at.toEpochSecond(ZoneOffset.UTC), TimeUnit.SECONDS)
    ).flatMap(delay =>
      Backtask.enqueue(
        self,
        queueName,
        Some(delay)
      )
    )

object Backtask:
  type QueueName = String
  val default: QueueName = "default"
  type JobJson = String

  private def enqueue[Env, T <: Backtask[Env]](
    task: T,
    queueName: QueueName = default,
    delay: Option[FiniteDuration] = None
  ): ZIO[Redis, Throwable, JobID] =
    for
      queue    <- succeed(getQueueName(task, queueName))
      _        <- when(delay.isDefined && queue == default)(
        fail(new RuntimeException("Tasks on default queue can't be delayed."))
      )
      jobTuple <- taskToJob(task, queue)
      result   <- durationToFuture(delay).fold {
        sadd("queues", default, queue) <&> Option
          .when(queue == default)(
            lpush(s"queue:$queue", jobTuple._2)
          )
          .getOrElse(
            zadd(s"queue:$queue", Instant.now.getEpochSecond.toDouble, jobTuple.jobJson)
          )
      } { future =>
        sadd("queues", default, queue) <&>
          zadd(s"queue:$queue", future.toDouble, jobTuple.jobJson)
      }
      _        <- logDebug(s"Scheduled job ${jobTuple.job.jid} on queue \"$queue\" with result $result")
    yield jobTuple.job.jid

  private def getQueueName[Env, T <: Backtask[Env]](task: T, outerQueueName: QueueName): QueueName =
    if outerQueueName != task.queueName then
      if outerQueueName != default then outerQueueName
      else task.queueName
    else task.queueName

  private val durationToFuture: Option[FiniteDuration] => Option[Long] =
    _.map(_.toSeconds + Instant.now.getEpochSecond)

  private def argsFrom[Env, T <: Backtask[Env]](task: T): Map[String, (String, Any)] =
    task.getClass.getDeclaredFields
      .tapEach(_.setAccessible(true))
      .foldLeft(Map.empty)((a, f) =>
        a + (f.getName -> {
          val v = f.get(task)
          (v.getClass.getName, v)
        })
      )

  final case class JobTuple(job: Job, jobJson: JobJson)
  private def taskToJob[Env, T <: Backtask[Env]](task: T, queueName: QueueName): Task[JobTuple] =
    import SERDE.jobEncoder
    for
      job     <- succeed(
        Job(
          queueName,
          task.getClass.getName,
          args = argsFrom(task).values.map(_._2).toList,
          argTypes = argsFrom(task).values.map(_._1).toList
        )
      )
      jobJson <- attempt(jobEncoder(job).noSpaces)
    yield JobTuple(job, jobJson)
