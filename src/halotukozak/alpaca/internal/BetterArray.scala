package halotukozak
package alpaca.internal

import scala.annotation.publicInBinary
import scala.reflect.ClassTag

extension (dummy: Array.type) inline private[internal] def better: BetterArray.type = BetterArray

@publicInBinary private[internal] object BetterArray:
  inline def tabulate[T: ClassTag](n: Int)(inline f: Int => T): Array[T] =
    if n <= 0 then Array.empty[T]
    else
      val array = new Array[T](n)
      var i = 0
      while i < n do
        array(i) = f(i)
        i += 1
      array
