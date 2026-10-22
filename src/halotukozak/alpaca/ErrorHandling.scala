package halotukozak
package alpaca

/**
 * How tokenizing or parsing goes on after input that is not accepted.
 *
 * Whatever the strategy, the error is reported in the [[Result.Failure]] that `tokenize` or `parse` returns; the
 * strategy only decides whether the run stops there or skips past the input and goes on. It is given the context and
 * the error, so it can decide per error.
 *
 * The lexer looks for an `ErrorHandling[Ctx, LexerError.Of[Ctx]]` for its context (an `ErrorHandling[Ctx, LexerError]`
 * also fits), the parser for an `ErrorHandling[Ctx, ParserError]` for its context. Both default to
 * [[ErrorHandling.Strategy.Stop]]; define a `given` for your context type to change that:
 * {{{
 * given ErrorHandling[MyLexerCtx, LexerError] = (ctx, error) => ErrorHandling.Strategy.SkipOne
 * given ErrorHandling[MyParserCtx, ParserError] = (ctx, error) => ErrorHandling.Strategy.SkipToNextMatch
 * }}}
 *
 * A lexer handler declared for `LexerError.Of[MyLexerCtx]` reads the context's fields with their types.
 *
 * To change the context from a handler, build it with [[ErrorHandling.forLexer]] or [[ErrorHandling.forParser]], where
 * `ctx` works as in a lexer rule or a parser action:
 * {{{
 * given ErrorHandling[MyLexerCtx, LexerError.Of[MyLexerCtx]] = ErrorHandling.forLexer: error =>
 *   ctx.skipped += 1
 *   ErrorHandling.Strategy.SkipOne
 * }}}
 *
 * @tparam Ctx the lexer or parser context this applies to
 * @tparam Err the error it is given: [[LexerError]] for the lexer, [[ParserError]] for the parser
 */
trait ErrorHandling[-Ctx, -Err] extends ((Ctx, Err) => ErrorHandling.Strategy):
  /** The strategy for `error`, and the context to go on with: `ctx` itself unless built by [[ErrorHandling.forLexer]]. */
  private[alpaca] def handle[C <: Ctx](ctx: C, error: Err): (strategy: ErrorHandling.Strategy, ctx: C) =
    (strategy = apply(ctx, error), ctx = ctx)

object ErrorHandling:

  /**
   * A lexer error handler that can change the context. In `handler`, `ctx` is the lexer context as in a rule body:
   * assigning its fields (`ctx.count += 1`) changes the context the lexer goes on with, so they can stay `val`s.
   * {{{
   * case class MyCtx(line: Line = Line.Start, skipped: Int = 0) extends LexerCtx
   *
   * given ErrorHandling[MyCtx, LexerError.Of[MyCtx]] = ErrorHandling.forLexer: error =>
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
    inline handler: LexerScope.Of[Ctx] ?=> LexerError.Of[Ctx] => Strategy,
  ): ErrorHandling[Ctx, LexerError.Of[Ctx]] =
    ${ internal.lexer.errorHandlingImpl[Ctx]('{ handler }) }

  /**
   * A parser error handler with `ctx` in scope, as in the parser's actions: the parser context, changed in place.
   * {{{
   * case class MyCtx(var skipsLeft: Int = 3) extends ParserCtx
   *
   * given ErrorHandling[MyCtx, ParserError] = ErrorHandling.forParser: error =>
   *   if ctx.skipsLeft == 0 then ErrorHandling.Strategy.Stop
   *   else
   *     ctx.skipsLeft -= 1
   *     ErrorHandling.Strategy.SkipOne
   * }}}
   *
   * @tparam Ctx the parser context
   * @param handler the strategy for an error
   */
  def forParser[Ctx <: ParserCtx](handler: CtxScope.Of[Ctx] ?=> ParserError => Strategy)
    : ErrorHandling[Ctx, ParserError] =
    (ctx, error) => handler(using CtxScope.refl(ctx))(error)

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
