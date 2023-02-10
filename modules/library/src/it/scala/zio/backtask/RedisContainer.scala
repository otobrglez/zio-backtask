package zio.backtask

import zio.{ZIO, ZLayer}
import ZIO.{acquireRelease, attempt, attemptBlocking, serviceWithZIO}
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

trait RedisContainer:
  def start: ZIO[Any, Throwable, Unit]
  def stop: ZIO[Any, Throwable, Unit]
  def mappedPort(port: Int): ZIO[Any, Throwable, Int]

private[this] case class RedisContainerImpl[T <: GenericContainer[_]](private val container: T) extends RedisContainer:
  def start: ZIO[Any, Throwable, Unit]                = attemptBlocking(container.start())
  def stop: ZIO[Any, Throwable, Unit]                 = attempt(container.stop())
  def mappedPort(port: Int): ZIO[Any, Throwable, Int] = attempt(container.getMappedPort(port))

object RedisContainer:
  private val containerPort: Int                    = 6379
  private def mkRedisContainer: GenericContainer[_] =
    new GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(containerPort)

  def live: ZLayer[zio.Scope, Throwable, RedisContainer] = ZLayer.fromZIO(
    acquireRelease(ZIO.attemptBlocking(mkRedisContainer).map(RedisContainerImpl.apply))(_.stop.orDie)
  )

  def start: ZIO[RedisContainer, Throwable, Unit]                = serviceWithZIO[RedisContainer](_.start)
  def stop: ZIO[RedisContainer, Throwable, Unit]                 = serviceWithZIO[RedisContainer](_.stop)
  def mappedPort(port: Int): ZIO[RedisContainer, Throwable, Int] =
    ZIO.serviceWithZIO[RedisContainer](_.mappedPort(port))
  def port: ZIO[RedisContainer, Throwable, Int] = serviceWithZIO[RedisContainer](_.mappedPort(containerPort))
