package halotukozak
package alpaca
package internal
package lexer

import scala.annotation.implicitNotFound
import scala.compiletime.{constValueTuple, erasedValue, summonFrom}
import scala.annotation.publicInBinary
import scala.deriving.Mirror
import halotukozak.commons.{containsOnly, toArrayOf}

/**
 * A per-token update for a single lexer-context field (a "fragment" such as
 * [[Line]] or [[Column]]).
 *
 * [[Tracking.materialize]] applies one `Tracking` instance to each case field of
 * the context for which a `given Tracking[FieldType]` resolves at the `lexer`
 * call, threading the result through a functional `copy`; fields with none are
 * left untouched by the hook (they only change in rule bodies).
 *
 * Lookup is ordinary given resolution at the `lexer` call: a `given` in lexical
 * scope there wins, otherwise the one in the field type's companion is used.
 * So a `given Tracking[Int]` in scope tracks every `Int` field of that lexer's
 * context, and a local `given Tracking[Column]` replaces [[Column]]'s own for
 * that lexer only.
 *
 * This is how tracking composes without inheritance: define a distinct field
 * type (e.g. an opaque type) and a `given Tracking[YourType]` in its companion,
 * then use it as a context field. A distinct type also keeps the update to the
 * fields meant for it, where a given for a common type like `Int` would reach
 * every field of that type.
 *
 * @tparam Field the fragment field type
 */
@implicitNotFound("No Tracking instance for the context fragment ${Field}")
trait Tracking[Field]:
  /**
   * @param matched the raw text of the token just matched
   * @param field   the fragment's current value
   * @return the fragment's new value
   */
  def apply(matched: String, field: Field): Field

object Tracking:

  /**
   * Derives the hook run after every token match for a context type: one
   * [[Tracking]] update per case field for which a `given` resolves at the
   * `lexer` call (see [[Tracking]] for the lookup),
   * followed by the fixed step every context needs regardless of what it
   * tracks -- apply the rule body's context changes and record the lexeme
   * (see [[materializeImpl]]). Cursor advancement itself already happened
   * earlier, in `Lexer`, before this hook runs.
   */
  @publicInBinary inline private[alpaca] def materialize[Ctx <: LexerCtx: Mirror.ProductOf as m]
    : (Token[?, Ctx, ?], String, Ctx) => Ctx =
    materializeImpl[Ctx](
      fieldSteps[m.MirroredElemTypes](0),
      constValueTuple[m.MirroredElemLabels].toArrayOf[String](using containsOnly.refl),
    )

  /**
   * One `(index, update)` pair per case field for which `summonFrom` finds a
   * `given Tracking` where `lexer` is called;
   * fields with none are skipped entirely, rather than carried along as a
   * no-op, so that [[Derived.apply]] can tell -- without inspecting the
   * context -- whether there is any field update to do at all.
   */
  inline private def fieldSteps[Elems <: Tuple](index: Int): List[(index: Int, update: Tracking[?])] =
    inline erasedValue[Elems] match
      case _: EmptyTuple => Nil
      case _: (h *: t) =>
        val rest = fieldSteps[t](index + 1)
        summonFrom:
          case tracking: Tracking[`h`] =>
            (index = index, update = tracking) :: rest
          case _ => rest

  /**
   * Named (not anonymous-per-inline-site) so [[materialize]] stays cheap to
   * inline. `materialize` is itself `inline`, so this gets constructed from
   * wherever `lexer` is ultimately called -- it can't be `private`/
   * `private[alpaca]` (unlike a plain member, `@publicInBinary` isn't allowed
   * on a class), so it's a plain, unqualified class instead; it's still
   * effectively internal since
   * `internal.lexer` is never exported wholesale, only specific symbols are.
   *
   * All tracked fields are folded into a single snapshot of the context's
   * fields, mutated in place, and rebuilt with one `Mirror.fromProduct` -- one
   * allocation and one reflective reconstruction per token match, regardless
   * of how many fields are tracked, rather than one per field. Contexts with
   * no tracked fields (`steps.isEmpty`) skip the snapshot/rebuild entirely.
   * The steps are unpacked into arrays once, so a match walks them with plain
   * loops instead of allocating a closure per pass.
   *
   * A lexeme keeps the pre-match value of every tracked field, so it describes its token's start.
   */
  @publicInBinary private[Tracking] def materializeImpl[Ctx <: LexerCtx: Mirror.ProductOf as m](
    steps: List[(index: Int, update: Tracking[?])],
    fieldNames: Array[String],
  ): (Token[?, Ctx, ?], String, Ctx) => Ctx = {
    val trackedIndices = steps.map(_.index).toArray
    val trackedUpdates = steps.map(_.update.asInstanceOf[Tracking[Any]]).toArray

    (token, raw, ctx) => {
      val afterFields =
        if trackedIndices.isEmpty then ctx
        else
          val values = fieldValuesOf(ctx)
          Array.better.foreachIndex(trackedIndices.length): step =>
            val index = trackedIndices(step)
            values(index) = trackedUpdates(step)(raw, values(index))
          val updated = m.fromProduct(Tuple.fromArray(values))
          updated.carryEngineStateFrom(ctx)

      token match {
        case DefinedToken(info, modifyCtx, remapping) =>
          val modified = modifyCtx(afterFields).carryEngineStateFrom(afterFields)
          val values = fieldValuesOf(modified)
          Array.better.foreachIndex(trackedIndices.length): step =>
            val index = trackedIndices(step)
            values(index) = ctx.productElement(index)
          val name = info.name.raw
          modified.lastLexeme = Lexeme(
            name = name,
            value = remapping(modified),
            text = raw,
            fieldNames = fieldNames,
            fieldValues = values,
          )
          modified

        case IgnoredToken(_, modifyCtx) =>
          modifyCtx(afterFields).carryEngineStateFrom(afterFields)
      }
    }
  }

  // a fresh array of the context's fields, sized up front rather than grown through `productIterator.toArray`
  private def fieldValuesOf(ctx: Product): Array[Any] = Array.better.tabulate(ctx.productArity)(ctx.productElement)
