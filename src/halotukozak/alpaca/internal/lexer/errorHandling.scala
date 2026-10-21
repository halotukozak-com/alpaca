package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.ErrorHandling.Strategy

import scala.annotation.publicInBinary
import scala.quoted.*

/**
 * What [[ErrorHandling.lexer]] builds: `update` returns the strategy together with the updated context.
 *
 * Not `private`: [[ErrorHandling.lexer]] is inline, so it is constructed wherever that is called.
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
 * Builds an [[ErrorHandling.lexer]] handler: its body runs against a local `var` holding the context, with every
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
        show"An `ErrorHandling.lexer` handler must be a lambda written directly in its block, as in `error => ErrorHandling.Strategy.SkipOne`",
        Position.ofMacroExpansion,
      )

  val update = createLambda[(Ctx, LexerError.Of[Ctx]) => (strategy: Strategy, ctx: Ctx)]:
    case (methSym, (newCtx: Term) :: (newError: Term) :: Nil) =>
      val ctxVar = Symbol.newVal(methSym, "$ctx", TypeRepr.of[Ctx], Flags.Mutable, Symbol.noSymbol)
      val strategy = Symbol.newVal(methSym, "$strategy", TypeRepr.of[Strategy], Flags.EmptyFlags, Symbol.noSymbol)
      val withNewCtx = replaceScope[Ctx](oldScope.symbol, Ref(ctxVar)):
        case error if error == oldError.symbol => newError
      .transformTerm(body.changeOwner(methSym))(methSym)
      val rewritten = rewriteCtxMutations(ctxVar)(withNewCtx)(methSym)
      Block(
        List(ValDef(ctxVar, Some(newCtx)), ValDef(strategy, Some(rewritten.changeOwner(strategy)))),
        '{ (strategy = ${ Ref(strategy).asExprOf[Strategy] }, ctx = ${ Ref(ctxVar).asExprOf[Ctx] }) }.asTerm,
      )

  abortOnErrors()
  '{ new CtxUpdatingErrorHandling[Ctx, LexerError.Of[Ctx]]($update) }
}
// $COVERAGE-ON$
