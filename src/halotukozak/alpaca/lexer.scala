package halotukozak
package alpaca

import alpaca.internal.*
import alpaca.internal.lexer.{IgnoredToken as _, Token as _, *}

import scala.NamedTuple.NamedTuple
import scala.annotation.{compileTimeOnly, publicInBinary, unused}

/**
 * Public re-exports of lexer types users are expected to reference directly
 *  (custom `given` instances, tracking traits, large-file tokenization) even
 *  though they are implemented under `internal.lexer`.
 */
export alpaca.internal.lexer.{Column, ErrorHandling, LazyReader, Line, Tracking}

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
 * @param empty implicit Empty instance to create the initial context
 * @return a Tokenization instance that can tokenize input strings
 */
transparent inline def lexer[Ctx <: LexerCtx](
  using Ctx withDefault LexerCtx.Default,
)(
  inline rules: Ctx ?=> LexerDefinition[Ctx],
)(using
  m: Mirror.ProductOf[Ctx],
  errorHandling: ErrorHandling[Ctx],
  empty: Empty[Ctx],
): Tokenization[Ctx] { type LexemeFields = NamedTuple[m.MirroredElemLabels, m.MirroredElemTypes] } =
  ${
    lexerImpl[Ctx, NamedTuple[m.MirroredElemLabels, m.MirroredElemTypes]](
      '{ rules },
      '{ Tracking.materialize[Ctx] },
      '{ errorHandling },
      '{ empty },
    )
  }

/**
 * Defines an opaque type `Token` that represents a token used in a lexer.
 *
 * This type has three type parameters:
 * - `Name`: The type of the token's name, restricted to a subtype of `ValidName`.
 * - `Ctx`: The type of the lexer context, restricted to a subtype of `LexerCtx`.
 * - `Value`: The type of the token's value.
 *
 * The exact implementation details of the underlying type are abstracted away by using `Any`.
 * Opaque types provide type safety without exposing the underlying representation.
 */
class Token[+Name <: ValidName, +Ctx <: LexerCtx, +Value]

/**
 * Represents a specific type of token definition that denotes an ignored token during the lexing process.
 *
 * Ignored tokens typically refer to tokens that are matched and processed by the lexer but are
 * excluded from the final parsed token stream. Examples of ignored tokens include whitespace,
 * comments, or any other tokens that are syntactically meaningful but do not contribute to
 * the structured representation of the input source.
 *
 * This opaque type is parameterized by a context type `Ctx`, which must be a subtype of `LexerCtx`.
 * The `LexerCtx` trait serves as a base for maintaining global lexing state, such as the current
 * position, the last matched token, and the remaining input.
 *
 * The `ValidName` and `Nothing` type parameters are placeholder constraints inherited from
 * `Token`, but `IgnoredToken` does not provide its own additional constraints
 * or behavior beyond being excluded from normal processing.
 *
 * The use of an opaque type ensures safe and restricted use within the scope of the lexer, as
 * this type cannot be directly manipulated outside the context of its definition.
 */
final class IgnoredToken[+Ctx <: LexerCtx] extends Token[ValidName, Ctx, Nothing]

/** Factory methods for creating token definitions in the lexer DSL. */
object Token:

  /**
   * Creates an ignored token that will be matched but not included in the output.
   *
   * This is compile-time only and should only be used inside lexer definitions.
   *
   * @param ctx the lexer context
   * @return a token that will be ignored
   */
  @compileTimeOnly("Should never be called outside the lexer definition")
  def Ignored(using ctx: LexerCtx): IgnoredToken[ctx.type] = new IgnoredToken[ctx.type]

  /**
   * Creates a token that captures the matched string.
   *
   * This is compile-time only and should only be used inside lexer definitions.
   *
   * @tparam Name the token name
   * @param ctx the lexer context
   * @return a token definition
   */
  @compileTimeOnly("Should never be called outside the lexer definition")
  def apply[Name <: ValidName](using ctx: LexerCtx): Token[Name, ctx.type, String] =
    new Token[Name, ctx.type, String]

  /**
   * Creates a token with a custom value extractor.
   *
   * This is compile-time only and should only be used inside lexer definitions.
   *
   * @tparam Name the token name
   * @param value the value to extract from the match
   * @param ctx   the lexer context
   * @return a token definition
   */
  @compileTimeOnly("Should never be called outside the lexer definition")
  def apply[Name <: ValidName](value: Any)(using ctx: LexerCtx): Token[Name, ctx.type, value.type] =
    new Token[Name, ctx.type, value.type]

/**
 * Propagates the lexer context through the DSL so that token constructors and
 * rule bodies can access it implicitly.
 *
 * The returned type is the concrete context type `C` refined with a getter
 * and a setter for every case field that doesn't already have a real setter,
 * e.g. for `case class Ctx(count: Int)`: `C { def count: Int; def count_=(v:
 * Int): Unit }`. This is what lets `ctx.count += 1` type-check even when
 * `count` is an immutable `val` — the real getter always wins over the
 * structural one, but the structural setter is used since there is no real
 * one. The `lexer` macro then rewrites every such structural assignment back
 * into a `copy` (see `rewriteCtxMutations`) before the rule is compiled, so
 * the structural setter is never actually invoked at runtime for a `case
 * class` context: this type exists purely to make the mutation-looking
 * syntax type-check. Contexts that still declare `var` fields are
 * unaffected: the real `var` setter shadows the structural one and the
 * assignment mutates in place, exactly as before, and in fact never gains a
 * refinement member in the first place.
 *
 * `C` is inferred as a fresh, unbound type parameter from whatever context
 * function currently binds `c` — deliberately *not* `c.type`: refining the
 * singleton type of the specific enclosing lambda parameter, rather than the
 * nominal class `C`, is what a `lexer` rule's own macro (which tears the
 * rule apart and rebuilds its pieces as fresh lambdas — see `Lexer.scala`)
 * empirically stumbles on downstream, even though the two only differ in
 * which stable path they're attached to.
 */
transparent inline def ctx[C <: LexerCtx](using c: C): C = ${ ctxImpl[C]('c) }

// $COVERAGE-OFF$
@publicInBinary private[alpaca] def ctxImpl[C <: LexerCtx: Type](c: Expr[C])(using quotes: Quotes): Expr[C] = {
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
    case '[type r <: C; r] => '{ $c.asInstanceOf[r] }
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
   * @note Internal API — the lexer macro reads this field at user-site, so it
   *       has to be source-visible outside the `alpaca` package.
   */
  var lastRawMatched: String = compiletime.uninitialized

  /**
   * The remaining text to be tokenized.
   * @note This is for internal use only and should not be accessed directly.
   */
  @publicInBinary
  private[alpaca] var text: CharSequence = compiletime.uninitialized

  /**
   * A read-only view of the text still remaining to be tokenized.
   *
   * Exposed so a custom [[alpaca.internal.lexer.ErrorHandling]] instance can inspect the character(s)
   * that failed to match any token rule, e.g. to build a diagnostic message
   * such as `unexpected '${ctx.remainingText.charAt(0)}'`.
   */
  final def remainingText: CharSequence = text

  /**
   * Propagates the engine-internal bookkeeping fields above from `prev` onto
   * `this`, e.g. after a `copy()` produced a fresh instance for an immutable
   * context field update. Used by macro-generated code (see `ctx` in
   * `lexer.scala` and [[alpaca.internal.lexer.Tracking.Derived]]); user code
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
   * structural access inside a rule before it is compiled, so in practice
   * this is only a safety net; it should never be hit at runtime.
   */
  // $COVERAGE-OFF$
  def applyDynamic(name: String)(@unused args: Any*): Any =
    throw new UnsupportedOperationException(
      s"Cannot mutate lexer context field '$name' on $productPrefix: either this assignment is outside a " +
        "lexer rule, or the lexer macro failed to rewrite it into a functional update.",
    )
  // $COVERAGE-ON$

object LexerCtx:

  /** Default error handler for any [[LexerCtx]] that throws on the first unrecognised character. */
  given ErrorHandling[LexerCtx] = ctx =>
    ErrorHandling.Strategy.Throw(new RuntimeException(s"Unexpected character: '${ctx.text.charAt(0)}'"))

  /**
   * An empty lexer context with no extra state tracking.
   *
   * This is the simplest context that only tracks the remaining text.
   * Use this when you don't need line or position tracking.
   */
  final case class Empty() extends LexerCtx

  /**
   * The default lexer context, composed of the [[Column]] and [[Line]]
   * tracking fragments.
   *
   * This is the most commonly used context and provides useful information
   * for error reporting. The `text` field is inherited from [[LexerCtx]].
   *
   * `position` and `line` are immutable `val`s of a subtype of `Int`:
   * `Tracking.materialize` finds each fragment's `given Tracking` and threads a
   * fresh `copy` of this case class through the lexer rather than mutating a
   * field in place. Read them as plain `Int`s (`ctx.line`, `ctx.position`).
   *
   * @param position the current column position within the line (1-based)
   * @param line     the current line number (1-based)
   */
  final case class Default(
    position: Column = Column.Start,
    line: Line = Line.Start,
  ) extends LexerCtx

  object Default:
    /** Default error handler for [[Default]] that includes line and position information in the error message. */
    given ErrorHandling[Default] = ctx =>
      ErrorHandling.Strategy.Throw:
        new RuntimeException(
          s"Unexpected character at line ${ctx.line}, position ${ctx.position}: '${ctx.text.charAt(0)}'",
        )

/**
 * Type alias for lexer rule definitions.
 *
 * A lexer definition is a partial function that maps string patterns
 * (as regex literals) to token definitions.
 *
 * @tparam Ctx the global context type
 */

type LexerDefinition[Ctx <: LexerCtx] = PartialFunction[String, Token[ValidName, Ctx, Any]]
