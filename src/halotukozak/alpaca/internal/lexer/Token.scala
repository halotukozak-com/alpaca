package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.internal.{Printable, RuleOnly, Showable, ValidName}
import halotukozak.alpaca.{LexerCtx, SepValue}
import halotukozak.mcodec.MCodec
import halotukozak.regex.{Regex, RegexParseError, RegexParser, TokenMatcher}

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.unchecked.uncheckedVariance as uv
import scala.annotation.{compileTimeOnly, publicInBinary, unused}
import scala.quoted.*

/**
 * Type alias for context manipulation functions.
 *
 * These functions are used to update the lexer context as tokens are matched.
 * The updated context is returned rather than mutated in place, so that user
 * contexts can be immutable `case class`es: the `lexer` macro rewrites every
 * `ctx.field = ...` / `ctx.field += ...` inside a rule into a `copy` and threads
 * the result through here. Contexts that still declare `var` fields keep working
 * unchanged (the assignment mutates in place and the same instance is returned).
 *
 * @tparam Ctx the global context type
 */
private[lexer] type CtxManipulation[Ctx <: LexerCtx] = Ctx => Ctx

/**
 * Information about a token definition.
 *
 * Contains the token's name, pattern, a unique group name for regex matching, and whether
 * matches are dropped from the lexeme stream.
 *
 * @param name the token name
 * @param regexGroupName a unique name for the regex capture group
 * @param pattern the regex pattern that matches this token
 * @param ignored whether matches of this token are dropped from the lexeme stream
 */
private[lexer] final case class TokenInfo(
  name: Printable,
  regexGroupName: String,
  pattern: Printable,
  ignored: Boolean,
) derives ToExprFactory

private[lexer] object TokenInfo:
  private val counter = AtomicInteger(0)

  /**
   * Creates a TokenInfo expression from a name and the alternatives of its regex pattern.
   *
   * This constructs an expression that will
   * create a TokenInfo at runtime. An invalid pattern is reported as an error without aborting,
   * so the lexer is still typed from all of its cases.
   *
   * @param name the token name, already validated
   * @param alternatives the regex patterns, joined with `|` into the token's pattern
   * @param ignored whether matches of this token are dropped from the lexeme stream
   * @param quotes the Quotes instance
   * @return a TokenInfo expression, together with the pattern's already-parsed [[Regex]] so
   *         callers don't have to parse it again, or `None` if the pattern is invalid
   */
// $COVERAGE-OFF$
  def apply(using
    Quotes,
    Diagnostics,
  )(
    name: ValidName,
    alternatives: List[String],
    ignored: Boolean,
    pos: quotes.reflect.Position,
  ): CompiledPattern = {
    import quotes.reflect.*
    val pattern = alternatives.mkString("|")
    def reportInvalid[T: Default](err: RegexParseError): T =
      error(show"""Invalid regex pattern for token "${Printable(name)}": $err""", pos)

    extension [L, R](either: Either[L, R]) def onLeft(op: L => Unit): either.type = either.tap(_.left.foreach(op))

    val hasValidAlternatives = alternatives match
      case _ :: Nil => true
      case alternatives =>
        // a strict List, so that every invalid alternative is reported before `forall` stops at the first
        val parsed = for alternative <- alternatives yield RegexParser.parse(alternative).onLeft(reportInvalid)
        parsed.forall(_.isRight)

    val regex: Option[Regex] = if hasValidAlternatives then
      RegexParser
        .parse(pattern)
        .onLeft(reportInvalid)
        .toOption
        .filter: regex =>
          if regex.nullable then
            error(
              //format: off
              show"""Token "${Printable(name)}" can match the empty string ("${Printable(pattern)}"); a token must consume at least one character""",
              //format: on
              pos,
            )
          // the lexer matches each token at the start of the rest of the input, so `^` would hold at every token
          else if regex.hasStartAnchor then
            error(
              //format: off
              show"""Token "${Printable(name)}" uses a start-of-input anchor `^`/`\\A` ("${Printable(pattern)}"), which a token pattern can't: each token is matched where the previous one ended""",
              //format: on
              pos,
            )
          else
            TokenMatcher.unsupportedConstructs(regex) match
              case unsupported :: _ =>
                error(
                  //format: off
                  show"""Token "${Printable(name)}" uses ${unsupported.construct.showRaw} ("${Printable(pattern)}"), which a token pattern can't: a token only sees the text it matches""",
                  //format: on
                  pos,
                )
              case Nil => true
    else None

    (
      tokenType = ConstantType(StringConstant(name)).asType.asInstanceOf[Type[? <: ValidName]],
      info = TokenInfo(Printable(name), nextRegexGroupName(), Printable(pattern), ignored),
      regex = regex,
    )
  }

  /**
   * Generates a unique name for a regex capture group.
   *
   * @return a unique token group name
   */
  private def nextRegexGroupName(): String = s"token${counter.getAndIncrement()}"

  given Showable[TokenInfo] = Showable.fromToString

  // Excludes regexGroupName, an internal-only detail with no meaning to the export's consumer.
  given MCodec[(info: TokenInfo, source: Source)] =
    MCodec
      .derived[(name: Printable, pattern: Printable, ignored: Boolean, source: Source)]
      .transform(
        onWrite = { case (TokenInfo(name, _, pattern, ignored), source) =>
          (name = name, pattern = pattern, ignored = ignored, source = source)
        },
        onRead = _ => throw UnsupportedOperationException("TokenInfo's export codec is write-only"),
      )
// $COVERAGE-ON$
/**
 * Base trait for all token types.
 *
 * A token represents a lexical unit matched by the lexer. It contains information
 * about the token's name, pattern, and how to manipulate the lexer context when matched.
 *
 * @tparam Name the token name type
 * @tparam Ctx the global context type
 * @tparam Value the value type extracted from the matched text
 */
sealed trait Token[+Name <: ValidName, -Ctx <: LexerCtx, +Value]:

  /** Token information including name and pattern. */
  @publicInBinary
  private[alpaca] val info: TokenInfo

  /** Function to update the context when this token is matched. */
  private[lexer] val ctxManipulation: CtxManipulation[Ctx @uv]

private[alpaca] final case class DefinedToken[
  Name <: ValidName,
  -Ctx <: LexerCtx,
  +Value,
  +LexemeTpe <: Lexeme[Name, Value],
](
  @publicInBinary private[alpaca] info: TokenInfo,
  private[lexer] ctxManipulation: CtxManipulation[Ctx @uv],
  private[lexer] remapping: Ctx => Value,
) extends Token[Name, Ctx, Value]:

  @compileTimeOnly(RuleOnly)
  inline def unapply(@unused x: Any)(using @unused scope: ProductionScope): Option[LexemeTpe] =
    null.asInstanceOf[Option[LexemeTpe]]
  @compileTimeOnly(RuleOnly)
  inline def List(using @unused scope: ProductionScope): PartialFunction[Any, List[LexemeTpe]] =
    null.asInstanceOf[PartialFunction[Any, List[LexemeTpe]]]
  @compileTimeOnly(RuleOnly)
  inline def Option(using @unused scope: ProductionScope): PartialFunction[Any, Option[LexemeTpe]] =
    null.asInstanceOf[PartialFunction[Any, Option[LexemeTpe]]]
  @compileTimeOnly(RuleOnly)
  inline def SeparatedBy[Separator](using @unused scope: ProductionScope)
    : PartialFunction[Any, List[LexemeTpe | SepValue[Separator]]] =
    null.asInstanceOf[PartialFunction[Any, List[LexemeTpe | SepValue[Separator]]]]

/**
 * A token that is matched but not included in the output.
 *
 * Ignored tokens are useful for whitespace, comments, and other lexical
 * elements that should be recognized but not passed to the parser.
 *
 * @tparam Name the token name type
 * @tparam Ctx the global context type
 * @param info token information
 * @param ctxManipulation function to update context
 */
private[alpaca] final case class IgnoredToken[Name <: ValidName, -Ctx <: LexerCtx](
  @publicInBinary private[alpaca] info: TokenInfo,
  private[lexer] ctxManipulation: CtxManipulation[Ctx @uv],
) extends Token[Name, Ctx, Nothing]

private[alpaca] def RecoveredToken[Ctx <: LexerCtx](matched: String): IgnoredToken[matched.type, Ctx] =
  IgnoredToken(
    TokenInfo(Printable(matched), s"<unrecognized \"$matched\">", Printable(matched), ignored = true),
    unchangedCtx,
  )

// Function values shared by generated tokens, so the lexer macro doesn't emit the same lambda for every token.
private val identityFunction: Any => Any = identity

@publicInBinary private[alpaca] def unchangedCtx[Ctx <: LexerCtx]: CtxManipulation[Ctx] =
  identityFunction.asInstanceOf[CtxManipulation[Ctx]]
@publicInBinary private[alpaca] val discard: LexerCtx => Unit = _ => ()
@publicInBinary private[alpaca] val rawMatched: LexerCtx => String = _.lastRawMatched

@publicInBinary private[alpaca] object DefinedToken

@publicInBinary private[alpaca] object IgnoredToken
