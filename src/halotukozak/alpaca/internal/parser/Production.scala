package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.{DebugSettings, NEL, Printable, Showable}
import halotukozak.mcodec.MCodec

import scala.quoted.ToExprFactory

/**
 * Represents a grammar production rule.
 *
 * A production defines how a non-terminal symbol can be expanded into
 * a sequence of symbols. For example: `E -> E + T` means the non-terminal
 * E can be produced by the sequence `E + T`.
 *
 * @param rhs the right-hand side sequence of symbols
 */
private[alpaca] enum Production(val rhs: NEL[Symbol.NonEmpty] | Symbol.Empty.type) derives ToExprFactory:

  /** The left-hand side non-terminal of the production. */
  val lhs: NonTerminal

  /** An optional name for the production. */
  val name: Printable | Null

  /**
   * Caches the case-class-derived hash instead of recomputing it on every call. `rhs` is a
   * `Vector`-backed sequence, so the default (non-cached) hashCode would rehash it from scratch
   * every time; this only ever computes it once. Used by [[Item]]'s cached `hashCode` (see #506),
   * which in turn speeds up every `State` (a `SortedSet[Item]`) used as a `stateIndex` map key
   * during LR construction -- and by the `Production` `Ordering` as its first comparison (see #507),
   * where unlike a per-instance counter it stays consistent with `equals` even if two
   * `Production` instances with identical fields are constructed separately.
   */
  override val hashCode: Int = (lhs, rhs, name).hashCode()
  override def equals(obj: Any): Boolean = obj match
    case that: Production => (this.lhs == that.lhs) && (this.rhs == that.rhs) && (this.name == that.name)
    case _ => false

  /**
   * Converts this production to an LR(0) item with a given lookahead.
   *
   * @param lookAhead the lookahead terminal (defaults to EOF)
   * @return an Item representing this production with the dot at position 0
   */
  def toItem(lookAhead: Terminal = Symbol.EOF)(using DebugSettings): Item = Item(this, 0, lookAhead)

  /** The number of symbols on the right-hand side, 0 for an empty production. */
  def size: Int = this match
    case NonEmpty(_, rhs, _) => rhs.size
    case Empty(_, _) => 0

  case NonEmpty(
    lhs: NonTerminal & Symbol.NonEmpty,
    override val rhs: NEL[Symbol.NonEmpty],
    name: Printable | Null = null,
  ) extends Production(rhs)

  case Empty(
    lhs: NonTerminal,
    name: Printable | Null = null,
  ) extends Production(Symbol.Empty)

private[alpaca] object Production:

  /** Showable instance for displaying productions in human-readable form. */
  given Showable[Production] =
    case NonEmpty(lhs, rhs, null) => show"$lhs -> ${rhs.mkShow(" ")}"
    case NonEmpty(lhs, rhs, name) => show"$lhs -> ${rhs.mkShow(" ")} (${name.nn})"
    case Empty(lhs, null) => show"$lhs -> ${Symbol.Empty}"
    case Empty(lhs, name) => show"$lhs -> ${Symbol.Empty} (${name.nn})"

  /**
   * Orders by the cached `hashCode` first, then by content, so that productions sharing a hash stay distinct in a
   * `SortedSet` (#722).
   */
  given Ordering[Production] =
    given Ordering[Symbol] = Ordering.by(symbol => (symbol.name.raw, symbol.isInstanceOf[Terminal]))
    Ordering
      .by[Production, Int](_.hashCode)
      .orElseBy(_.lhs.name.raw)
      .orElseBy[List[Symbol]] {
        case NonEmpty(_, rhs, _) => rhs.toList
        case Empty(_, _) => Nil
      }(using Ordering.Implicits.seqOrdering)
      .orElseBy(production => Option(production.name).map(_.raw))

  // $COVERAGE-OFF$
  private given MCodec[Printable | Null] = MCodec[Printable].nullable

  /**
   * The export codec, taking each production's source from `sources`, which has every production of the grammar.
   * NonEmpty/Empty share one flat shape rather than a tagged union; rhs.isEmpty distinguishes them.
   */
  def exportCodec(sources: Map[Production, Source]): MCodec[Production] = MCodec
    .derived[(lhs: Printable, rhs: List[Symbol], name: Printable | Null, source: Source)]
    .transform(
      onWrite = {
        case p @ NonEmpty(lhs, rhs, name) => (lhs = lhs.name, rhs = rhs.toList, name = name, source = sources(p))
        case p @ Empty(lhs, name) => (lhs = lhs.name, rhs = Nil, name = name, source = sources(p))
      },
      onRead = _ => throw UnsupportedOperationException("Production's export codec is write-only"),
    )
// $COVERAGE-ON$
