package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.internal.ValidName

import scala.NamedTuple.AnyNamedTuple
import scala.util.boundary
import scala.util.boundary.break

/**
 * A lexeme represents a token that has been matched and extracted from the input.
 *
 * A lexeme contains the token's name and the value that was extracted from
 * the matched text. This is the output of the tokenization process.
 *
 * Fields exposed via `selectDynamic` are kept as two parallel arrays plus the
 * matched `text` rather than a `Map[String, Any]`, so every token match only
 * allocates one small `Array[Any]` for the values (field names are cached
 * per-context-class). Lookup is a linear scan, which is optimal for the
 * tiny field counts typical of `LexerCtx` subclasses.
 *
 * @tparam Name the token name type
 * @tparam Value the value type
 * @param name the token name
 * @param value the extracted value
 */
final class Lexeme[+Name <: ValidName, +Value] private[alpaca] (
  val name: Name,
  val value: Value,
  val text: String,
  private[alpaca] val fieldNames: Array[String],
  private[alpaca] val fieldValues: Array[Any],
) extends Selectable:
  type Fields <: AnyNamedTuple

  def selectDynamic(name: String): Any =
    boundary:
      for i <- fieldNames.indices if fieldNames(i) == name do break(fieldValues(i))
      throw new NoSuchElementException(name)

private[alpaca] object Lexeme:
  /**
   * A special end-of-file lexeme used to signal the end of input.
   *
   * This is used internally by the parser to detect when all input has been consumed.
   */
  private[alpaca] val EOF: Lexeme["$", String] = Lexeme("$", "", "", Array.empty, Array.empty)
