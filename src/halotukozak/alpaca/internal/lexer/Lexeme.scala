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
 * The context fields are a snapshot taken after the rule body ran, except
 * for the [[Line]] and [[Column]] fields, which record where the token starts.
 *
 * @tparam Name the token name type
 * @tparam Value the value type
 * @param name the token name
 * @param value the extracted value
 * @param lineIndex   the index of the [[Line]] field in `fieldValues`, or -1
 * @param columnIndex the index of the [[Column]] field in `fieldValues`, or -1
 * @param lineAfter   the line right after the token, when `lineIndex >= 0`
 * @param columnAfter the column right after the token, when `columnIndex >= 0`
 */
final class Lexeme[+Name <: ValidName, +Value] private[alpaca] (
  val name: Name,
  val value: Value,
  val text: String,
  private[alpaca] val fieldNames: Array[String],
  private[alpaca] val fieldValues: Array[Any],
  private[alpaca] val lineIndex: Int = -1,
  private[alpaca] val columnIndex: Int = -1,
  private[alpaca] val lineAfter: Int = 0,
  private[alpaca] val columnAfter: Int = 0,
) extends Selectable:
  type Fields <: AnyNamedTuple

  def selectDynamic(name: String): Any =
    boundary:
      for i <- fieldNames.indices if fieldNames(i) == name do break(fieldValues(i))
      throw new NoSuchElementException(name)

  /** The line the token starts on, if the lexer context tracks a [[Line]]. */
  private[alpaca] def startLine: Option[Int] = Option.when(lineIndex >= 0)(fieldValues(lineIndex).asInstanceOf[Int])

  /** The column the token starts at, if the lexer context tracks a [[Column]]. */
  private[alpaca] def startColumn: Option[Int] =
    Option.when(columnIndex >= 0)(fieldValues(columnIndex).asInstanceOf[Int])

  /** The line right after the token, if the lexer context tracks a [[Line]]. */
  private[alpaca] def endLine: Option[Int] = Option.when(lineIndex >= 0)(lineAfter)

  /** The column right after the token, if the lexer context tracks a [[Column]]. */
  private[alpaca] def endColumn: Option[Int] = Option.when(columnIndex >= 0)(columnAfter)

private[alpaca] object Lexeme:
  /**
   * A special end-of-file lexeme used to signal the end of input.
   *
   * This is used internally by the parser to detect when all input has been consumed.
   */
  private[alpaca] val EOF: Lexeme["$", String] = Lexeme("$", "", "", Array.empty, Array.empty)
