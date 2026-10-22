package halotukozak
package alpaca
package internal

import halotukozak.alpaca.ErrorHandling.Strategy

import scala.annotation.{nowarn, publicInBinary}
import scala.quoted.*

// $COVERAGE-OFF$
@publicInBinary private[alpaca] def lexerErrorHandlingImpl[Ctx <: LexerCtx: Type](
  handler: Expr[LexerScope.Of[Ctx] ?=> LexerError.Of[Ctx] => Strategy],
)(using Quotes,
): Expr[ErrorHandling[Ctx, LexerError.Of[Ctx]]] =
  updatingErrorHandling[Ctx, LexerError.Of[Ctx]](handler, "ErrorHandling.forLexer")

@publicInBinary private[alpaca] def parserErrorHandlingImpl[Ctx <: ParserCtx: Type](
  handler: Expr[CtxScope.Of[Ctx] ?=> ParserError => Strategy],
)(using Quotes,
): Expr[ErrorHandling[Ctx, ParserError]] =
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
): Expr[ErrorHandling[Ctx, Err]] = {
  import quotes.reflect.*
  given Diagnostics = Diagnostics()

  // the error lambda is kept whole, with its own parameter: the types in its body may depend on it (`error.Fields`)
  val (oldScope, onError) = handler.asTerm match
    case ScopedLambda(oldScope, onError @ Lambda(_ :: Nil, _)) => (oldScope, onError)
    case _ =>
      // at the call: a handler defined elsewhere is `underlying` there
      errorAndAbort(
        show"An `${name.showRaw}` handler must be a lambda written directly in its block, as in `error => ErrorHandling.Strategy.SkipOne`",
        Position.ofMacroExpansion,
      )

  def run(current: Expr[Ctx], error: Expr[Err]): Expr[Strategy] =
    val ctxVar = current.asTerm.symbol
    // the handler's method, which declares `current`
    val owner = ctxVar.owner
    val body = Select.unique(onError, "apply").appliedTo(error.asTerm).changeOwner(owner)
    val withNewCtx = replaceScope[Ctx](oldScope.symbol, current.asTerm)(PartialFunction.empty).transformTerm(body)(owner)
    val rewritten = rewriteCtxMutations(ctxVar)(withNewCtx)(owner)
    abortOnErrors()
    rewritten.asExprOf[Strategy]

  '{
    new ErrorHandling[Ctx, Err]:
      private def update(initial: Ctx, error: Err): (strategy: Strategy, ctx: Ctx) =
        // assigned only by the rewritten handler, spliced in below
        @nowarn("msg=unset local variable") var current = initial
        // the strategy first, so `ctx` is the context the handler left
        (strategy = ${ run('current, 'error) }, ctx = current)

      def apply(ctx: Ctx, error: Err): Strategy = update(ctx, error).strategy

      // `C` is the lexer's or parser's own context, a case class, which no other context extends
      override private[alpaca] def handle[C <: Ctx](ctx: C, error: Err): (strategy: Strategy, ctx: C) =
        update(ctx, error).asInstanceOf[(strategy: Strategy, ctx: C)]
  }
}
// $COVERAGE-ON$
