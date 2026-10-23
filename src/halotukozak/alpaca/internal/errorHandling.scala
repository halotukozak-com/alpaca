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
 * Builds an [[ErrorHandling.forLexer]] or [[ErrorHandling.forParser]] handler: its body has its context assignments
 * threaded through as in a lexer rule or a parser action (see [[threadCtx]]), and it returns the strategy together with
 * the context it leaves.
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
    case dropInlined(Lambda(oldScope :: Nil, dropInlined(onError @ Lambda(_ :: Nil, _)))) => (oldScope, onError)
    case _ =>
      // at the call: a handler defined elsewhere has its position there
      errorAndAbort(
        show"An `${name.showRaw}` handler must be a lambda written directly in its block, as in `error => ErrorHandling.Strategy.SkipOne`",
        Position.ofMacroExpansion,
      )

  // outside the splice below, which brings its own `Quotes` that the handler's trees do not belong to
  def handle(initial: Expr[Ctx], error: Expr[Err]): Expr[(strategy: Strategy, ctx: Ctx)] =
    // in terms: `initial` and `error` belong to the splice, so a quote of this macro must not splice them
    val initialCtx = initial.asTerm
    // the handler's lambda, which takes the initial context
    val owner = initialCtx.symbol.owner
    val handled =
      threadCtx[Ctx](owner, initialCtx, oldScope.symbol, Select.unique(onError, "apply").appliedTo(error.asTerm)):
        (strategy, ctx) =>
          '{ (strategy = ${ strategy.asExprOf[Strategy] }, ctx = ${ ctx.asExprOf[Ctx] }) }.asTerm.changeOwner(owner)
    abortOnErrors()
    handled.asExprOf[(strategy: Strategy, ctx: Ctx)]

  // the lexer or parser of `Ctx` gives the handler its own errors; the strategy comes first, so `ctx` is the context
  // the handler left
  '{ new ErrorHandling[Ctx]((initial, error) => ${ handle('initial, '{ error.asInstanceOf[Err] }) }) }
}
// $COVERAGE-ON$
