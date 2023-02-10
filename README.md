# ZIO Backtask

![scala-version][scala-version-badge]

## Overview

ZIO Backtask is background job framework that you can simply implement in your ZIO application. 
It is backed by [Redis], an in-memory key-value store known for its flexibility and performance.

ZIO Backtask was heavily inspired by [sidekiq] - very popular Ruby framework for background jobs.

The project uses ZIO 2, Scala 3 and its capabilities and relies upon [Lettuce] Redis Client.

> ⚠️ This project is in proof-of-concept phase. It lacks a lot of basic and expected 
> functionalities and its APIs are subject to change. Please feel free to reach-out
> if you would like to support or help with this project or you see potential in it.

## Getting started

```scala
// A few example user-defined tasks.
object tasks:

  // Task that adds two numbers together and outputs the result.
  case class add(a: Int, b: Int, yes: Option[String] = None) extends Backtask[Any]:
    override def queueName = "math"
    def run = printLine(s"Add: $a + $b = ${a + b}")

  // Task that outputs whatever string it got.
  case class saySomething(raw: String) extends Backtask[Any]:
    def run = printLine(s"Said ${raw}")

  // A task that counts and generates new tasks with delay until a condition is reached
  case class countFromTo(from: Int, to: Int, delay: Int) extends Backtask[Redis]:
    override def queueName = "counting"
    def run =
      for
        _ <- printLine(s"n: $from, queueName: $queueName")
        _ <-
          if (from + 1 <= to)
            countFromTo(from + 1, to, delay).performIn(delay.seconds)
          else ZIO.unit
      yield ()

object BacktaskApp extends ZIOAppDefault:
  import tasks.*

  def clientProgram: ZIO[Redis, Throwable, Unit] =
    for
      _ <- logInfo("Booting.")
      _ <- add(40, 2).performAsync
      _ <- add(20, 22).performAsync("math")
      _ <- saySomething("Hello world!").performAsync
      _ <- saySomething("Doing this in one hour").performAt(LocalDateTime.now.plusHours(1), "hello")
      _ <- saySomething("Hello after 10 seconds").performIn(10.seconds, "hello")
      _ <- saySomething("Do this in 1 minute!").performIn(1.minute, "delayed")
      _ <- saySomething("Experiment is done!").performIn(2.minutes, "hello")
      _ <- countFromTo(0, 20, 3).performAsync
      _ <- saySomething("Mail was sent! ✉️").performIn(5.seconds, "mail")
    yield ()

  def program =
    for
      fib1 <- clientProgram.fork
      // TODO: The BacktaskScheduler will be embedded in the Worker in the near future. 
      fib2 <- BacktaskScheduler.run.fork
      _    <- fib1.join
      _    <- fib2.join
    yield ()

  // Core program is started with two additional workers that actually consume tasks and execute them.
  def run =
    program.provideLayer(RedisClient.live) <&>
      Worker.run.delay(2.seconds).provideLayer(RedisClient.live) <&>
      Worker.run.delay(3.seconds).provideLayer(RedisClient.live)
```

Please check the [examples](modules/examples/src/main/scala/zio/backtask/examples) for more examples and details.

## Supported methods

### `performAsync`

Moves the given `Backtask` to `default` queue for instant consumption.

The `queueName` is set to default, however it can be overriden.

### `performIn`

Moves the `Backtask` to a given queue. The task will be executed after the given
`FiniteDelay`. Tasks, that are delayed or scheduled can not be scheduled on `default`
queue.

### `performAt`

Function accepts `LocalDateTime` defining the exact time when the `Backtask` should 
be executed. The system works on UTC; whatever timezone will be given gets converted to it.

Same as with `performIn`, this function can't schedule on `default` queue, 
meaning that some other queue needs to be given.


## Resources

- [Redis / ZRANGEBYSCORE](https://redis.io/commands/zrangebyscore/)
- [Redis / BRPOPLPUSH](https://redis.io/commands/brpoplpush/)
- [How Sidekiq really works](https://www.paweldabrowski.com/articles/how-sidekiq-really-works)
- [Jesque](https://github.com/gresrun/jesque)
- [sidekiq](https://github.com/mperham/sidekiq)

## Authors

- [Oto Brglez](https://github.com/otobrglez) / [@otobrglez](https://twitter.com/otobrglez)

[Redis]: https://redis.io
[Lettuce]: https://lettuce.io
[sidekiq]: https://github.com/mperham/sidekiq
[scala-version-badge]: https://img.shields.io/badge/scala-3.2.2-blue?logo=scala&color=red
