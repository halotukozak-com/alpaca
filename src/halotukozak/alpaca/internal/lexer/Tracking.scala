package halotukozak
package alpaca
package internal
package lexer

import scala.annotation.implicitNotFound
import scala.compiletime.constValueTuple
import scala.annotation.publicInBinary
import halotukozak.commons.{containsOnly, toArrayOf}

/**
 * A per-token update for a single lexer-context field (a "fragment" such as
 * [[Line]] or [[Column]]).
 *
 * A field is tracked when its type's companion provides a `given Tracking`
 * (for an opaque type, the object of the same name next to it).
 * [[Tracking.materialize]] applies that instance to the field after every
 * match, threading the result through a functional `copy`; other fields only
 * change in rule bodies. A `given Tracking[Int]` elsewhere in scope does not
 * track `Int` fields -- give the field its own type instead:
 * {{{
 * opaque type Offset <: Int = Int
 * object Offset:
 *   val Start: Offset = 0
 *   given Tracking[Offset] = (matched, offset) => offset + matched.length
 *
 * case class MyCtx(offset: Offset = Offset.Start) extends LexerCtx
 * }}}
 *
 * @tparam F the fragment field type
 */
@implicitNotFound("No Tracking instance for the context fragment ${F}")
trait Tracking[F]:
  /**
   * @param matched the raw text of the token just matched
   * @param field   the fragment's current value
   * @return the fragment's new value
   */
  def apply(matched: String, field: F): F

object Tracking:

  /**
   * Derives the hook run after every token match for a context type: one
   * [[Tracking]] update per case field whose type's companion provides a `given`,
   * followed by the fixed step every context needs regardless of what it
   * tracks -- apply the rule body's context changes and record the lexeme
   * (see [[materializeImpl]]). Cursor advancement itself already happened
   * earlier, in `Lexer`, before this hook runs.
   */
  @publicInBinary inline private[alpaca] def materialize[Ctx <: LexerCtx: Mirror.ProductOf as m]
    : (Token[?, Ctx, ?], String, Ctx) => Ctx =
    materializeImpl[Ctx](
      fieldSteps[m.MirroredElemTypes],
      constValueTuple[m.MirroredElemLabels].toArrayOf[String](using containsOnly.refl),
    )

  /**
   * One `(index, update)` pair per case field whose type's companion has a `given Tracking`;
   * fields with none are skipped entirely, rather than carried along as a
   * no-op, so that [[Derived.apply]] can tell -- without inspecting the
   * context -- whether there is any field update to do at all.
   */
  inline private def fieldSteps[Elems <: Tuple]: List[(index: Int, update: Tracking[?])] = ${ fieldStepsImpl[Elems] }

  @publicInBinary private[Tracking] def fieldStepsImpl[Elems <: Tuple: Type](using Quotes)
    : Expr[List[(index: Int, update: Tracking[?])]] =
    given Diagnostics = Diagnostics()
    def steps[Rest <: Tuple: Type](index: Int): List[Expr[(index: Int, update: Tracking[?])]] = Type.of[Rest] match
      case '[EmptyTuple] => Nil
      case '[field *: rest] =>
        val tail = steps[rest](index + 1)
        companionTracking[field].fold(tail)(update => '{ (index = ${ Expr(index) }, update = $update) } :: tail)
    Expr.ofList(steps[Elems](0))

  /**
   * The `given Tracking[Field]` declared in (or inherited by) the companion of `Field` -- for an opaque type, the
   * object of the same name next to it. Givens elsewhere in scope are ignored, so e.g. a `given Tracking[Int]` never
   * turns every `Int` field into a tracked one.
   */
  private def companionTracking[Field: Type](using Quotes, Diagnostics): Option[Expr[Tracking[Field]]] =
    import quotes.reflect.*
    val fieldType = TypeRepr.of[Field].dealiasKeepOpaques.typeSymbol
    // An opaque type read from TASTy has no companion link, so look for its namesake object instead.
    val companion =
      if fieldType.flags.is(Flags.Opaque) then
        fieldType.owner.declarations
          .find(decl => decl.isTerm && decl.flags.is(Flags.Module) && decl.name == fieldType.name)
          .getOrElse(Symbol.noSymbol)
      else fieldType.companionModule
    if companion.isNoSymbol then None
    else
      val companionClass = companion.moduleClass
      val tracking = TypeRepr.of[Tracking[Field]]
      (companionClass.fieldMembers ++ companionClass.methodMembers)
        .filter(_.flags.is(Flags.Given))
        .map(Ref(companion).select)
        .filter(_.tpe.widenTermRefByName <:< tracking)
        .sortBy(_.symbol.name) match
        case Nil => None
        case tracked :: Nil => Some(tracked.asExprOf[Tracking[Field]])
        case givens =>
          errorAndAbort(
            show"Ambiguous Tracking for ${TypeRepr.of[Field].show(using Printer.TypeReprShortCode).showRaw}: ${companion.name.showRaw} provides ${givens.map(_.symbol.name.showRaw).mkShow(", ")}",
            companion.pos.getOrElse(Position.ofMacroExpansion),
          )

  /**
   * Named (not anonymous-per-inline-site) so [[materialize]] stays cheap to
   * inline. `materialize` is itself `inline`, so this gets constructed from
   * wherever `lexer` is ultimately called -- it can't be `private`/
   * `private[alpaca]` (unlike a plain member, `@publicInBinary` isn't allowed
   * on a class), so it's a plain, unqualified class instead; it's still
   * effectively internal since
   * `internal.lexer` is never exported wholesale, only specific symbols are.
   *
   * All tracked fields are folded into a single `productIterator` snapshot,
   * mutated in place, and rebuilt with one `Mirror.fromProduct` -- one
   * allocation and one reflective reconstruction per token match, regardless
   * of how many fields are tracked, rather than one per field. Contexts with
   * no tracked fields (`steps.isEmpty`) skip the snapshot/rebuild entirely.
   *
   * A lexeme keeps the pre-match value of every tracked field, so it describes its token's start.
   */
  @publicInBinary private[Tracking] def materializeImpl[Ctx <: LexerCtx: Mirror.ProductOf as m](
    steps: List[(index: Int, update: Tracking[?])],
    fieldNames: Array[String],
  ): (Token[?, Ctx, ?], String, Ctx) => Ctx = (token, raw, ctx) => {
    val afterFields =
      if steps.isEmpty then ctx
      else
        val values = ctx.productIterator.toArray
        steps.foreach:
          case (index, update: Tracking[Any] @unchecked) =>
            values(index) = update(raw, values(index))
        val updated = m.fromProduct(Tuple.fromArray(values))
        updated.carryEngineStateFrom(ctx)

    token match {
      case DefinedToken(info, modifyCtx, remapping) =>
        modifyCtx(afterFields)
          .carryEngineStateFrom(afterFields)
          .tap: c =>
            val values = c.productIterator.toArray
            steps.foreach(step => values(step.index) = ctx.productElement(step.index))
            val name = info.name.raw
            c.lastLexeme = Lexeme(
              name = name,
              value = remapping(c),
              text = raw,
              fieldNames = fieldNames,
              fieldValues = values,
            )

      case IgnoredToken(_, modifyCtx) =>
        modifyCtx(afterFields).carryEngineStateFrom(afterFields)
    }
  }
