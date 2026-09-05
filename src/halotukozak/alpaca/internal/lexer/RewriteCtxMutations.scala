package halotukozak
package alpaca
package internal
package lexer

import scala.annotation.tailrec
import scala.reflect.NameTransformer

// $COVERAGE-OFF$

/**
 * Rewrites structural field-mutation syntax on a lexer context back into
 * functional updates, so that a `case class` context with immutable (`val`)
 * fields can still be "mutated" with ordinary syntax inside a lexer rule:
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
 * Engine-internal bookkeeping (`text`, `lastRawMatched`, `lastLexeme`) is
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
 * @param ctxVar the local `var` (of type `Ctx`) that accumulates the updates
 * @param body the rule statements to rewrite
 * @param owner the owner to use when transforming the body
 */
private[lexer] def rewriteCtxMutations(
  using quotes: Quotes,
)(
  ctxVar: quotes.reflect.Symbol,
)(
  body: quotes.reflect.Term,
)(
  owner: quotes.reflect.Symbol,
): quotes.reflect.Term =
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

  def carry(newCtx: Term, oldCtx: Term): Term =
    '{ ${ newCtx.asExprOf[LexerCtx] }.carryEngineStateFrom(${ oldCtx.asExprOf[LexerCtx] }) }.asTerm

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
      case SetterCall(field, rhs) =>
        // `carry` builds its result via a fresh `'{...}` quote, which re-homes the whole
        // spliced-in subtree (including any lambda nested in `rhs`, e.g. `s.count(_ == '\n')`)
        // to the ambient macro-body owner instead of `owner` -- confirmed under -Xcheck-macros
        // ("Block contains definition with different owners"). changeOwner puts it back.
        Assign(ctxRef, carry(copyWith(ctxRef, field, transformTerm(rhs)(owner)), ctxRef).changeOwner(owner))
      case Cast(unwrap(inner)) if isCtxRef(inner) =>
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

// $COVERAGE-ON$
