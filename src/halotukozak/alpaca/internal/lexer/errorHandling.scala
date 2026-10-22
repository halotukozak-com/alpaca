package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.ErrorHandling.Strategy

import scala.annotation.publicInBinary
import scala.quoted.*

/**
 * What [[ErrorHandling.forLexer]] builds: `update` returns the strategy together with the updated context.
 *
 * Not `private`: [[ErrorHandling.forLexer]] is inline, so it is constructed wherever that is called.
 */
final class CtxUpdatingErrorHandling[Ctx, Err] @publicInBinary private[alpaca] (
  update: (Ctx, Err) => (strategy: Strategy, ctx: Ctx),
) extends ErrorHandling[Ctx, Err]:
  def apply(ctx: Ctx, error: Err): Strategy = update(ctx, error).strategy

  override private[alpaca] def handle[C <: Ctx](ctx: C, error: Err): (strategy: Strategy, ctx: C) =
    val updated = update(ctx, error)
    // `C` is the lexer's own context, a case class, which no other context extends
    (strategy = updated.strategy, ctx = updated.ctx.asInstanceOf[C])

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
    rewriteCtxMutations(ctxVar)(withNewCtx)(owner).asExprOf[Strategy]

  abortOnErrors()
  '{
    new CtxUpdatingErrorHandling[Ctx, LexerError.Of[Ctx]]((initial, error) =>
      var current = initial
      // the strategy first, so `ctx` is the context the handler left
      (strategy = ${ run('current, 'error) }, ctx = current),
    )
  }
}
// $COVERAGE-ON$
