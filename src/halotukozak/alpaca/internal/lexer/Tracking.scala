package halotukozak
package alpaca
package internal
package lexer

import scala.annotation.implicitNotFound
import scala.compiletime.{constValueTuple, erasedValue, summonFrom}
import scala.annotation.publicInBinary
import halotukozak.commons.{containsOnly, toArrayOf}

/**
 * A per-token update for a single lexer-context field (a "fragment" such as
 * [[Line]] or [[Column]]).
 *
 * [[Tracking.materialize]] applies one `Tracking` instance to each case field of
 * the context whose type provides a `given`, threading the result through a
 * functional `copy`; fields whose type has no `Tracking` given are left
 * untouched by the hook (they only change in rule bodies).
 *
 * This is how tracking composes without inheritance: define a distinct field
 * type and a `given Tracking[YourType]` in its companion, then use it as a
 * context field.
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
   * [[Tracking]] update per case field whose type provides a `given`,
   * followed by the fixed step every context needs regardless of what it
   * tracks -- apply the rule body's context changes and record the lexeme
   * (see [[Hook]]). Cursor advancement itself already happened
   * earlier, in `Lexer`, before this hook runs.
   */
  @publicInBinary inline private[alpaca] def materialize[Ctx <: LexerCtx: Mirror.ProductOf as m]: Hook[Ctx] =
    materializeImpl[Ctx](
      fieldSteps[m.MirroredElemTypes](0),
      constValueTuple[m.MirroredElemLabels].toArrayOf[String](using containsOnly.refl),
      indexOfType[m.MirroredElemTypes, Line](0),
      indexOfType[m.MirroredElemTypes, Column](0),
    )

  /**
   * One `(index, update)` pair per case field that has a `given Tracking`;
   * fields with none are skipped entirely, rather than carried along as a
   * no-op, so that [[Hook.apply]] can tell -- without inspecting the
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

  /** The index of the first case field of type `F`, or -1. */
  inline private def indexOfType[Elems <: Tuple, F](index: Int): Int =
    inline erasedValue[Elems] match
      case _: EmptyTuple => -1
      case _: (F *: _) => index
      case _: (_ *: t) => indexOfType[t, F](index + 1)

  @publicInBinary private[Tracking] def materializeImpl[Ctx <: LexerCtx: Mirror.ProductOf](
    steps: List[(index: Int, update: Tracking[?])],
    fieldNames: Array[String],
    lineIndex: Int,
    columnIndex: Int,
  ): Hook[Ctx] = Hook(steps, fieldNames, lineIndex, columnIndex)

  /**
   * The hook `Lexer` runs after every token match. A plain public class
   * (rather than an anonymous function) because `materialize` is `inline` and
   * its type appears wherever `lexer` is called; it is still effectively
   * internal, since `internal.lexer` is never exported wholesale.
   *
   * All tracked fields are folded into a single `productIterator` snapshot,
   * mutated in place, and rebuilt with one `Mirror.fromProduct` -- one
   * allocation and one reflective reconstruction per token match, regardless
   * of how many fields are tracked, rather than one per field. Contexts with
   * no tracked fields (`steps.isEmpty`) skip the snapshot/rebuild entirely.
   *
   * A lexeme keeps the pre-match [[Line]]/[[Column]], so it points at its token's start.
   *
   * @param lineIndex   the index of the context's [[Line]] field, or -1
   * @param columnIndex the index of the context's [[Column]] field, or -1
   */
  final class Hook[Ctx <: LexerCtx] private[Tracking] (
    steps: List[(index: Int, update: Tracking[?])],
    fieldNames: Array[String],
    private[alpaca] val lineIndex: Int,
    private[alpaca] val columnIndex: Int,
  )(using m: Mirror.ProductOf[Ctx],
  ) extends ((Token[?, Ctx, ?], String, Ctx) => Ctx):

    def apply(token: Token[?, Ctx, ?], raw: String, ctx: Ctx): Ctx = {
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
              val lineAfter = if lineIndex < 0 then 0 else values(lineIndex).asInstanceOf[Int]
              val columnAfter = if columnIndex < 0 then 0 else values(columnIndex).asInstanceOf[Int]
              if lineIndex >= 0 then values(lineIndex) = ctx.productElement(lineIndex)
              if columnIndex >= 0 then values(columnIndex) = ctx.productElement(columnIndex)
              val name = info.name.raw
              c.lastLexeme = Lexeme(
                name = name,
                value = remapping(c),
                text = raw,
                fieldNames = fieldNames,
                fieldValues = values,
                lineIndex = lineIndex,
                columnIndex = columnIndex,
                lineAfter = lineAfter,
                columnAfter = columnAfter,
              )

        case IgnoredToken(_, modifyCtx) =>
          modifyCtx(afterFields).carryEngineStateFrom(afterFields)
      }
    }
