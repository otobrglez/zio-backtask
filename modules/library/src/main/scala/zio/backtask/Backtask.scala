package zio.backtask

import io.circe.*
import zio.Console.printLine
import zio.ZIO.{attempt, attemptBlocking, fail, logDebug, logError, logInfo, succeed, when}
import zio.logging.backend.SLF4J
import zio.stream.{ZSink, ZStream}
import zio.{ZIOAppDefault, *}

import scala.concurrent.duration.*
import io.circe.generic.semiauto.*
import io.circe.syntax.*

import java.math.BigInteger
import java.security.SecureRandom
import java.time.{Clock, Instant, ZoneOffset}

trait Backtask[Env]:
  self =>
  import Backtask.{QueueName, default}

  def queueName: QueueName = default

  def run: ZIO[Env, Throwable, Unit]

  def performAsync[T <: Backtask[Env]](
    queueName: QueueName = default
  ): ZIO[Redis, Throwable, JobID] =
    Backtask.enqueue(self, queueName, None)

  def performIn[T <: Backtask[Env]](
    delay: FiniteDuration,
    queueName: QueueName = default
  ): ZIO[Redis, Throwable, JobID] =
    Backtask.enqueue(self, queueName, Some(delay))

object Backtask:
  type QueueName = String
  val default: QueueName = "default"
  type JobJson = String

  def enqueue[Env, T <: Backtask[Env]](
    task: T,
    outQueueName: QueueName = default,
    delay: Option[FiniteDuration] = None
  ): ZIO[Redis, Throwable, JobID] =
    for
      queueName <- succeed(getQueue(task, outQueueName))
      _         <- when(delay.isDefined && queueName == default)(
        fail(new RuntimeException("Tasks on default queue can't be delayed."))
      )
      jobTuple  <- taskToJob(task, queueName)
      result    <- durationToFuture(delay).fold {
        Redis.asyncSadd("queues", default, queueName) <&> Option
          .when(queueName == default)(
            Redis.asyncLpush(s"queue:$queueName", jobTuple._2)
          )
          .getOrElse(
            Redis.asyncZadd(s"queue:$queueName", Instant.now.getEpochSecond.toDouble, jobTuple._2)
          )
      } { future =>
        Redis.asyncSadd("queues", default, queueName) <&>
          Redis.asyncZadd(s"queue:$queueName", future.toDouble, jobTuple._2)
      }
      _         <- logDebug(s"Scheduled job ${jobTuple._1.jid} on queue \"$queueName\" with result $result")
    yield jobTuple._1.jid

  private def getQueue[Env, T <: Backtask[Env]](task: T, outerQueueName: QueueName): QueueName =
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

  private def taskToJob[Env, T <: Backtask[Env]](task: T, queueName: QueueName): Task[(Job, JobJson)] =
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
      // TODO: Figure out who is doing double serialisation(?)
      _       <- ZIO.when(queueName.startsWith("\""))(ZIO.fail(new RuntimeException("Wtf?!")))
    yield (job, jobJson)
