package halotukozak
package alpaca

import halotukozak.alpaca.internal.*
import halotukozak.alpaca.internal.lexer.Token

import scala.annotation.{compileTimeOnly, unused}

// In its own file because an opaque type is transparent to the whole file that declares it.
/**
 * A reference to one of the parser's productions inside [[resolutions]], taken from `production.<name>` or
 * `Production(symbols*)` and passed to `before`/`after`. It exists only at compile time, so it has no members and no
 * other way to create one.
 */
opaque type Production = Null

object Production:

  /**
   * Creates a production reference from symbols.
   *
   * This is compile-time only and can be used only inside [[resolutions]], to refer to a production by its
   * right-hand side, e.g. `Production(CalcParser.Expr, CalcLexer.MINUS, CalcParser.Expr)`.
   *
   * @param symbols the symbols on the right-hand side of the production
   * @return a production reference
   */
  @compileTimeOnly(ConflictResolutionOnly)
  inline def apply[P <: parser.Parser[?]: ResolutionScope](@unused symbols: (Rule[?] | Token[?, ?, ?])*): Production =
    null
