package zio.backtask

import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import zio.*
import zio.Console.printLine
import zio.ZIO.{attempt, fromEither, logDebug, logError, logInfo, logSpan, succeed}
import zio.backtask.Backtask.{JobJson, QueueName, default}
import zio.backtask.SERDE.*
import zio.stream.ZStream.fromQueue as streamFromQueue

import scala.jdk.CollectionConverters.*

final case class Worker(private val id: ID = ID.mkID):
  private def jsonToJob(jobJson: JobJson): Task[Job] =
    for
      json      <- fromEither(io.circe.parser.parse(jobJson))
      hcursor   <- succeed(json.hcursor)
      className <- fromEither(hcursor.get[String]("className"))
      queueName <- fromEither(hcursor.get[String]("queueName"))
      jid       <- fromEither(hcursor.get[String]("jid"))
      createdAt <- fromEither(hcursor.get[Long]("createdAt"))
      args      <- fromEither(hcursor.downField("args").as[List[Any]])
      argTypes  <- fromEither(hcursor.downField("argTypes").as[List[String]])
    yield Job(queueName, className, jid, createdAt, args, argTypes)

  private def arguments(job: Job): List[Any] =
    job.args
      .zip(job.argTypes)
      .map { case (value: Any, kind: String) =>
        kind match {
          // TODO: Something is injecting double quotes. Need to investigate.
          case "java.lang.String"  => value.toString.drop(1).dropRight(1)
          case "java.lang.Integer" => Integer.parseInt(value.toString)
          case "scala.None$"       => scala.None
          case v                   => throw new RuntimeException(s"No idea about ${v}")
        }
      }

  private def jobToTask[Env](job: Job): ZIO[Any, Throwable, Backtask[Env]] =
    for
      constructor <- attempt(Class.forName(job.className).getConstructors.head)
      instance    <- attempt(constructor.newInstance(arguments(job): _*).asInstanceOf[Backtask[Env]])
    yield instance

  private def handleJob(queueName: QueueName, jobJson: JobJson): ZIO[Any, Throwable, Unit] =
    for
      job    <- jsonToJob(jobJson)
      task   <- jobToTask(job)
      _      <- logDebug(s"Resolved job ${job.jid} to task $task on $queueName")
      result <- logSpan("Running") {
        task.run.catchAll { th =>
          logError(s"Task crashed with - ${th}")
        }
      }.fork
      _      <- result.join
    yield printLine(job)

  private[this] def run: ZIO[Redis, Throwable, Unit] =
    for
      _       <- logInfo("Starting worker.")
      work    <- Queue.bounded[(QueueName, JobJson)](100)
      workFib <- streamFromQueue(work)
        .runForeach(handleJob)
        .forever
        .fork

      readerFib <- Redis
        .brpop(0, default, "now")
        .flatMap(work.offer)
        .schedule(Schedule.spaced(50.milliseconds) && Schedule.forever)
        .fork
      _         <- workFib.join.zipPar(readerFib.join)
    yield ()

object Worker:
  def run: ZIO[Redis, Throwable, Unit] = Worker().run
