package halotukozak
package alpaca

import halotukozak.alpaca.internal.*
import halotukozak.alpaca.internal.lexer.{Lexeme, Token}
import halotukozak.alpaca.internal.parser.*

import scala.annotation.{compileTimeOnly, unused}

type Parser[Ctx <: ParserCtx] = parser.Parser[Ctx]

opaque type Resolutions[P <: parser.Parser[?]] = Set[ConflictResolution]

sealed trait ResolutionCtx[P <: parser.Parser[?]]
object ResolutionCtx:
  private val reusable = new ResolutionCtx[parser.Parser[?]] {}
  private[alpaca] def refl[P <: parser.Parser[?]]: ResolutionCtx[P] = reusable.asInstanceOf[ResolutionCtx[P]]
def resolutions[P <: parser.Parser[?]](elements: (ResolutionCtx[P] ?=> ConflictResolution)*): Resolutions[P] =
  elements.map(_.apply(using ResolutionCtx.refl)).toSet

@compileTimeOnly(ConflictResolutionOnly)
transparent inline def production[P <: parser.Parser[?]](using ResolutionCtx[P]): ProductionSelector =
  ${ productionImpl[P] }

/**
 * Defines a single production in a grammar rule.
 *
 * A production definition is a partial function that matches a specific pattern of
 * symbols (as a tuple of terminals and non-terminals, or a single lexeme) and produces
 * a result value of type `R`. Productions are the building blocks of grammar rules,
 * specifying how input sequences are recognized and transformed.
 *
 * Production definitions are typically passed to the [[rule]] function to define
 * the possible ways a non-terminal can be parsed.
 *
 * See the documentation for [[rule]] for more details.
 *
 * @tparam R the result type produced by this production
 */
type ProductionDefinition[R] = PartialFunction[Tuple | Lexeme[?, ?], R]

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
 * val expr: Rule[Int] = rule(
 *   { case (number(a), Lexer.+(_), number(b)) => a.toInt + b.toInt },
 *   { case (number(n)) => n.toInt }
 * )
 * }}}
 *
 * @tparam R the result type produced by this rule
 * @param productions one or more productions that define this rule
 * @return a Rule instance
 */
@compileTimeOnly(ParserOnly)
inline def rule[R](@unused productions: ProductionDefinition[R]*): Rule[R] = null.asInstanceOf[Rule[R]]

extension (name: String)
  /**
   * Defines a named production for use in grammar rules and conflict resolution.
   *
   * This extension method allows you to assign a name to a specific production within a rule.
   * Named productions can be referenced in conflict resolution rules using the `Production` selector,
   * enabling fine-grained control over precedence and associativity.
   *
   * Usage:
   * {{{
   * val add: Rule[Int] = rule(
   *   "sum" { case (number(a), Lexer.+(_), number(b)) => a.toInt + b.toInt },
   *   { case (number(n)) => n.toInt }
   * )
   *
   * // In conflict resolution:
   * given Resolutions[MyParser.type] = resolutions(
   *   production.sum.after(Lexer.+),
   * )
   * }}}
   *
   * @param production the production to name
   * @tparam R the result type produced by this production
   * @return the original production, annotated with the given name
   */
  @compileTimeOnly(ParserOnly)
  inline def apply[R](production: ProductionDefinition[R]): production.type = production

/**
 * The runtime value type of a separator symbol used by `.SeparatedBy`.
 *
 * The parser places `Lexeme` values on the stack for terminals, so when
 * the separator is a token type `Token[n, ?, v]`, its runtime value is
 * `Lexeme[n, v]`. For a rule separator `Rule[t]` (typically passed as a
 * singleton type like `Sep.type`), the runtime value is `t` — whatever
 * that rule produces.
 *
 * @tparam S the separator symbol type (a token type or a rule's `.type`)
 */
type SepValue[S] = S match
  case Token[n, ?, v] => Lexeme[n, v]
  case Rule[t] => t

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
 * @tparam R the type of value produced when this rule is matched
 */
trait Rule[R]:

  /**
   * Pattern matching extractor for single occurrences of this rule.
   *
   * This is compile-time only and should only be used in parser rule definitions.
   *
   * @param x the value to match
   * @return Some(result) if the match succeeds
   */
  @compileTimeOnly(RuleOnly)
  inline def unapply(@unused x: Any): Option[R] = null.asInstanceOf[Option[R]]

  /**
   * Pattern matching extractor for lists of this rule.
   *
   * This is compile-time only and should only be used in parser rule definitions.
   *
   * @return a partial function that extracts a list of results
   */
  @compileTimeOnly(RuleOnly)
  inline def List: PartialFunction[Any, List[R]] = null.asInstanceOf[PartialFunction[Any, List[R]]]

  /**
   * Pattern matching extractor for optional occurrences of this rule.
   *
   * This is compile-time only and should only be used in parser rule definitions.
   *
   * @return a partial function that extracts an optional result
   */
  @compileTimeOnly(RuleOnly)
  inline def Option: PartialFunction[Any, Option[R]] = null.asInstanceOf[PartialFunction[Any, Option[R]]]

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
  inline def SeparatedBy[Separator]: PartialFunction[Any, List[R | SepValue[Separator]]] =
    null.asInstanceOf[PartialFunction[Any, List[R | SepValue[Separator]]]]

/**
 * Why the input does not match the grammar, as reported by `parse` in a [[ParseResult.Failure]]. It is an
 * `Exception` so that [[ParseResult.getOrThrow]] can throw it.
 *
 * @param unexpected the lexeme the parser could not accept; its `name` is `"$"` when the input ended too early
 * @param expected   the token names the grammar would have accepted at that point (`"$"` stands for the end of
 *                   the input), sorted
 */
final class ParseError(val unexpected: Lexeme[?, ?], val expected: List[String])
  extends Exception(ParseError.message(unexpected, expected))

object ParseError:
  private def message(unexpected: Lexeme[?, ?], expected: List[String]): String = {
    def field(name: String): Option[Int] =
      unexpected.fieldNames.indexOf(name) match
        case -1 => None
        case i =>
          unexpected.fieldValues(i) match
            case n: Int => Some(n)
            case _ => None

    val name: String = unexpected.name
    val what =
      if name == "$" then "end of input"
      else s"""$name "${unexpected.text}""""
    // `position` is recorded after the match, so the token itself starts `text.length` earlier.
    val where = (field("line"), field("position")) match
      case (Some(line), Some(position)) => s" at line $line, column ${position - unexpected.text.length}"
      case (Some(line), None) => s" at line $line"
      case (None, Some(position)) => s" at column ${position - unexpected.text.length}"
      case (None, None) => ""
    val accepted = expected.map(name => if name == "$" then "end of input" else name).mkString(", ")
    s"Unexpected $what$where. Expected one of: $accepted"
  }

/**
 * The outcome of [[parse]]: either the value the root rule produced, or the errors that stopped the parser.
 *
 * Both cases carry the parser context as it was when parsing ended.
 *
 * {{{
 * MyParser.parse(lexemes) match
 *   case ParseResult.Success(ctx, value) => println(value)
 *   case ParseResult.Failure(ctx, errors) => errors.foreach(e => println(e.getMessage))
 * }}}
 *
 * @tparam Ctx the parser context type
 * @tparam R   the type the root rule produces
 */
enum ParseResult[+Ctx, +R]:
  /** The parser context as it was when parsing ended. */
  def ctx: Ctx

  /** The input matched the grammar and the root rule produced `value`. */
  case Success(ctx: Ctx, value: R)

  /** The input did not match the grammar; `errors` says where and what was expected instead. */
  case Failure(ctx: Ctx, errors: ::[ParseError])

  /** The produced value, or `None` if parsing failed. */
  def toOption: Option[R] = this match
    case Success(_, value) => Some(value)
    case Failure(_, _) => None

  /** The produced value on the right, or the errors on the left. */
  def toEither: Either[::[ParseError], R] = this match
    case Success(_, value) => Right(value)
    case Failure(_, errors) => Left(errors)

  /** The produced value; throws the first [[ParseError]] if parsing failed. */
  def getOrThrow: R = this match
    case Success(_, value) => value
    case Failure(_, errors) => throw errors.head

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
extension (first: Production | Token[?, ?, ?]) {

  /**
   * Specifies that this production/token should have higher precedence than others.
   *
   * This is compile-time only and should only be used inside parser rule definitions.
   *
   * Example:
   * {{{
   * Production(expr, "*", expr) after Production(expr, "+", expr)
   * }}}
   *
   * @param second the productions/tokens that should have lower precedence
   * @return a conflict resolution rule
   */
  @compileTimeOnly(RuleOnly)
  inline infix def after(@unused second: (Production | Token[?, ?, ?])*): ConflictResolution =
    null.asInstanceOf[ConflictResolution]

  /**
   * Specifies that this production/token should have lower precedence than others.
   *
   * This is compile-time only and should only be used inside parser rule definitions.
   *
   * Example:
   * {{{
   * Production(expr, "+", expr) before Production(expr, "*", expr)
   * }}}
   *
   * @param second the productions/tokens that should have higher precedence
   * @return a conflict resolution rule
   */
  @compileTimeOnly(RuleOnly)
  inline infix def before(@unused second: (Production | Token[?, ?, ?])*): ConflictResolution =
    null.asInstanceOf[ConflictResolution]
}

object Production:

  /**
   * Creates a production reference from symbols.
   *
   * This is compile-time only and used in conflict resolution definitions
   * to refer to productions by their right-hand side.
   *
   * @param symbols the symbols on the right-hand side of the production
   * @return a production reference
   */
  @compileTimeOnly(ConflictResolutionOnly)
  inline def apply(@unused symbols: (Rule[?] | Token[?, ?, ?])*): Production = null.asInstanceOf[Production]

object ParserCtx:

  /**
   * An empty parser context with no state.
   *
   * This is the default context used by parsers when no custom context
   * is needed. Most simple parsers can use this. It holds no state, so one
   * instance is shared by every parse.
   */
  case object Empty extends ParserCtx

  /** The type of [[Empty]], so it can be named like any other context type, e.g. `Parser[ParserCtx.Empty]`. */
  type Empty = Empty.type

extension [Ctx <: ParserCtx](parser: Parser[Ctx]) {

  /**
   * Parses a list of lexemes using the defined grammar.
   *
   * The result type is inferred from the root rule. Input that does not match the grammar is not thrown as an
   * exception: it comes back as a [[ParseResult.Failure]] listing the [[ParseError]]s.
   *
   * @param lexems the list of lexemes to parse
   * @return the value the root rule produced, or the errors that stopped the parser, with the context either way
   */
  inline def parse(lexems: List[Lexeme[?, ?]]): ParseResult[
    Ctx,
    parser.root.type match
      case Rule[t] => t,
  ] = parser.parseResult(lexems)
}
