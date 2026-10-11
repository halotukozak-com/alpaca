package halotukozak
package alpaca.internal

import scala.annotation.publicInBinary
import scala.reflect.ClassTag

extension (dummy: Array.type) inline private[alpaca] def better: BetterArray.type = BetterArray

@publicInBinary private[alpaca] object BetterArray:
  inline def tabulate[T: ClassTag](n: Int)(inline f: Int => T): Array[T] =
    if n <= 0 then Array.empty[T]
    else
      val array = new Array[T](n)
      var i = 0
      while i < n do
        array(i) = f(i)
        i += 1
      array

  inline def foreachIndex(length: Int)(inline f: Int => Unit): Unit =
    var i = 0
    while i < length do
      f(i)
      i += 1
