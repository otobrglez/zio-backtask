package zio.backtask

import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax.*

object SERDE:
  private def unsafe(x: Option[Json], `type`: String): Json =
    x.getOrElse(throw new Exception(s"Invalid ${`type`} value"))

  private def convertValue(value: Any): Json =
    value match {
      case str: String       => Json.fromString(str)
      case float: Float      => unsafe(Json.fromFloat(float), "float")
      case double: Double    => unsafe(Json.fromDouble(double), "double")
      case int: Int          => Json.fromInt(int)
      case bigInt: BigInt    => Json.fromBigInt(bigInt)
      case long: Long        => Json.fromLong(long)
      case option: Option[_] =>
        option match {
          case None             => Json.Null
          case Some(innerValue) => convertValue(innerValue)
        }
      case list: List[_]     => Json.arr(list.map(convertValue): _*)
      case array: Array[_]   => Json.arr(array.map(convertValue): _*)
      case obj: Map[_, _]    =>
        Json.obj(obj.toList.map { case (k, v) => (k.asInstanceOf[String], convertValue(v)) }: _*)
      // case job: Job          => jobEncoder(job)
      case v                 => throw new Exception(s"No idea what to do with ${v}")
    }

  implicit val jobEncoder: Encoder[Job] = deriveEncoder
  implicit val jobDecoder: Decoder[Job] = deriveDecoder

  implicit val mapOfAnyEncoder: io.circe.Encoder[Map[String, Any]] = v => convertValue(v)
  // implicit val listOfStringEncoder: io.circe.Encoder[List[String]] = v => Json.arr(v.map(convertValue): _*)
  implicit val listOfAnyEncoder: io.circe.Encoder[List[Any]]       = v => Json.arr(v.map(convertValue): _*)

  implicit val anyDecoder: Decoder[Any] = Decoder.instance { c =>
    c.focus match {
      case Some(x) => Right(x)
      case None    => Left(DecodingFailure("Could not parse", List()))
    }
  }
