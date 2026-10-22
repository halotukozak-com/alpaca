package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.ErrorHandling.Strategy

import scala.annotation.publicInBinary
import scala.quoted.*

// $COVERAGE-OFF$
/**
 * Builds an [[ErrorHandling.forLexer]] handler: its body runs against a local `var` holding the context, with every
 * context assignment rewritten into a `copy` of it as in a rule body (see [[rewriteCtxMutations]]), and returns the
 * strategy together with that `var`.
 */
@publicInBinary private[alpaca] def errorHandlingImpl[Ctx <: LexerCtx: Type](
  handler: Expr[LexerScope.Of[Ctx] ?=> LexerError.Of[Ctx] => Strategy],
)(using Quotes,
): Expr[ErrorHandling[Ctx, LexerError.Of[Ctx]]] = {
  import quotes.reflect.*
  given Diagnostics = Diagnostics()

  val (oldScope, oldError, body) = handler.asTerm.underlying match
    case Lambda(oldScope :: Nil, Lambda(oldError :: Nil, body)) => (oldScope, oldError, body)
    case _ =>
      // at the call: a handler defined elsewhere is `underlying` there
      errorAndAbort(
        show"An `ErrorHandling.forLexer` handler must be a lambda written directly in its block, as in `error => ErrorHandling.Strategy.SkipOne`",
        Position.ofMacroExpansion,
      )

  def run(current: Expr[Ctx], error: Expr[LexerError.Of[Ctx]]): Expr[Strategy] =
    val ctxVar = current.asTerm.symbol
    // the handler's lambda, which declares `current`
    val owner = ctxVar.owner
    val withNewCtx = replaceScope[Ctx](oldScope.symbol, current.asTerm):
      case symbol if symbol == oldError.symbol => error.asTerm
    .transformTerm(body.changeOwner(owner))(owner)
    val rewritten = rewriteCtxMutations(ctxVar)(withNewCtx)(owner)
    abortOnErrors()
    rewritten.asExprOf[Strategy]

  '{
    new ErrorHandling[Ctx, LexerError.Of[Ctx]]:
      private def update(initial: Ctx, error: LexerError.Of[Ctx]): (strategy: Strategy, ctx: Ctx) =
        var current = initial
        // the strategy first, so `ctx` is the context the handler left
        (strategy = ${ run('current, 'error) }, ctx = current)

      def apply(ctx: Ctx, error: LexerError.Of[Ctx]): Strategy = update(ctx, error).strategy

      // `C` is the lexer's own context, a case class, which no other context extends
      override private[alpaca] def handle[C <: Ctx](ctx: C, error: LexerError.Of[Ctx]): (strategy: Strategy, ctx: C) =
        update(ctx, error).asInstanceOf[(strategy: Strategy, ctx: C)]
  }
}
// $COVERAGE-ON$
