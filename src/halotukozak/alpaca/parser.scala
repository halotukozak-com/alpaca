package halotukozak
package alpaca

import halotukozak.alpaca.internal.*
import halotukozak.alpaca.internal.lexer.{Lexeme, Token}
import halotukozak.alpaca.internal.parser.{Production as _, *}

import scala.NamedTuple.AnyNamedTuple
import scala.annotation.{compileTimeOnly, implicitNotFound, unused}

type Parser[Ctx <: ParserCtx] = parser.Parser[Ctx]

/**
 * How the parser `ParserType` resolves the conflicts in its grammar. Provide one as a `given` next to the parser object (before or
 * after it) or as the object's last member, built with [[resolutions]]:
 * {{{
 * given Resolutions[CalcParser.type] = resolutions(
 *   production.plus.before(CalcLexer.PLUS), // + is left-associative
 * )
 * }}}
 *
 * @tparam ParserType the parser's singleton type
 */
opaque type Resolutions[ParserType <: parser.Parser[?]] = Set[ConflictResolution]

/**
 * Evidence that code runs inside [[resolutions]], where `production`, `Production(...)` and `before`/`after` are
 * available. Gives `production` the parser it refers to.
 *
 * @tparam ParserType the parser's singleton type
 */
@implicitNotFound("`production`, `Production(...)`, `before` and `after` can only be used inside resolutions(...)")
opaque type ResolutionScope[ParserType <: parser.Parser[?]] = Unit
object ResolutionScope:
  private[alpaca] def refl[ParserType <: parser.Parser[?]]: ResolutionScope[ParserType] = ()

/**
 * Evidence that code runs inside a parser definition, where `rule`, named productions, and token and rule extractors
 * are available. Every [[Parser]] provides one.
 */
@implicitNotFound("`rule`, named productions, and token and rule extractors can only be used inside a parser definition")
opaque type ParserScope = Unit
object ParserScope:
  private[alpaca] def refl: ParserScope = ()

/**
 * Collects the conflict resolutions for the parser `ParserType`, each written with `before` or `after` (see [[Resolutions]]).
 *
 * @param elements the resolutions; inside them, `production.<name>` refers to `P`'s named productions
 */
def resolutions[ParserType <: parser.Parser[?]](elements: (ResolutionScope[ParserType] ?=> ConflictResolution)*)
  : Resolutions[ParserType] =
  elements.map(_.apply(using ResolutionScope.refl)).toSet

/**
 * Selects one of the parser's named productions by its name: `production.plus` is the production named `"plus"`.
 * Names that are not Scala identifiers go in backticks (`` production.`if then` ``).
 *
 * This is compile-time only and can be used only inside [[resolutions]].
 */
@compileTimeOnly(ConflictResolutionOnly)
transparent inline def production[ParserType <: parser.Parser[?]: ResolutionScope]: ProductionSelector =
  ${ productionImpl[ParserType] }

/**
 * Defines a single production in a grammar rule.
 *
 * A production definition is a partial function that matches a specific pattern of
 * symbols (as a tuple of terminals and non-terminals, or a single lexeme) and produces
 * a result value of type `Value`. Productions are the building blocks of grammar rules,
 * specifying how input sequences are recognized and transformed.
 *
 * Production definitions are typically passed to the [[rule]] function to define
 * the possible ways a non-terminal can be parsed.
 *
 * See the documentation for [[rule]] for more details.
 *
 * @tparam Value the result type produced by this production
 */
type ProductionDefinition[Value] = PartialFunction[Tuple | Lexeme[?, ?], Value]

/**
 * Creates a grammar rule from one or more productions.
 *
 * This is the main way to define grammar rules in the parser DSL. Each production
 * is a partial function that matches a pattern of symbols (terminals and non-terminals)
 * and produces a result value.
 *
 * This is compile-time only and should only be used inside parser class definitions.
 *
 * Example:
 * {{{
 * val Expr: Rule[Int] = rule(
 *   { case (Expr(a), CalcLexer.PLUS(_), CalcLexer.NUMBER(b)) => a + b.value },
 *   { case CalcLexer.NUMBER(n) => n.value },
 * )
 * }}}
 *
 * @tparam Value the result type produced by this rule
 * @param productions one or more productions that define this rule
 * @return a Rule instance
 */
@compileTimeOnly(ParserOnly)
inline def rule[Value](@unused productions: ProductionDefinition[Value]*)(using ParserScope): Rule[Value] =
  null.asInstanceOf[Rule[Value]]

extension (name: String)
  /**
   * Defines a named production for use in grammar rules and conflict resolution.
   *
   * This extension method allows you to assign a name to a specific production within a rule.
   * Named productions can be referenced in conflict resolution rules as `production.<name>`,
   * enabling fine-grained control over precedence and associativity.
   *
   * Usage:
   * {{{
   * val Expr: Rule[Int] = rule(
   *   "plus" { case (Expr(a), CalcLexer.PLUS(_), Expr(b)) => a + b },
   *   { case CalcLexer.NUMBER(n) => n.value },
   * )
   *
   * // In conflict resolution:
   * given Resolutions[CalcParser.type] = resolutions(
   *   production.plus.before(CalcLexer.PLUS),
   * )
   * }}}
   *
   * @param production the production to name
   * @tparam Value the result type produced by this production
   * @return the original production, annotated with the given name
   */
  @compileTimeOnly(ParserOnly)
  inline def apply[Value](production: ProductionDefinition[Value])(using ParserScope): production.type = production

/**
 * The runtime value type of a separator symbol used by `.SeparatedBy`.
 *
 * The parser places `Lexeme` values on the stack for terminals, so when
 * the separator is a token named `name` whose value has type `value` (e.g.
 * `MyLexer.COMMA`), its runtime value is `Lexeme[name, value]`. For a rule separator `Rule[result]` (typically passed as a
 * singleton type like `Sep.type`), the runtime value is `result` — whatever
 * that rule produces.
 *
 * @tparam Separator the separator symbol type (a token type or a rule's `.type`)
 */
type SepValue[Separator] = Separator match
  case Token[name, ?, value] => Lexeme[name, value]
  case Rule[result] => result

/**
 * Represents a grammar rule in the parser.
 *
 * A rule defines how a non-terminal symbol can be parsed by specifying
 * one or more productions. Each production maps a pattern of symbols
 * to a result value.
 *
 * Rules are created using the `rule` function and can be used in pattern
 * matching within parser productions.
 *
 * @tparam Value the type of value produced when this rule is matched
 */
sealed trait Rule[Value]:

  /**
   * Pattern matching extractor for single occurrences of this rule.
   *
   * This is compile-time only and should only be used in parser rule definitions.
   *
   * @param x the value to match
   * @return Some(result) if the match succeeds
   */
  @compileTimeOnly(RuleOnly)
  inline def unapply(@unused x: Any)(using ParserScope): Option[Value] = null.asInstanceOf[Option[Value]]

  /**
   * Pattern matching extractor for lists of this rule.
   *
   * This is compile-time only and should only be used in parser rule definitions.
   *
   * @return a partial function that extracts a list of results
   */
  @compileTimeOnly(RuleOnly)
  inline def List(using ParserScope): PartialFunction[Any, List[Value]] =
    null.asInstanceOf[PartialFunction[Any, List[Value]]]

  /**
   * Pattern matching extractor for optional occurrences of this rule.
   *
   * This is compile-time only and should only be used in parser rule definitions.
   *
   * @return a partial function that extracts an optional result
   */
  @compileTimeOnly(RuleOnly)
  inline def Option(using ParserScope): PartialFunction[Any, Option[Value]] =
    null.asInstanceOf[PartialFunction[Any, Option[Value]]]

  /**
   * Matches zero or more occurrences of this rule delimited by `Separator`,
   * producing a list with separators interleaved.
   *
   * For a token separator, the interleaved values are `Lexeme`s (not the
   * token type itself); for a rule separator, they are values of that rule's
   * result type. See [[SepValue]].
   *
   * @tparam Separator a token type or a rule's `.type`
   */
  @compileTimeOnly(RuleOnly)
  inline def SeparatedBy[Separator](using ParserScope): PartialFunction[Any, List[Value | SepValue[Separator]]] =
    null.asInstanceOf[PartialFunction[Any, List[Value | SepValue[Separator]]]]

/**
 * Why the input does not match the grammar, as reported by `parse` in a [[Result.Failure]].
 *
 * The type member `Fields` is the lexer context's fields, which the lexemes carry. `parse` returns errors with
 * the fields of the lexemes it was given, so positions are the lexer context's fields, e.g.
 * `error.unexpected.map(_.line)`, or `error.last.map(_.line)` at the end of the input. Plain `ParserError` is an error
 * over any lexemes.
 */
sealed abstract class ParserError:
  /** The lexer context's fields, which the lexemes carry. */
  type Fields <: AnyNamedTuple

  /** The lexeme the parser could not accept; `None` when the input ended too early. */
  val unexpected: Option[Lexeme[?, ?] withFields Fields]

  /** What the grammar would have accepted at that point: token names and [[ParserError.EndOfInput]], sorted. */
  val expected: List[String | ParserError.EndOfInput]

  /** At the end of the input, the last lexeme before it; `None` when the input is empty or the error is not at the end. */
  val last: Option[Lexeme[?, ?] withFields Fields]

  def copy(
    unexpected: Option[Lexeme[?, ?] withFields Fields] = unexpected,
    expected: List[String | ParserError.EndOfInput] = expected,
    last: Option[Lexeme[?, ?] withFields Fields] = last,
  ): ParserError withFields Fields = ParserError(unexpected, expected, last)

  /** A readable description, e.g. `Unexpected PLUS "+". Expected one of: Num`. */
  def message: String = {
    def describe(lexeme: Lexeme[?, ?]): Shown = show"""${Printable(lexeme.name)} "${Printable(lexeme.text)}""""

    val what = unexpected match
      case Some(lexeme) => describe(lexeme)
      case None => show"end of input${last.fold(show"")(lexeme => show" after ${describe(lexeme)}")}"
    show"Unexpected $what. Expected one of: ${expected.mkShow(", ")}"
  }

  override def equals(that: Any): Boolean = that match
    case that: ParserError => unexpected == that.unexpected && expected == that.expected && last == that.last
    case _ => false

  override def hashCode: Int = (unexpected, expected, last).##

  override def toString: String = "ParserError" + (unexpected, expected, last).toString

object ParserError:
  private given Showable[String | EndOfInput] =
    case EndOfInput => show"end of input"
    case name: String => Printable(name).show

  private[alpaca] def apply[LexemeFields <: AnyNamedTuple](
    unexpectedLexeme: Option[Lexeme[?, ?] withFields LexemeFields],
    expectedInput: List[String | EndOfInput],
    lastLexeme: Option[Lexeme[?, ?] withFields LexemeFields],
  ): ParserError withFields LexemeFields = new ParserError:
    type Fields = LexemeFields
    val unexpected = unexpectedLexeme
    val expected = expectedInput
    val last = lastLexeme

  def unapply(error: ParserError): (
    Option[Lexeme[?, ?] withFields error.Fields],
    List[String | EndOfInput],
    Option[Lexeme[?, ?] withFields error.Fields],
  ) =
    (error.unexpected, error.expected, error.last)

  /** In [[ParserError.expected]], the end of the input. */
  object EndOfInput:
    override def toString: String = "EndOfInput"

  type EndOfInput = EndOfInput.type

  extension [Ctx, Value](result: Result[Ctx, Value, ParserError])
    /** The value; throws the errors as a [[ParserException]] if parsing failed. */
    def getOrThrow: Value = result match
      case Result.Success(_, value) => value
      case Result.Failure(_, _, errors) => throw ParserException(errors)

/**
 * Thrown by `getOrThrow` on a parse [[Result]] when parsing failed.
 *
 * @param errors the errors the parser reported, in input order
 */
final class ParserException(val errors: ::[ParserError]) extends RuntimeException(errors.map(_.message).mkString("\n"))

/**
 * Base trait for parser global context.
 *
 * Unlike the lexer, the parser's global context is typically empty by default,
 * but can be extended to track custom state during parsing such as symbol tables,
 * type information, or other semantic data.
 */
trait ParserCtx

/**
 * Type representing conflict resolution rules for the parser.
 *
 * Conflict resolutions are used to resolve shift/reduce and reduce/reduce conflicts
 * in the parsing table by specifying precedence relationships between productions
 * and tokens.
 */
type ConflictResolution
extension (@unused inline first: Production | Token[?, ?, ?]) {

  /**
   * Resolves the conflicts between this production or token and each of `others` in favour of `others`: the
   * reverse of [[before]]. `production.plus.after(CalcLexer.TIMES)` shifts `*` instead of reducing `plus`, so `*`
   * binds tighter than `+`.
   *
   * This is compile-time only and can be used only inside [[resolutions]].
   *
   * @param others the productions and tokens that win over this one
   * @return a conflict resolution rule
   */
  @compileTimeOnly(ConflictResolutionOnly)
  inline infix def after[ParserType <: parser.Parser[?]: ResolutionScope](
    @unused inline others: (Production | Token[?, ?, ?])*,
  ): ConflictResolution =
    null.asInstanceOf[ConflictResolution]

  /**
   * Resolves the conflicts between this production or token and each of `others` in favour of this one: a production
   * is reduced rather than shifting a token of `others` or reducing a production of `others`; a token is shifted
   * rather than reducing a production of `others`. `production.plus.before(CalcLexer.PLUS)` reduces `1 + 2` before
   * shifting the next `+`, so `+` is left-associative.
   *
   * This is compile-time only and can be used only inside [[resolutions]].
   *
   * @param others the productions and tokens this one wins over
   * @return a conflict resolution rule
   */
  @compileTimeOnly(ConflictResolutionOnly)
  inline infix def before[ParserType <: parser.Parser[?]: ResolutionScope](
    @unused inline others: (Production | Token[?, ?, ?])*,
  ): ConflictResolution =
    null.asInstanceOf[ConflictResolution]
}

object ParserCtx:

  /** Default error handler for any [[ParserCtx]]: stop at the first input that does not match the grammar. */
  given defaultErrorHandling: ErrorHandling[ParserCtx, ParserError] = (_, _) => ErrorHandling.Strategy.Stop

  /**
   * An empty parser context with no state.
   *
   * This is the default context used by parsers when no custom context
   * is needed. Most simple parsers can use this.
   */
  final case class Empty(
  ) extends ParserCtx

extension [Ctx <: ParserCtx](parser: Parser[Ctx]) {

  /**
   * Parses a list of lexemes using the defined grammar.
   *
   * The result type is inferred from the root rule. Input that does not match the grammar is not thrown as an
   * exception: it comes back as a [[Result.Failure]] listing the [[ParserError]]s.
   *
   * @tparam LexemeFields the lexer context's fields, which the lexemes carry; the errors carry them too, so
   *                      `error.unexpected.map(_.line)` compiles when they have a `line`
   * @param lexemes the list of lexemes to parse
   * @return the value the root rule produced, or the errors that stopped the parser, with the context either way
   */
  inline def parse[LexemeFields <: AnyNamedTuple](lexemes: List[Lexeme[?, ?] withFields LexemeFields]): Result[
    Ctx,
    parser.root.type match { case Rule[result] => result },
    ParserError withFields LexemeFields,
  ] = parser.parseResult(lexemes)
}
