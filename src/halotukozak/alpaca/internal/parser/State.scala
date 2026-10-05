package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.DebugSettings

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet

/**
 * An opaque type representing a parser state.
 *
 * In LR parsing, a state is a set of LR(1) items that represent the
 * parser's current position in recognizing various productions. Each
 * state knows which symbols can be shifted and which items might be reduced.
 */
opaque private[parser] type State <: SortedSet[Item] = SortedSet[Item]

private[parser] object State:

  val empty: State = SortedSet.empty[Item](
    using Ordering
      .by[Item, String](_.production.lhs.name.raw)
      .orElseBy(_.production)
      .orElseBy(_.dotPosition)
      .orElseBy(_.lookAhead.name.raw),
  )

  /**
   * Constructs a state closure from a single item.
   *
   * This computes the closure of an item set by recursively adding items
   * for all productions of non-terminals that appear after the dot.
   *
   * @param state the current state to add to
   * @param item the item to close
   * @param productionsByLhs all grammar productions, indexed by their LHS non-terminal
   * @param firstSet the FIRST sets for lookahead computation
   * @return the closed state
   */
  def fromItem(
    state: State,
    item: Item,
    productionsByLhs: Map[NonTerminal, List[Production]],
    firstSet: FirstSet,
  )(using DebugSettings,
  ): State = {
    @tailrec def loop(state: State, pending: Set[Item], worklist: List[Item]): State = worklist match
      case Nil => state
      case item :: rest if item.isLastItem => loop(state + item, pending, rest)
      case item :: rest =>
        item.nextSymbol match
          case nt: NonTerminal =>
            val newState = state + item
            val lookAheads = item.nextTerminals(firstSet)
            val newItems = productionsByLhs
              .getOrElse(nt, Nil)
              .iterator
              .flatMap(production => lookAheads.iterator.map(production.toItem))
              .filterNot(candidate => newState.contains(candidate) || pending.contains(candidate))
              .toList
            loop(newState, pending ++ newItems, newItems ::: rest)
          case _: Terminal => loop(state + item, pending, rest)

    loop(state, Set.empty, item :: Nil)
  }
