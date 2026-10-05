package halotukozak
package alpaca

import scala.annotation.implicitNotFound

/**
 * How tokenizing or parsing goes on after input that is not accepted.
 *
 * Whatever the strategy, the error is reported in the [[Result.Failure]] that `tokenize` or `parse` returns; the
 * strategy only decides whether the run stops there or skips past the input and goes on. It is given the context and
 * the error, so it can decide per error.
 *
 * The lexer looks for an `ErrorHandling[Ctx, LexError]` for its context, the parser for an
 * `ErrorHandling[Ctx, ParseError]` for its context. Both default to [[ErrorHandling.Strategy.Stop]]; define a
 * `given` for your context type to change that:
 * {{{
 * given ErrorHandling[MyLexerCtx, LexError] = (ctx, error) => ErrorHandling.Strategy.SkipOne
 * given ErrorHandling[MyParserCtx, ParseError] = (ctx, error) => ErrorHandling.Strategy.SkipToNextMatch
 * }}}
 *
 * @tparam Ctx the lexer or parser context this applies to
 * @tparam E   the error it is given: [[LexError]] for the lexer, [[ParseError]] for the parser
 */
@implicitNotFound("Define ErrorHandling[${Ctx}, ${E}].")
trait ErrorHandling[-Ctx, -E] extends ((Ctx, E) => ErrorHandling.Strategy)

object ErrorHandling:
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
