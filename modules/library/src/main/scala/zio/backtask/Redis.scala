package zio.backtask

import zio.*
import zio.ZIO.{acquireRelease, attempt, fromCompletableFuture, fromOption, logDebug, logInfo, serviceWithZIO}
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.{Limit as LettuceLimit, Range as LettuceRange, RedisClient as LettuceRedisClient}

import scala.jdk.CollectionConverters.*

private[this] type Redis               = IRedis[Any]
private[this] type RedisImplementation = IRedis[Redis]
private[this] type RedisConnection     = StatefulRedisConnection[String, String]

object RedisClient:
  private def mkClientFromURI(uri: String): ZIO[Scope, Throwable, LettuceRedisClient] =
    acquireRelease(attempt(LettuceRedisClient.create(uri)))(client => attempt(client.close()).orDie)

  private def mkClient: ZIO[Scope, Throwable, LettuceRedisClient] =
    for
      readUrl <- System.env("REDIS_URL")
      url     <- fromOption(readUrl).orDieWith(_ => new RuntimeException("Failed reading REDIS_URL"))
      client  <- mkClientFromURI(url)
    yield client

  private def mkConnection(client: LettuceRedisClient): ZIO[Scope, Throwable, RedisConnection] =
    acquireRelease(
      attempt(client.connect()) <* logDebug("Connected.")
    )(connection => attempt(connection.close()).orDie <* logDebug("Disconnected."))

  val live: ZLayer[Scope, Throwable, Redis] =
    ZLayer.fromZIO(mkClient.flatMap(mkConnection).map(RedisImpl.apply))

private trait IRedis[Env]:
  def asyncSet(key: String, value: String): ZIO[Env, Throwable, String]
  def asyncSadd(key: String, values: String*): ZIO[Env, Throwable, Long]
  def asyncZadd(key: String, score: Double, member: String): ZIO[Env, Throwable, Long]
  def asyncZrem(key: String, members: String*): ZIO[Env, Throwable, Long]
  def asyncLpush(key: String, values: String*): ZIO[Env, Throwable, Long]
  def asyncZrangebyscore(
    key: String,
    range: (Double, Double),
    limit: (Double, Double)
  ): ZIO[Env, Throwable, Array[String]]
  def asyncSMembers(key: String): ZIO[Env, Throwable, Set[String]]
  def asyncBrpop(delay: Double, keys: String*): ZIO[Env, Throwable, (String, String)]

  def set(key: String, value: String): ZIO[Env, Throwable, String]
  def brpop(delay: Double, keys: String*): ZIO[Env, Throwable, (String, String)]
  def flushdb(): ZIO[Env, Throwable, String]

private case class RedisImpl(private val connection: RedisConnection) extends Redis:
  private def sync  = connection.sync()
  private def async = connection.async()

  def asyncSet(key: String, value: String): ZIO[Any, Throwable, String] =
    fromCompletableFuture(async.set(key, value).toCompletableFuture)

  def asyncSadd(key: String, values: String*): ZIO[Any, Throwable, Long] =
    fromCompletableFuture(async.sadd(key, values: _*).toCompletableFuture).map(_.toLong)

  def asyncZadd(key: String, score: Double, member: String): ZIO[Any, Throwable, Long] =
    fromCompletableFuture(async.zadd(key, score, member).toCompletableFuture).map(_.toLong)

  def asyncZrem(key: String, members: String*): ZIO[Any, Throwable, Long] =
    fromCompletableFuture(async.zrem(key, members: _*).toCompletableFuture).map(_.toLong)

  def asyncLpush(key: String, values: String*): ZIO[Any, Throwable, Long] =
    fromCompletableFuture(async.lpush(key, values: _*).toCompletableFuture).map(_.toLong)

  def asyncSMembers(key: String): ZIO[Any, Throwable, Set[String]] =
    fromCompletableFuture(async.smembers(key).toCompletableFuture).map(_.asScala.toSet)

  def asyncBrpop(delay: Double, keys: String*): ZIO[Any, Throwable, (String, String)] =
    fromCompletableFuture(
      async.brpop(delay, keys: _*).toCompletableFuture
    ).map(kv => (kv.getKey, kv.getValue))

  def set(key: String, value: String): ZIO[Any, Throwable, String] =
    attempt(sync.set(key, value))

  def brpop(delay: Double, keys: String*): ZIO[Any, Throwable, (String, String)] =
    fromCompletableFuture(
      async.brpop(delay, keys: _*).toCompletableFuture
    ).map(kv => (kv.getKey, kv.getValue))

  def asyncZrangebyscore(
    key: String,
    range: (Double, Double),
    limit: (Double, Double)
  ): ZIO[Any, Throwable, Array[String]] =
    fromCompletableFuture(
      async
        .zrangebyscore(
          key,
          LettuceRange.create[java.lang.Long](range._1.toLong, range._2.toLong),
          LettuceLimit.create(limit._1.toLong, limit._2.toLong)
        )
        .toCompletableFuture
    ).map(_.asScala.toArray)

  def flushdb(): ZIO[Any, Throwable, String] =
    attempt(sync.flushdb())

object Redis extends RedisImplementation:
  def asyncSet(key: String, value: String): ZIO[Redis, Throwable, String] =
    serviceWithZIO[Redis](_.asyncSet(key, value))

  def asyncSadd(key: String, values: String*): ZIO[Redis, Throwable, Long] =
    serviceWithZIO[Redis](_.asyncSadd(key, values: _*))

  def asyncZadd(key: String, score: Double, member: String): ZIO[Redis, Throwable, Long] =
    serviceWithZIO[Redis](_.asyncZadd(key, score, member))

  def asyncZrem(key: String, members: String*): ZIO[Redis, Throwable, Long] =
    serviceWithZIO[Redis](_.asyncZrem(key, members: _*))

  def asyncLpush(key: String, values: String*): ZIO[Redis, Throwable, Long] =
    serviceWithZIO[Redis](_.asyncLpush(key, values: _*))

  def asyncSMembers(key: String): ZIO[Redis, Throwable, Set[String]] =
    serviceWithZIO[Redis](_.asyncSMembers(key))

  def asyncZrangebyscore(
    key: String,
    range: (Double, Double),
    limit: (Double, Double)
  ): ZIO[Redis, Throwable, Array[String]] =
    serviceWithZIO[Redis](_.asyncZrangebyscore(key, range, limit))

  def asyncBrpop(delay: Double, keys: String*): ZIO[Redis, Throwable, (String, String)] =
    serviceWithZIO[Redis](_.asyncBrpop(delay, keys: _*))

  def set(key: String, value: String): ZIO[Redis, Throwable, String] =
    serviceWithZIO[Redis](_.set(key, value))

  def brpop(delay: Double, keys: String*): ZIO[Redis, Throwable, (String, String)] =
    serviceWithZIO[Redis](_.brpop(delay, keys: _*))

  def flushdb(): ZIO[Redis, Throwable, String] =
    serviceWithZIO[Redis](_.flushdb())
