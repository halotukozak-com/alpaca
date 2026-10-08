package halotukozak
package alpaca
package internal
package lexer

import scala.util.boundary
import scala.util.boundary.break

/** The value of the context field `name` in a snapshot. */
private[alpaca] def contextField(fieldNames: Array[String], fieldValues: Array[Any], name: String): Any =
  boundary:
    for i <- fieldNames.indices if fieldNames(i) == name do break(fieldValues(i))
    throw new NoSuchElementException(name)
