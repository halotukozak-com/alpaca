package halotukozak
package alpaca
package internal

import scala.annotation.{nowarn, tailrec}
import scala.quoted.*
import scala.reflect.NameTransformer

// $COVERAGE-OFF$

/**
 * Points a body typed against a [[CtxScope]] -- a lexer rule, a parser action or an `ErrorHandling.forLexer`/`.forParser`
 * handler -- at `newCtx`: the scope itself becomes `newCtx` again, and every `ctx` unwrapping it becomes `newCtx`
 * directly, so that [[rewriteCtxMutations]] recognises it.
 *
 * @param scope  the scope the body was typed against: the parameter of the user's lambda
 * @param newCtx the context the body runs against
 * @param others replacements for other symbols of the user's lambda, such as its bound match
 */
private[internal] def replaceScope[Ctx <: AlpacaCtx: Type](
  using
  quotes: Quotes,
)(
  scope: quotes.reflect.Symbol,
  newCtx: quotes.reflect.Term,
)(
  others: PartialFunction[quotes.reflect.Symbol, quotes.reflect.Term],
): quotes.reflect.TreeMap = {
  import quotes.reflect.*

  // the scope is the context itself, cast to the scope's type: a lexer's `Token` and a parser's extractors need it
  lazy val rescoped: Term = scope.termRef.widen.asType match
    case '[scopeType] => '{ ${ newCtx.asExpr }.asInstanceOf[scopeType] }.asTerm

  new TreeMap:
    override def transformTerm(t: Term)(owner: Symbol): Term = t match
      case _ if t.symbol == scope => rescoped
      case _ if others.isDefinedAt(t.symbol) => others(t.symbol)
      case block: Block => super.transformTerm(block.changeOwner(owner))(owner)
      case t if t.isExpr =>
        t.asExpr match
          // `ctx` unwraps the scope, which is the context itself
          case '{ ($unwrapped: CtxScope).ctx } if unwrapped.asTerm.symbol == scope => newCtx
          case _ => super.transformTerm(t)(owner)
      case _ => super.transformTerm(t)(owner)
}

/**
 * `body`, typed against `scope`, run against `initial` with its context assignments threaded through a local `var`
 * (see [[replaceScope]] and [[rewriteCtxMutations]]). `result` builds what the block returns from the body and the
 * updated context, `None` when the body assigns nothing: then there is no `var`, and the body runs against `initial`
 * directly. `result` must evaluate the body before the context, as `(body, ctx)` does.
 *
 * @param owner   the owner of the block, e.g. the lambda it is the body of
 * @param initial the context to start from
 * @param scope   the scope `body` was typed against
 * @param body    the user's code
 * @param others  replacements for other symbols of the user's code, as in [[replaceScope]]
 * @param result  what the block returns, from the rewritten body and the updated context
 */
private[internal] def threadCtx[Ctx <: AlpacaCtx: Type, Result: Type](
  using
  quotes: Quotes,
  diagnostics: Diagnostics,
)(
  owner: quotes.reflect.Symbol,
  initial: Expr[Ctx],
  scope: quotes.reflect.Symbol,
  body: quotes.reflect.Term,
  others: PartialFunction[quotes.reflect.Symbol, quotes.reflect.Term] = PartialFunction.empty,
)(
  result: (body: quotes.reflect.Term, ctx: Option[quotes.reflect.Term]) => quotes.reflect.Term,
): quotes.reflect.Term = {
  import quotes.reflect.*

  // `None` without a `var`: the body runs against `initial`
  def threaded(current: Expr[Ctx], updated: Option[Term]): Expr[Result] =
    val ctxSymbol = current.asTerm.symbol
    // what declares `current`: the quote below, or `owner` for the initial context
    val ctxOwner = ctxSymbol.owner
    val replaced = replaceScope[Ctx](scope, current.asTerm)(others).transformTerm(body.changeOwner(ctxOwner))(ctxOwner)
    val rewritten = rewriteCtxMutations(ctxSymbol)(replaced)(ctxOwner)
    // in terms: `current` belongs to the splice, so a quote of the caller's must not splice it
    result(rewritten, updated).asExprOf[Result]

  // a `var` only for a body that assigns: one never assigned is reported as unset where the code expands
  if !assignsCtx(body) then threaded(initial, None).asTerm
  // a quote re-homes what it splices to the macro's own owner, so `changeOwner` puts it under `owner`
  else
    '{
      // assigned only by the rewritten body, spliced in below
      @nowarn("msg=unset local variable") var current = $initial
      ${ threaded('current, Some('current.asTerm)) }
    }.asTerm.changeOwner(owner)
}

/**
 * Whether `body` assigns a context field: calls a setter through `applyDynamic`, as `ctx.count += 1` does. Read before
 * the body is rewritten, to tell whether it needs a `var`.
 */
private[internal] def assignsCtx(using quotes: Quotes)(body: quotes.reflect.Term): Boolean = {
  import quotes.reflect.*

  new TreeAccumulator[Boolean]:
    def foldTree(found: Boolean, tree: Tree)(owner: Symbol): Boolean = found ||
      (tree match
        case Apply(Apply(Select(_, "applyDynamic"), List(Literal(StringConstant(name)))), _)
            if NameTransformer.decode(name).endsWith("_=") =>
          true
        case _ => foldOverTree(found, tree)(owner))
  .foldTree(false, body)(Symbol.spliceOwner)
}

/**
 * Rewrites structural field-mutation syntax on a lexer or parser context back
 * into functional updates, so that a `case class` context with immutable
 * (`val`) fields can still be "mutated" with ordinary syntax inside a lexer
 * rule, a parser action or an error handler:
 *
 * {{{
 * case class Ctx(count: Int = 0) extends LexerCtx
 * ...
 * case "inc" =>
 *   ctx.count += 1     // rewritten to: $ctx = $ctx.copy(count = $ctx.count + 1)
 *   Token["inc"]
 * }}}
 *
 * Against the structural type computed by the `ctx` helper (see `lexer.scala`),
 * `ctx.field = rhs` and every compound-assignment operator (`+=`, `-=`, `::=`,
 * ...) desugar to `ctx.applyDynamic("field_=")(rhs)` — the getter `ctx.field`
 * itself stays a plain field access, since the real `val` always wins over the
 * structural refinement. This class finds exactly that `applyDynamic` shape
 * and replaces it with `_ctx = _ctx.copy(field = rhs)`, threading a local
 * `var` through the rule's statements. `applyDynamic`/`selectDynamic` are
 * therefore never actually invoked at runtime for a `case class` context with
 * immutable fields: this class rewrites every occurrence away before the rule
 * is compiled. Fields that are still declared `var` never produce this shape
 * in the first place: the real setter is used directly, the assignment
 * mutates in place, and this class leaves it untouched.
 *
 * A lexer context's engine-internal bookkeeping (`input`, `lastRawMatched`, `lastLexeme`) is
 * ''not'' preserved by a `case class`'s generated `copy()` — it only knows
 * about its own constructor fields. Each rewritten update therefore carries
 * it over explicitly (see `LexerCtx.carryEngineStateFrom`), so that e.g. a
 * bound match (`case x @ "..." => ctx.count += 1; ...x...`) still sees the
 * matched text correctly even after a mutation earlier in the same rule.
 *
 * This class also strips every plain `ctx.asInstanceOf[Facade]` cast left
 * over from typing a *read* of a real field (`ctx.count`, unlike a mutation,
 * never goes through `applyDynamic`, so the cast around its receiver survives
 * untouched otherwise) back down to a bare reference. This isn't just
 * cosmetic: `ctx` is itself a generic `transparent inline def`, and inlining
 * it introduces its own synthetic local (`val $1$ = ctx-expr`) to avoid
 * re-evaluating the summoned context — so a plain `ctx.count` read actually
 * reaches this class as `{ val $1$ = <ctx>; $1$.count }`. That binding's own
 * declared type is frozen at whatever `ctx`'s macro produced (the facade
 * type) when it was first elaborated, and doesn't get updated just because a
 * later pass rewrites its initializer — so those trivial aliasing lets are
 * inlined away and dropped (see the `aliases` set built up in the `Block` case
 * below) rather than left in place with a now-stale declared type.
 *
 * A mutation can only be threaded through a `var`: when `ctxVar` is a `val`, as the context parameter of a token's
 * value is, each one is reported as an error instead, since the value can't hand an updated context back.
 *
 * @param ctxVar the local `var` (of type `Ctx`) that accumulates the updates
 * @param body the rule statements to rewrite
 * @param owner the owner to use when transforming the body
 */
private[internal] def rewriteCtxMutations(using
  Quotes,
  Diagnostics,
)(
  ctxVar: quotes.reflect.Symbol,
)(
  body: quotes.reflect.Term,
)(
  owner: quotes.reflect.Symbol,
): quotes.reflect.Term = {
  import quotes.reflect.*

  val Cast: PartialFunction[Term, Term] =
    case TypeApply(Select(inner, "asInstanceOf" | "$asInstanceOf$"), _) => inner
    case Select(inner, "$asInstanceOf$") => inner

  val SetterName: PartialFunction[String, String] = Function.unlift: encoded =>
    val decoded = NameTransformer.decode(encoded)
    Option.when(decoded.endsWith("_="))(decoded.stripSuffix("_="))

  val ApplyDynamicCall: PartialFunction[Term, (recv: Term, name: String, args: Term)] =
    case Apply(Apply(Select(recv, "applyDynamic"), List(Literal(StringConstant(name)))), List(args)) =>
      (recv, name, args)

  object unwrap:
    def apply(t: Term): Term = unapply(t).value

    @tailrec def unapply(t: Term): Some[Term] = t match
      case Inlined(_, Nil, e) => unapply(e)
      case Typed(e, _) => unapply(e)
      case Block(Nil, e) => unapply(e)
      case Cast(e) => unapply(e)
      case _ => Some(t)

  def copyWith(recv: Term, field: String, value: Term): Term =
    val cls = recv.tpe.typeSymbol
    val copySym = cls.methodMember("copy").head
    val args = cls.caseFields.map(f => if f.name == field then value else Select.unique(recv, f.name))
    val applied = recv.tpe.typeArgs match
      case Nil => recv.select(copySym)
      case targs => TypeApply(recv.select(copySym), targs.map(Inferred(_)))
    applied.appliedToArgs(args)

  // only a lexer context has engine state; a parser context is just its fields
  def carry(newCtx: Term, oldCtx: Term): Term =
    if newCtx.tpe <:< TypeRepr.of[LexerCtx] then
      '{ ${ newCtx.asExprOf[LexerCtx] }.carryEngineStateFrom(${ oldCtx.asExprOf[LexerCtx] }) }.asTerm
    else newCtx

  val ctxRef = Ref(ctxVar)
  val aliases = scala.collection.mutable.Set(ctxVar)

  def isCtxRef(t: Term): Boolean = aliases(unwrap(t).symbol)

  val SetterCall: PartialFunction[Term, (String, Term)] =
    case unwrap(ApplyDynamicCall(recv, SetterName(field), argSeq)) if isCtxRef(recv) =>
      val rhs = argSeq match
        case Typed(Repeated(List(r), _), _) => r
        case other => other
      (field, rhs)

  object rewriter extends TreeMap:
    override def transformTerm(tree: Term)(owner: Symbol): Term = tree match
      case SetterCall(_, rhs) if !ctxVar.flags.is(Flags.Mutable) =>
        error(show"Assign context fields before `Token[...]`, not inside its value", tree.pos)
        transformTerm(rhs)(owner): Unit
        Literal(UnitConstant())
      case SetterCall(field, rhs) =>
        // `carry` builds its result via a fresh `'{...}` quote, which re-homes the whole
        // spliced-in subtree (including any lambda nested in `rhs`, e.g. `s.count(_ == '\n')`)
        // to the ambient macro-body owner instead of `owner` -- confirmed under -Xcheck-macros
        // ("Block contains definition with different owners"). changeOwner puts it back.
        Assign(ctxRef, carry(copyWith(ctxRef, field, transformTerm(rhs)(owner)), ctxRef).changeOwner(owner))
      // a cast to the `ctx` facade; one to a scope, which `Token` and the extractors read, stays
      case cast @ Cast(unwrap(inner)) if isCtxRef(inner) && cast.tpe <:< TypeRepr.of[AlpacaCtx] =>
        inner
      case Ident(_) if aliases(tree.symbol) =>
        ctxRef
      case Block(stats, expr) if stats.nonEmpty =>
        val kept = stats.filter:
          case vd @ ValDef(_, _, Some(rhs)) if isCtxRef(transformTerm(rhs)(owner)) =>
            aliases += vd.symbol
            false
          case _ => true
        val newStats = kept.map(transformStatement(_)(owner))
        val newExpr = transformTerm(expr)(owner)
        if newStats.isEmpty then newExpr else Block(newStats, newExpr)
      case _ => super.transformTerm(tree)(owner)

  rewriter.transformTerm(body)(owner)
}

// $COVERAGE-ON$
