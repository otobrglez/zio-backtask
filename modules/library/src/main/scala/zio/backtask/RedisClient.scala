package zio.backtask

import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.RedisClient as LettuceRedisClient
import zio.ZIO.{acquireRelease, attempt, fromCompletableFuture, fromOption, logDebug, logInfo, serviceWithZIO}
import zio.{Scope, System, ZIO, ZLayer, *}

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
