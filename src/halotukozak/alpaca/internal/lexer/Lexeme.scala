package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.internal.ValidName

import scala.NamedTuple.AnyNamedTuple

/**
 * A token matched in the input, as `tokenize` returns it: the token's name, its value, the matched text, and the
 * lexer context's fields, read by name (`lexeme.line` when the context has a `line` field). In a signature, write a
 * lexer's lexemes as `MyLexer.Lexeme` to keep those fields.
 *
 * The context fields are a snapshot taken after the rule body ran, except that a tracked field, like `line` or
 * `column`, keeps its value from before the match (even if the rule body assigned it), so it describes where the
 * token starts.
 *
 * @tparam Name the token name type
 * @tparam Value the value type
 * @param name the token name
 * @param value the token's value: what the rule passed to `Token["NAME"](value)`, or `()`
 * @param text the matched text
 */
final class Lexeme[+Name <: ValidName, +Value] private[alpaca] (
  val name: Name,
  val value: Value,
  val text: String,
  private[alpaca] val fieldNames: Array[String],
  private[alpaca] val fieldValues: Array[Any],
) extends Selectable:
  // Context fields are kept as two parallel arrays rather than a `Map[String, Any]`, so every token match only
  // allocates one small `Array[Any]` for the values (field names are cached per context class). Lookup is a linear
  // scan, which is optimal for the tiny field counts typical of contexts.

  /** The lexer context's fields, which the lexeme carries; a lexer's own lexemes have them as `MyLexer.Lexeme`. */
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
