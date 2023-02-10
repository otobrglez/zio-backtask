package zio.backtask

import java.time.Clock

type JobID = ID
final case class Job(
  queueName: String,
  className: String,
  jid: JobID = ID.mkID,
  createdAt: Long = java.time.Instant.now(Clock.systemUTC()).toEpochMilli,
  args: List[Any] = List.empty,
  argTypes: List[String] = List.empty,
)
