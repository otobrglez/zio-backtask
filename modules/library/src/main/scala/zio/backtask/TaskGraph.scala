package zio.backtask

import zio.{backtask, ZIO}
import zio.stream.ZStream.fromIterable

type TaskGraph[Env] = List[Backtask[Env]]

object TaskGraph:
  given backtaskConversion[Env]: Conversion[Backtask[Env], TaskGraph[Env]] = task => backtask.TaskGraph(task)

  extension [T <: Backtask[Env], Env](task: T)
    def ++(otherTask: Backtask[Env]): TaskGraph[Env] = List(task, otherTask)
    def ~>(otherTask: Backtask[Env]): Backtask[Env]  = task.withAfterTasks(otherTask)

  extension [T >: Backtask[Env], Env](tasks: TaskGraph[Env])
    def ++(otherTask: Backtask[Env]): TaskGraph[Env]      = tasks ++ TaskGraph(otherTask)
    def performAsync()(using TaskSerde): ZIO[Redis, Throwable, Array[JobID]] =
      fromIterable(tasks).mapZIO(_.performAsync).runCollect.map(_.toArray)

  def apply[T <: Backtask[Env], Env](task: T): TaskGraph[Env] = List(task)

export TaskGraph.backtaskConversion
