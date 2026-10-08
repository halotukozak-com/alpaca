package halotukozak
package alpaca

import alpaca.internal.*
import alpaca.internal.lexer.{IgnoredToken as _, Token as _, *}

import scala.NamedTuple.{AnyNamedTuple, NamedTuple}
import scala.annotation.{compileTimeOnly, publicInBinary, unused}

/**
 * Public re-exports of lexer types users are expected to reference directly
 *  (lexer and lexeme types, custom `given` instances, tracking traits, large-file tokenization) even
 *  though they are implemented under `internal.lexer`.
 */
export alpaca.internal.lexer.{Column, LazyReader, Lexeme, Lexer, Line, Tracking}

/**
 * Creates a lexer from a DSL-based definition.
 *
 * This is the main entry point for defining a lexer. It uses a macro to
 * compile the lexer definition into efficient tokenization code.
 *
 * Example:
 * {{{
 * val myLexer = lexer {
 *   case "\\d+" => Token["number"]
 *   case "[a-zA-Z]+" => Token["identifier"]
 *   case "\\s+" => Token.Ignored
 * }
 * }}}
 *
 * @tparam Ctx the global context type, defaults to [[LexerCtx.Default]]
 * @param rules the lexer rules as a partial function
 * @param errorHandling implicit ErrorHandling for custom error recovery
 * @return a [[Lexer]] that can tokenize input strings
 */
transparent inline def lexer[Ctx <: LexerCtx](
  using Ctx withDefault LexerCtx.Default,
)(
  inline rules: LexerScope.Of[Ctx] ?=> LexerDefinition[Ctx],
)(using
  m: Mirror.ProductOf[Ctx],
  errorHandling: ErrorHandling[Ctx, LexerError.Of[Ctx]],
): Lexer[Ctx] { type LexemeFields = NamedTuple[m.MirroredElemLabels, m.MirroredElemTypes] } =
  ${
    createLexerImpl[Ctx, NamedTuple[m.MirroredElemLabels, m.MirroredElemTypes]](
      '{ rules },
      '{ Tracking.materialize[Ctx] },
      '{ errorHandling },
    )
  }

/**
 * What `Token["NAME"]` and `Token["NAME"](value)` return inside a `lexer` block: a marker the `lexer` macro reads to
 * define a token. It exists only at compile time and cannot be created or extended outside the library.
 *
 * The token a lexer defines is read as `MyLexer.NAME`, and its type is named the same way: `val t: MyLexer.NAME =
 * MyLexer.NAME`. In a parser, `MyLexer.NAME(lexeme)` matches one; as a type, a token can be a `SeparatedBy` separator,
 * and in `resolutions(...)` it can be ordered against productions.
 *
 * @tparam Name  the token's name
 * @tparam Ctx   the lexer context type
 * @tparam Value the type of the value its lexemes carry
 */
sealed class Token[+Name <: ValidName, +Ctx <: LexerCtx, +Value] private[alpaca] ()

/**
 * What `Token.Ignored` returns inside a `lexer` block: its matches are consumed but produce no lexeme. Use it for
 * whitespace, comments and anything else the parser should not see.
 *
 * @tparam Ctx the lexer context type
 */
final class IgnoredToken[+Ctx <: LexerCtx] private[alpaca] () extends Token[ValidName, Ctx, Nothing]

/** Factory methods for creating token definitions in the lexer DSL. */
object Token:

  /**
   * Creates an ignored token that will be matched but not included in the output.
   *
   * This is compile-time only and should only be used inside lexer definitions.
   *
   * @return a token that will be ignored
   */
  @compileTimeOnly("Should never be called outside the lexer definition")
  def Ignored(using s: LexerScope): IgnoredToken[s.Ctx] = new IgnoredToken

  /**
   * Creates a token whose lexemes carry no value (`()`). To carry the matched text or anything computed from it, bind
   * the match and pass it: `case n @ "[0-9]+" => Token["NUM"](n.toInt)`.
   *
   * This is compile-time only and should only be used inside lexer definitions.
   *
   * @tparam Name the token name
   * @return a token definition
   */
  @compileTimeOnly("Should never be called outside the lexer definition")
  def apply[Name <: ValidName](using s: LexerScope): Token[Name, s.Ctx, Unit] =
    new Token[Name, s.Ctx, Unit]

  /**
   * Creates a token whose lexemes carry `value`, typically computed from the bound match.
   *
   * This is compile-time only and should only be used inside lexer definitions.
   *
   * @tparam Name the token name
   * @param value the value its lexemes carry
   * @return a token definition
   */
  @compileTimeOnly("Should never be called outside the lexer definition")
  def apply[Name <: ValidName](value: Any)(using s: LexerScope): Token[Name, s.Ctx, value.type] =
    new Token[Name, s.Ctx, value.type]

// The returned type is the concrete context type `C` refined with a getter
// and a setter for every case field that doesn't already have a real setter,
// e.g. for `case class Ctx(count: Int)`: `C { def count: Int; def count_=(v:
// Int): Unit }`. This is what lets `ctx.count += 1` type-check even when
// `count` is an immutable `val` — the real getter always wins over the
// structural one, but the structural setter is used since there is no real
// one. The `lexer` macro then rewrites every such structural assignment back
// into a `copy` (see `rewriteCtxMutations`) before the rule is compiled, so
// the structural setter is never actually invoked at runtime for a `case
// class` context: this type exists purely to make the mutation-looking
// syntax type-check. Contexts that still declare `var` fields are
// unaffected: the real `var` setter shadows the structural one and the
// assignment mutates in place, exactly as before, and in fact never gains a
// refinement member in the first place.
//
// `C` is inferred as a fresh, unbound type parameter from whatever context
// function currently binds the `LexerScope` — deliberately *not* `scope.ctx.type`: refining the
// singleton type of the specific enclosing lambda parameter, rather than the
// nominal class `C`, is what a `lexer` rule's own macro (which tears the
// rule apart and rebuilds its pieces as fresh lambdas — see `createLexer.scala`)
// empirically stumbles on downstream, even though the two only differ in
// which stable path they're attached to.
/**
 * The lexer context inside a `lexer` rule body. Read its fields, or assign them (`ctx.count += 1`) to change the
 * context for the tokens that follow: the assignment is rewritten into a `copy`, so the fields can stay `val`s.
 */
transparent inline def ctx[C <: LexerCtx: LexerScope.Of as scope]: C = ${ ctxImpl[C]('scope) }

// $COVERAGE-OFF$
@publicInBinary private[alpaca] def ctxImpl[C <: LexerCtx: Type](scope: Expr[LexerScope.Of[C]])(using Quotes): Expr[C] = {
  import quotes.reflect.*

  val ctxTpe = TypeRepr.of[C].widen

  val fields = ctxTpe.typeSymbol.caseFields.iterator
    .filterNot(_.flags.is(Flags.Mutable))
    .map(f => (f.name, ctxTpe.memberType(f)))

  val refined = fields.foldLeft(TypeRepr.of[C]):
    case (acc, (name, tpe)) =>
      val withGetter = Refinement(acc, name, tpe)
      Refinement(withGetter, s"${name}_=", MethodType(List("v"))(_ => List(tpe), _ => TypeRepr.of[Unit]))

  refined.asType match
    case '[type r <: C; r] => '{ LexerScope.ctx($scope).asInstanceOf[r] }
}

// $COVERAGE-ON$

/**
 * Trait for the global context used during tokenization.
 *
 * The global context maintains state during lexing, including the current
 * position in the input, the last matched token, and the remaining text to process.
 * Users can extend this trait to add custom state tracking.
 */
trait LexerCtx extends Product, Selectable:
  /**
   * The last lexeme that was created.
   * @note This is for internal use only and should not be accessed directly.
   */
  @publicInBinary
  private[alpaca] var lastLexeme: Lexeme[?, ?] | Null = compiletime.uninitialized

  /**
   * The raw string that was matched for the last token.
   * @note This is for internal use only and should not be accessed directly.
   */
  @publicInBinary
  private[alpaca] var lastRawMatched: String = compiletime.uninitialized

  /**
   * The remaining text to be tokenized.
   * @note This is for internal use only and should not be accessed directly.
   */
  @publicInBinary
  private[alpaca] var text: CharSequence = compiletime.uninitialized

  /**
   * A copy of at most the next `n` characters of the input still to be tokenized, fewer at its end.
   *
   * Meant for a custom [[ErrorHandling]] instance to look at what failed to match any token rule, e.g. to pick a
   * recovery strategy based on what comes next. Only the requested characters are read and copied, and the result is
   * safe to keep after the callback returns.
   *
   * @param n the maximum number of characters to return
   * @throws IllegalArgumentException if `n` is negative
   */
  final def peek(n: Int): String =
    require(n >= 0, s"peek length must be non-negative, got $n")
    text.subSequence(0, math.min(n, text.length)).toString

  /**
   * Propagates the engine-internal bookkeeping fields above from `prev` onto
   * `this`, e.g. after a `copy()` produced a fresh instance for an immutable
   * context field update. Used by macro-generated code (see `ctx` in
   * `lexer.scala` and [[alpaca.internal.lexer.Tracking.materialize]]); user code
   * never needs to call this.
   *
   * @note This is for internal use only and should not be called directly.
   */
  @publicInBinary
  private[alpaca] def carryEngineStateFrom(prev: LexerCtx): this.type =
    text = prev.text
    lastRawMatched = prev.lastRawMatched
    lastLexeme = prev.lastLexeme
    this

  /**
   * Structural fallback for the getter/setter refinement that `ctx` (see
   * below) types itself with, so that `ctx.field += 1` type-checks even when
   * `field` is an immutable `val`. The `lexer` macro rewrites away every such
   * structural access inside a rule before it is inlined; anywhere else it is a compile error.
   */
  inline def applyDynamic(@unused inline name: String)(@unused inline args: Any*): Any =
    compiletime.error("Lexer context fields can only be assigned inside a lexer rule")

object LexerCtx:

  /** Default error handler for any [[LexerCtx]]: stop at the first unrecognised character and report it. */
  given ErrorHandling[LexerCtx, LexerError] = (_, _) => ErrorHandling.Strategy.Stop

  /**
   * An empty lexer context with no extra state tracking.
   *
   * This is the simplest context that only tracks the remaining text.
   * Use this when you don't need line or column tracking.
   */
  final case class Empty() extends LexerCtx

  /**
   * The default lexer context, composed of the [[Column]] and [[Line]]
   * tracking fragments.
   *
   * This is the most commonly used context and provides useful information
   * for error reporting. The `text` field is inherited from [[LexerCtx]].
   *
   * `column` and `line` are immutable `val`s of a subtype of `Int`:
   * `Tracking.materialize` finds each fragment's `given Tracking` and threads a
   * fresh `copy` of this case class through the lexer rather than mutating a
   * field in place. Read them as plain `Int`s (`ctx.line`, `ctx.column`).
   *
   * In the context they are the position after the last match; in a lexeme they
   * are the position where its token starts.
   *
   * @param column the current column within the line (1-based, in code points)
   * @param line   the current line number (1-based)
   */
  final case class Default(
    column: Column = Column.Start,
    line: Line = Line.Start,
  ) extends LexerCtx

/**
 * Input that did not match any token, as reported by `tokenize` in a [[Result.Failure]].
 *
 * Like a [[Lexeme]], it carries the lexer context's fields as they were where the input starts, read by name
 * (`error.line` when the context has a `line` field) on the errors `tokenize` returns.
 *
 * @param unexpected the input that was not matched: one character, or with `ErrorHandling.Strategy.SkipToNextMatch`
 *                   everything skipped up to the next match
 */
final class LexerError private[alpaca] (
  val unexpected: String,
  private[alpaca] val fieldNames: Array[String],
  private[alpaca] val fieldValues: Array[Any],
) extends Selectable:
  type Fields <: AnyNamedTuple

  def selectDynamic(name: String): Any = contextField(fieldNames, fieldValues, name)

  /** A readable description, e.g. `Unexpected character '@'`. */
  def message: String = {
    val text = Printable(unexpected)
    if unexpected.codePointCount(0, unexpected.length) == 1 then show"Unexpected character '$text'"
    else show"""Unexpected input "$text""""
  }

  override def equals(that: Any): Boolean = that match
    case that: LexerError =>
      unexpected == that.unexpected && fieldNames.sameElements(that.fieldNames) &&
      fieldValues.sameElements(that.fieldValues)
    case _ => false

  override def hashCode: Int = (unexpected, fieldNames.toSeq, fieldValues.toSeq).##

  override def toString: String =
    (unexpected +: fieldNames.lazyZip(fieldValues).map((name, value) => s"$name = ${String.valueOf(value)}"))
      .mkString("LexerError(", ", ", ")")

object LexerError:
  /** An error carrying the fields of the lexer context `Ctx`, as an [[ErrorHandling]] for `Ctx` is given it. */
  type Of[Ctx] = LexerError withFields NamedTuple.From[Ctx]

  extension [Ctx, A](result: Result[Ctx, A, LexerError])
    /** The value; throws the errors as a [[LexerException]] if any input did not match a token. */
    def getOrThrow: A = result match
      case Result.Success(_, value) => value
      case Result.Failure(_, _, errors) => throw LexerException(errors)

  /** An error for `unexpected`, with `ctx`'s fields as they are before it. */
  private[alpaca] def apply[CtxFields <: AnyNamedTuple](unexpected: String, fieldNames: Array[String], ctx: LexerCtx)
    : LexerError withFields CtxFields =
    new LexerError(unexpected, fieldNames, ctx.productIterator.toArray).asInstanceOf[LexerError withFields CtxFields]

  def unapply(error: LexerError): Some[String] = Some(error.unexpected)

/**
 * Thrown by `getOrThrow` on a lexer [[Result]] when some input did not match a token.
 *
 * @param errors the errors the lexer reported, in input order
 */
final class LexerException(val errors: ::[LexerError]) extends RuntimeException(errors.map(_.message).mkString("\n"))

/**
 * Type alias for lexer rule definitions.
 *
 * A lexer definition is a partial function that maps string patterns
 * (as regex literals) to token definitions.
 *
 * @tparam Ctx the global context type
 */
type LexerDefinition[Ctx <: LexerCtx] = PartialFunction[String, Token[ValidName, Ctx, Any]]
