package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.{AlgorithmError, Showable}

/**
 * An LR(0) item: a production with a dot position, deliberately without a lookahead.
 *
 * This is the unit of identity for the LR(0) automaton built during LALR(1) construction
 * (#504): two [[Item]]s that differ only by lookahead share the same `Core`, which is what
 * keeps that automaton's state count down to the LALR(1) count instead of the (often
 * 10-50x larger) canonical LR(1) count. Lookaheads are determined afterward by propagating
 * them over this automaton (see [[Lookaheads]]) rather than being threaded through closure.
 *
 * @param production  the grammar production
 * @param dotPosition the position of the dot (0 to production.rhs.size)
 */
private[parser] final case class Core(production: Production, dotPosition: Int):

  /**
   * The symbol immediately after the dot.
   *
   * Callers must guard with `!isLastItem`; on the last item this throws (the exact exception
   * depends on the production kind, not part of this method's contract).
   */
  def nextSymbol: Symbol = production match
    case Production.NonEmpty(_, rhs, _) => rhs(dotPosition)
    case _: Production.Empty => throw AlgorithmError(show"$this is the last item, has no next symbol")

  /** The core with the dot advanced by one position. Callers must guard with `!isLastItem`. */
  def nextCore: Core = Core(production, dotPosition + 1)

  /** Whether the dot is at the end of the production. */
  val isLastItem: Boolean = production match
    case Production.NonEmpty(_, rhs, _) => rhs.sizeIs == dotPosition
    case _: Production.Empty => true

private[parser] object Core:
  /** The core of a production with the dot at position 0. */
  def apply(production: Production): Core = Core(production, 0)

  given Ordering[Core] = Ordering.by[Core, Int](_.production.hashCode).orElseBy(_.dotPosition)

  given Showable[Core] =
    case Core(Production.NonEmpty(lhs, rhs, name), dotPosition) =>
      val (left, right) = rhs.splitAt(dotPosition)
      show"$lhs -> ${left.mkShow}•${right.mkShow}"
    case Core(Production.Empty(lhs, name), _) =>
      show"$lhs -> •${Symbol.Empty}"
