package halotukozak
package alpaca

import halotukozak.alpaca.internal.*
import halotukozak.alpaca.internal.lexer.{Lexeme, Token}
import halotukozak.alpaca.internal.parser.*

import scala.annotation.{compileTimeOnly, implicitNotFound, unused}

type Parser[Ctx <: ParserCtx] = parser.Parser[Ctx]

/**
 * How the parser `P` resolves the conflicts in its grammar. Provide one as a `given` next to the parser object (before or
 * after it) or as the object's last member, built with [[resolutions]]:
 * {{{
 * given Resolutions[CalcParser.type] = resolutions(
 *   production.plus.before(CalcLexer.PLUS), // + is left-associative
 * )
 * }}}
 *
 * @tparam P the parser's singleton type
 */
opaque type Resolutions[P <: parser.Parser[?]] = Set[ConflictResolution]

/**
 * Evidence that code runs inside [[resolutions]], where `production`, `Production(...)` and `before`/`after` are
 * available. Gives `production` the parser it refers to.
 *
 * @tparam P the parser's singleton type
 */
@implicitNotFound("`production`, `Production(...)`, `before` and `after` can only be used inside resolutions(...)")
sealed trait ResolutionScope[P <: parser.Parser[?]]
object ResolutionScope:
  private val reusable = new ResolutionScope[parser.Parser[?]] {}
  private[alpaca] def refl[P <: parser.Parser[?]]: ResolutionScope[P] = reusable.asInstanceOf[ResolutionScope[P]]

/**
 * Evidence that code runs inside a parser definition, where `rule`, named productions, and token and rule extractors
 * are available. Every [[Parser]] provides one.
 */
@implicitNotFound("`rule`, named productions, and token and rule extractors can only be used inside a parser definition")
final class ParserScope private[alpaca] ()
object ParserScope:
  private[alpaca] val instance: ParserScope = new ParserScope

/**
 * Collects the conflict resolutions for the parser `P`, each written with `before` or `after` (see [[Resolutions]]).
 *
 * @param elements the resolutions; inside them, `production.<name>` refers to `P`'s named productions
 */
def resolutions[P <: parser.Parser[?]](elements: (ResolutionScope[P] ?=> ConflictResolution)*): Resolutions[P] =
  elements.map(_.apply(using ResolutionScope.refl)).toSet

/**
 * Selects one of the parser's named productions by its name: `production.plus` is the production named `"plus"`.
 * Names that are not Scala identifiers go in backticks (`` production.`if then` ``).
 *
 * This is compile-time only and can be used only inside [[resolutions]].
 */
@compileTimeOnly(ConflictResolutionOnly)
transparent inline def production[P <: parser.Parser[?]](using ResolutionScope[P]): ProductionSelector =
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
 * val Expr: Rule[Int] = rule(
 *   { case (Expr(a), CalcLexer.PLUS(_), CalcLexer.NUMBER(b)) => a + b.value },
 *   { case CalcLexer.NUMBER(n) => n.value },
 * )
 * }}}
 *
 * @tparam R the result type produced by this rule
 * @param productions one or more productions that define this rule
 * @return a Rule instance
 */
@compileTimeOnly(ParserOnly)
inline def rule[R](@unused productions: ProductionDefinition[R]*)(using ParserScope): Rule[R] = null.asInstanceOf[Rule[R]]

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
   * @tparam R the result type produced by this production
   * @return the original production, annotated with the given name
   */
  @compileTimeOnly(ParserOnly)
  inline def apply[R](production: ProductionDefinition[R])(using ParserScope): production.type = production

/**
 * The runtime value type of a separator symbol used by `.SeparatedBy`.
 *
 * The parser places `Lexeme` values on the stack for terminals, so when
 * the separator is a token named `n` whose value has type `v` (e.g.
 * `MyLexer.COMMA`), its runtime value is `Lexeme[n, v]`. For a rule separator `Rule[t]` (typically passed as a
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
  inline def unapply(@unused x: Any)(using ParserScope): Option[R] = null.asInstanceOf[Option[R]]

  /**
   * Pattern matching extractor for lists of this rule.
   *
   * This is compile-time only and should only be used in parser rule definitions.
   *
   * @return a partial function that extracts a list of results
   */
  @compileTimeOnly(RuleOnly)
  inline def List(using ParserScope): PartialFunction[Any, List[R]] = null.asInstanceOf[PartialFunction[Any, List[R]]]

  /**
   * Pattern matching extractor for optional occurrences of this rule.
   *
   * This is compile-time only and should only be used in parser rule definitions.
   *
   * @return a partial function that extracts an optional result
   */
  @compileTimeOnly(RuleOnly)
  inline def Option(using ParserScope): PartialFunction[Any, Option[R]] = null.asInstanceOf[PartialFunction[Any, Option[R]]]

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
  inline def SeparatedBy[Separator](using ParserScope): PartialFunction[Any, List[R | SepValue[Separator]]] =
    null.asInstanceOf[PartialFunction[Any, List[R | SepValue[Separator]]]]

/**
 * Why the input does not match the grammar, as reported by `parse` in a [[Result.Failure]].
 *
 * @param unexpected the lexeme the parser could not accept; its `name` is `"$"` when the input ended too early
 * @param expected   the token names the grammar would have accepted at that point (`"$"` stands for the end of
 *                   the input), sorted
 */
final case class ParserError(unexpected: Lexeme[?, ?], expected: List[String]):
  /** A readable description, e.g. `Unexpected + "+" at line 1, column 3. Expected one of: Num`. */
  def message: String = {
    def field(name: String): Option[Int] =
      unexpected.fieldNames.indexOf(name) match
        case -1 => None
        case i =>
          unexpected.fieldValues(i) match
            case n: Int => Some(n)
            case _ => None

    def describe(name: String): Shown = if name == "$" then show"end of input" else Printable(name).show

    val what =
      if unexpected.name == "$" then describe(unexpected.name)
      else show"""${describe(unexpected.name)} "${Printable(unexpected.text)}""""
    // `position` is recorded after the match, so the token itself starts `text.length` earlier.
    val where = (field("line"), field("position")) match
      case (Some(line), Some(position)) => show" at line $line, column ${position - unexpected.text.length}"
      case (Some(line), None) => show" at line $line"
      case (None, Some(position)) => show" at column ${position - unexpected.text.length}"
      case (None, None) => show""
    show"Unexpected $what$where. Expected one of: ${expected.map(describe).mkShow(", ")}"
  }

object ParserError:
  extension [Ctx, A](result: Result[Ctx, A, ParserError])
    /** The value; throws the errors as a [[ParserException]] if parsing failed. */
    def getOrThrow: A = result match
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
extension (first: Production | Token[?, ?, ?]) {

  /**
   * Resolves the conflicts between this production or token and each of `second` in favour of `second`: the
   * reverse of [[before]]. `production.plus.after(CalcLexer.TIMES)` shifts `*` instead of reducing `plus`, so `*`
   * binds tighter than `+`.
   *
   * This is compile-time only and can be used only inside [[resolutions]].
   *
   * @param second the productions and tokens that win over this one
   * @return a conflict resolution rule
   */
  @compileTimeOnly(RuleOnly)
  inline infix def after(@unused second: (Production | Token[?, ?, ?])*)(using ResolutionScope[?]): ConflictResolution =
    null.asInstanceOf[ConflictResolution]

  /**
   * Resolves the conflicts between this production or token and each of `second` in favour of this one: a production
   * is reduced rather than shifting a token of `second` or reducing a production of `second`; a token is shifted
   * rather than reducing a production of `second`. `production.plus.before(CalcLexer.PLUS)` reduces `1 + 2` before
   * shifting the next `+`, so `+` is left-associative.
   *
   * This is compile-time only and can be used only inside [[resolutions]].
   *
   * @param second the productions and tokens this one wins over
   * @return a conflict resolution rule
   */
  @compileTimeOnly(RuleOnly)
  inline infix def before(@unused second: (Production | Token[?, ?, ?])*)(using ResolutionScope[?]): ConflictResolution =
    null.asInstanceOf[ConflictResolution]
}

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
  inline def apply(@unused symbols: (Rule[?] | Token[?, ?, ?])*)(using ResolutionScope[?]): Production = null.asInstanceOf[Production]

object ParserCtx:

  /** Default error handler for any [[ParserCtx]]: stop at the first input that does not match the grammar. */
  given ErrorHandling[ParserCtx, ParserError] = (_, _) => ErrorHandling.Strategy.Stop

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
   * @param lexemes the list of lexemes to parse
   * @return the value the root rule produced, or the errors that stopped the parser, with the context either way
   */
  inline def parse(lexemes: List[Lexeme[?, ?]]): Result[
    Ctx,
    parser.root.type match { case Rule[t] => t },
    ParserError,
  ] = parser.parseResult(lexemes)
}
