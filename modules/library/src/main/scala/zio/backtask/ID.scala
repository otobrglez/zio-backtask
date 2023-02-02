package zio.backtask

import java.math.BigInteger
import java.security.SecureRandom

type ID = String
object ID:
  private val length = 10
  def mkID: ID       = String.format("%0" + length + "x", new BigInteger(length * 4, new SecureRandom()))
