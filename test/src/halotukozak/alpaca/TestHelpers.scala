package halotukozak
package alpaca

import halotukozak.alpaca.internal.Source
import halotukozak.alpaca.internal.lexer.LazyReader
import java.io.StringReader
import scala.util.Using

// Backed by a StringReader rather than a real file so this helper (and the tests built on
// it) stay portable to Scala.js, which has no java.nio.file filesystem support.
inline def withLazyReader[A](fileContent: String)(inline action: LazyReader => A): A =
  Using.resource(LazyReader(StringReader(fileContent), fileContent.length))(action)

/** Source for productions built by hand in unit tests, which have no definition in any grammar. */
val TestSource: Source = Source(line = 0, file = "test", start = 0, end = 0)
