package halotukozak
package alpaca
package internal

import halotukozak.alpaca.ErrorHandling.Strategy

import scala.annotation.publicInBinary
import scala.quoted.*

// $COVERAGE-OFF$
@publicInBinary private[alpaca] def lexerErrorHandlingImpl[Ctx <: LexerCtx: Type](
  handler: Expr[LexerScope.Of[Ctx] ?=> LexerError.Of[Ctx] => Strategy],
)(using Quotes,
): Expr[ErrorHandling[Ctx]] =
  updatingErrorHandling[Ctx, LexerError.Of[Ctx]](handler, "ErrorHandling.forLexer")

@publicInBinary private[alpaca] def parserErrorHandlingImpl[Ctx <: ParserCtx: Type](
  handler: Expr[CtxScope.Of[Ctx] ?=> ParserError => Strategy],
)(using Quotes,
): Expr[ErrorHandling[Ctx]] =
  updatingErrorHandling[Ctx, ParserError](handler, "ErrorHandling.forParser")

/**
 * Builds an [[ErrorHandling.forLexer]] or [[ErrorHandling.forParser]] handler: its body runs against a local `var` holding
 * the context, with every context assignment rewritten into a `copy` of it as in a lexer rule or a parser action (see
 * [[rewriteCtxMutations]]), and returns the strategy together with that `var`.
 *
 * @param handler the user's `scope ?=> error => strategy` lambda
 * @param name    how the user calls the builder, for the error message
 */
private def updatingErrorHandling[Ctx <: AlpacaCtx: Type, Err: Type](
  using Quotes,
)(
  handler: Expr[?],
  name: String,
): Expr[ErrorHandling[Ctx]] = {
  import quotes.reflect.*
  given Diagnostics = Diagnostics()

  // the handler is found by the context type alone, so a context of both kinds would give it the other kind's errors
  if TypeRepr.of[Ctx] <:< TypeRepr.of[LexerCtx & ParserCtx] then
    errorAndAbort(
      show"`${Printable(TypeRepr.of[Ctx].typeSymbol.name)}` is both a `LexerCtx` and a `ParserCtx`; give the lexer and the parser a context each",
      Position.ofMacroExpansion,
    )

  // the error lambda is kept whole, with its own parameter: the types in its body may depend on it (`error.Fields`)
  val (oldScope, onError) = handler.asTerm match
    case ScopedLambda(oldScope, onError @ Lambda(_ :: Nil, _)) => (oldScope, onError)
    case _ =>
      // at the call: a handler defined elsewhere has its position there
      errorAndAbort(
        show"An `${name.showRaw}` handler must be a lambda written directly in its block, as in `error => ErrorHandling.Strategy.SkipOne`",
        Position.ofMacroExpansion,
      )

  def run(current: Expr[Ctx], error: Expr[Err]): Expr[Strategy] =
    val ctxSymbol = current.asTerm.symbol
    // the handler's lambda, which declares `current` or takes the initial context
    val owner = ctxSymbol.owner
    // in terms, not a quote: `error` belongs to the splice, so a quote of this macro must not splice it
    val body = Select.unique(onError, "apply").appliedTo(error.asTerm).changeOwner(owner)
    val withNewCtx = replaceScope[Ctx](oldScope.symbol, current.asTerm)(PartialFunction.empty).transformTerm(body)(owner)
    val rewritten = rewriteCtxMutations(ctxSymbol)(withNewCtx)(owner)
    abortOnErrors()
    rewritten.asExprOf[Strategy]

  // the lexer or parser of `Ctx` gives the handler its own errors; the strategy comes first, so `ctx` is the context
  // the handler left
  if assignsCtx(onError) then
    '{
      new ErrorHandling[Ctx]((initial, error) =>
        var current = initial
        (strategy = ${ run('current, '{ error.asInstanceOf[Err] }) }, ctx = current),
      )
    }
  // without a `var`, which one never assigned would be reported as unset where the handler is defined
  else '{ new ErrorHandling[Ctx]((ctx, error) => (strategy = ${ run('ctx, '{ error.asInstanceOf[Err] }) }, ctx = ctx)) }
}
// $COVERAGE-ON$
