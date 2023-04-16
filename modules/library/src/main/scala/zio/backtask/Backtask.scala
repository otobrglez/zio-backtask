package zio.backtask

import io.circe.*
import zio.Console.printLine
import zio.ZIO.{attempt, attemptBlocking, fail, logDebug, logError, logInfo, succeed, when}
import zio.backtask.Redis.{lpush, sadd, zadd}
import zio.logging.backend.SLF4J
import zio.stream.{ZSink, ZStream}
import zio.{Duration, ZIOAppDefault, *}

import java.math.BigInteger
import java.security.SecureRandom
import java.time.{Clock, Instant, LocalDateTime, ZoneOffset}
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.reflect.ClassTag

trait TaskSerde:
  def taskToJob[Env, T >: Backtask[Env]](task: T): ZIO[Any, Throwable, (JobID, String)]
  def jobToTask[Env, T >: Backtask[Env]](job: String): T // TODO: This should not be just "T"

trait Backtask[Env]:
  self =>
  import Backtask.{default, enqueue, QueueName}

  def queueName: QueueName                                = default
  private var delay: Option[FiniteDuration]               = None
  private[this] var afterTasks: ListBuffer[Backtask[Env]] = ListBuffer.empty

  def withDelay(delay: FiniteDuration): Backtask[Env]                          = { self.delay = Some(delay); self }
  def withAfterTasks[T >: Backtask[Env]](tasks: Backtask[Env]*): Backtask[Env] = { afterTasks.appendAll(tasks); self }

  def run: ZIO[Env, Throwable, Unit]

  def performAsync[T >: Backtask[Env]](using TaskSerde): ZIO[Redis, Throwable, JobID] =
    enqueue(self, queueName = default, None)

  def performAsync[T >: Backtask[Env]](queueName: QueueName)(using TaskSerde): ZIO[Redis, Throwable, JobID] =
    enqueue(self, queueName, None)

  def performIn[T >: Backtask[Env]](delay: FiniteDuration)(using TaskSerde): ZIO[Redis, Throwable, JobID] =
    enqueue(self, queueName = default, Some(delay))

  def performIn[T >: Backtask[Env]](delay: FiniteDuration, queueName: QueueName)(using
    TaskSerde
  ): ZIO[Redis, Throwable, JobID] =
    enqueue(self, queueName, Some(delay))

  def performIn[T >: Backtask[Env]](delay: zio.Duration)(using TaskSerde): ZIO[Redis, Throwable, JobID] =
    enqueue(self, queueName = default, Some(delay.toSeconds.seconds))

  def performIn[T >: Backtask[Env]](delay: zio.Duration, queueName: QueueName)(using
    TaskSerde
  ): ZIO[Redis, Throwable, JobID] =
    enqueue(self, queueName, Some(delay.toSeconds.seconds))

  def performAt[T >: Backtask[Env]](
    at: LocalDateTime,
    queueName: QueueName = default
  )(using TaskSerde): ZIO[Redis, Throwable, JobID] =
    attempt(
      FiniteDuration.apply(Instant.now.getEpochSecond - at.toEpochSecond(ZoneOffset.UTC), TimeUnit.SECONDS)
    ).flatMap(delay => enqueue(self, queueName, Some(delay)))

object Backtask:
  type QueueName = String
  val default: QueueName = "default"
  type JobJson = String

  private def enqueue[Env, T <: Backtask[Env]](
    task: T,
    queueName: QueueName = default,
    delay: Option[FiniteDuration] = None
  )(using ts: TaskSerde): ZIO[Redis, Throwable, JobID] =
    for
      queue <- succeed(getQueueName(task, queueName))
      _     <- when(delay.isDefined && queue == default)(
        fail(new RuntimeException("Tasks on default queue can't be delayed."))
      )
      pmx   <- ts.taskToJob(task)
      _     <- ZIO.succeed(println("FIRE " * 5 + s"PMX = ${pmx}"))
      // pmx      <- TaskSerde.taskToJob(task)

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
