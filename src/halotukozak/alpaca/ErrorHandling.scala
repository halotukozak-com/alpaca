package halotukozak
package alpaca

import scala.annotation.publicInBinary

/**
 * How tokenizing or parsing goes on after input that is not accepted.
 *
 * Whatever the strategy, the error is reported in the [[Result.Failure]] that `tokenize` or `parse` returns; the
 * strategy only decides whether the run stops there or skips past the input and goes on. It is given the error, and
 * reads and changes the context as `ctx`, so it can decide per error.
 *
 * The lexer and the parser look for an `ErrorHandling` for their context, which defaults to
 * [[ErrorHandling.Strategy.Stop]]. Define a `given` for your context type, built with [[ErrorHandling.forLexer]] or
 * [[ErrorHandling.forParser]], to change that:
 * {{{
 * given ErrorHandling[MyLexerCtx] = ErrorHandling.forLexer: error =>
 *   ctx.skipped += 1
 *   ErrorHandling.Strategy.SkipOne
 * given ErrorHandling[MyParserCtx] = ErrorHandling.forParser(_ => ErrorHandling.Strategy.SkipToNextMatch)
 * }}}
 *
 * @tparam Ctx the lexer or parser context this applies to
 */
final class ErrorHandling[Ctx <: AlpacaCtx] @publicInBinary private[alpaca] (
  /** The strategy for an error, and the context to go on with. */
  private[alpaca] val handle: (Ctx, LexerError | ParserError) => (strategy: ErrorHandling.Strategy, ctx: Ctx),
)

object ErrorHandling:

  /**
   * A lexer error handler. In `handler`, the error carries the context's fields with their types (`error.line`).
   * It can also change the context. In `handler`, `ctx` is the lexer context as in a rule body:
   * assigning its fields (`ctx.count += 1`) changes the context the lexer goes on with, so they can stay `val`s.
   * {{{
   * case class MyCtx(line: Line = Line.Start, skipped: Int = 0) extends LexerCtx
   *
   * given ErrorHandling[MyCtx] = ErrorHandling.forLexer: error =>
   *   ctx.skipped += 1
   *   ErrorHandling.Strategy.SkipOne
   * }}}
   * The error reported for the input keeps the context from before the handler ran.
   *
   * @tparam Ctx the lexer context
   * @param handler the strategy for an error, written directly as a lambda
   */
  // `transparent` so it expands while `handler` is typed, before the `applyDynamic` of an assignment is inlined into
  // its compile error, as `lexer` does
  transparent inline def forLexer[Ctx <: LexerCtx](
    inline handler: CtxScope.Of[Ctx] ?=> LexerError.Of[Ctx] => Strategy,
  ): ErrorHandling[Ctx] =
    ${ internal.lexerErrorHandlingImpl[Ctx]('{ handler }) }

  /**
   * A parser error handler, which can change the context. In `handler`, `ctx` is the parser context as in an action:
   * assigning its fields (`ctx.skipsLeft -= 1`) changes the context the parser goes on with, so they can stay `val`s.
   * {{{
   * case class MyCtx(skipsLeft: Int = 3) extends ParserCtx
   *
   * given ErrorHandling[MyCtx] = ErrorHandling.forParser: error =>
   *   if ctx.skipsLeft == 0 then ErrorHandling.Strategy.Stop
   *   else
   *     ctx.skipsLeft -= 1
   *     ErrorHandling.Strategy.SkipOne
   * }}}
   *
   * @tparam Ctx the parser context
   * @param handler the strategy for an error, written directly as a lambda
   */
  inline def forParser[Ctx <: ParserCtx](
    inline handler: CtxScope.Of[Ctx] ?=> ParserError => Strategy,
  ): ErrorHandling[Ctx] =
    ${ internal.parserErrorHandlingImpl[Ctx]('{ handler }) }

  // the default: stop at the first error
  private val stop = ErrorHandling[AlpacaCtx]((ctx, _) => (strategy = Strategy.Stop, ctx = ctx))

  /** Stops at the first error: what a lexer or parser does without a handler for its context. */
  private[alpaca] def stopping[Ctx <: AlpacaCtx]: ErrorHandling[Ctx] = stop.asInstanceOf[ErrorHandling[Ctx]]

  /** What to do with input that is not accepted: one character for the lexer, one lexeme for the parser. */
  enum Strategy:
    /** Skips the one character or lexeme that is not accepted and goes on. */
    case SkipOne

    /**
     * Skips ahead to the next character the lexer can match, or the next lexeme the parser can accept, and goes on;
     * one error covers everything skipped. If nothing further is accepted, it skips one character or stops at the end
     * of the input.
     */
    case SkipToNextMatch

    /** Stops at the error; the failure has no `recovered` value. */
    case Stop
