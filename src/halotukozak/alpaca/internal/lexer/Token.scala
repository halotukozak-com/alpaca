package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.internal.{Default, Printable, RuleOnly, Showable, ValidName}
import halotukozak.alpaca.{LexerCtx, SepValue}
import halotukozak.mcodec.MCodec
import halotukozak.regex.{RegexParseError, RegexParser}

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.unchecked.uncheckedVariance as uv
import scala.annotation.{compileTimeOnly, publicInBinary, unused}
import scala.quoted.{Quotes, ToExprFactory}

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
   * This validates the name and constructs an expression that will
   * create a TokenInfo at runtime. An invalid name or pattern is reported as an error without aborting,
   * so the lexer is still typed from the rest of its cases.
   *
   * @param name the token name
   * @param alternatives the regex patterns, joined with `|` into the token's pattern
   * @param ignored whether matches of this token are dropped from the lexeme stream
   * @param quotes the Quotes instance
   * @return a TokenInfo expression, together with the pattern's already-parsed [[Regex]] so
   *         callers don't have to parse it again (`None` if the pattern is invalid), or `None` if the name is invalid
   */
// $COVERAGE-OFF$
  def apply(using
    Quotes,
    Diagnostics,
  )(
    name: String,
    alternatives: List[String],
    ignored: Boolean,
    pos: quotes.reflect.Position,
  ): Option[CompiledPattern] =
    import quotes.reflect.*
    ValidName(name, pos)
      .map: validName =>
        val pattern = alternatives.mkString("|")
        def reportInvalid(err: RegexParseError): Unit =
          error(show"""Invalid regex pattern for token "${Printable(validName)}": $err""", pos)
        // An alternative can be invalid on its own and still parse once joined, e.g. "(" | ")".
        val invalidAlternatives = alternatives match
          case _ :: Nil => Nil
          case _ => alternatives.map(RegexParser.parse).collect { case Left(err) => err }
        invalidAlternatives.foreach(reportInvalid)
        val regex = Option
          .when(invalidAlternatives.isEmpty)(RegexParser.parse(pattern))
          .flatMap:
            case Right(regex) => Some(regex)
            case Left(err) => reportInvalid(err); None
        (
          tokenType = ConstantType(StringConstant(validName)).asType.asInstanceOf[Type[? <: ValidName]],
          info = TokenInfo(Printable(validName), nextRegexGroupName(), Printable(pattern), ignored),
          regex = regex,
        )

  /**
   * Generates a unique name for a regex capture group.
   *
   * @return a unique token group name
   */
  private def nextRegexGroupName(): String = s"token${counter.getAndIncrement()}"

  given Default[TokenInfo] = () => TokenInfo(Printable(""), "", Printable(""), ignored = false)

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
  inline def unapply(@unused x: Any)(using ParserScope): Option[LexemeTpe] = null.asInstanceOf[Option[LexemeTpe]]
  @compileTimeOnly(RuleOnly)
  inline def List(using ParserScope): PartialFunction[Any, List[LexemeTpe]] =
    null.asInstanceOf[PartialFunction[Any, List[LexemeTpe]]]
  @compileTimeOnly(RuleOnly)
  inline def Option(using ParserScope): PartialFunction[Any, Option[LexemeTpe]] =
    null.asInstanceOf[PartialFunction[Any, Option[LexemeTpe]]]
  @compileTimeOnly(RuleOnly)
  inline def SeparatedBy[Separator](using ParserScope): PartialFunction[Any, List[LexemeTpe | SepValue[Separator]]] =
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
    identity,
  )

@publicInBinary private[alpaca] object DefinedToken

@publicInBinary private[alpaca] object IgnoredToken
