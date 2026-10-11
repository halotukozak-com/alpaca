package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.internal.ValidName

import scala.NamedTuple.AnyNamedTuple

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
 * The context fields are a snapshot taken after the rule body ran, except that a field with a [[Tracking]] given
 * keeps its value from before the match (even if the rule body assigned it), so it describes where the token starts.
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

  /** Backs context field selection by name (`lexeme.NAME`); not meant to be called directly. */
  final def selectDynamic(name: String): Any = contextField(fieldNames, fieldValues, name)

  override def equals(that: Any): Boolean = that match
    case that: Lexeme[?, ?] =>
      name == that.name && value == that.value && text == that.text && fieldNames.sameElements(that.fieldNames) &&
      fieldValues.sameElements(that.fieldValues)
    case _ => false

  override def hashCode: Int = (name, value, text, fieldNames.toSeq, fieldValues.toSeq).##

  override def toString: String =
    // Scala.js prints `()` as `undefined`
    def show(any: Any): String = any match
      case _: Unit => "()"
      case other => String.valueOf(other)
    val fields = fieldNames.lazyZip(fieldValues).map((fieldName, fieldValue) => s"$fieldName = ${show(fieldValue)}")
    (Array(name, show(value), s"\"$text\"") ++ fields).mkString("Lexeme(", ", ", ")")

private[alpaca] object Lexeme:
  /**
   * A special end-of-file lexeme used to signal the end of input.
   *
   * This is used internally by the parser to detect when all input has been consumed.
   */
  private[alpaca] val EOF: Lexeme["$", String] = Lexeme("$", "", "", Array.empty, Array.empty)
