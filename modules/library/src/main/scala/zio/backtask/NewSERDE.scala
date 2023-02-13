package zio.backtask

import zio.backtask.Backtask.QueueName
import com.github.plokhotnyuk.jsoniter_scala.core.*

import java.time.Clock

case class NewJob[T <: Backtask[Env], Env](
  queueName: String,
  className: String,
  jid: JobID = ID.mkID,
  createdAt: Long = java.time.Instant.now(Clock.systemUTC()).toEpochMilli,
  task: T
)

object NewSERDE {

  def taskToJob[T <: Backtask[Env], Env](queueName: QueueName, task: T) =
    ()
    // NewJob(queueName, className = task.getClass.getName, task = task)

  def jobToTask(job: Job) = ???
}
