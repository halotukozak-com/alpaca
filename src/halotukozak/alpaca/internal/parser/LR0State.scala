package halotukozak
package alpaca
package internal
package parser

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet

/**
 * An opaque type representing a state of the LR(0) automaton built during LALR(1)
 * construction (#504).
 *
 * Unlike [[State]] (a set of lookahead-carrying [[Item]]s), closure here never consults
 * `FirstSet` -- it only follows productions by LHS -- which is the other half (besides the
 * smaller state count from [[Core]] merging) of why this automaton is cheap to build.
 */
opaque private[parser] type LR0State <: SortedSet[Core] = SortedSet[Core]

private[parser] object LR0State:

  val empty: LR0State = SortedSet.empty[Core]

  extension (state: LR0State) {

    /**
     * Groups this state's non-final items by the symbol they can shift on next.
     *
     * @return a map from each possible step symbol to the cores that shift on it
     */
    def itemsByNextSymbol: Map[Symbol, List[Core]] =
      state.iterator.filterNot(_.isLastItem).toList.groupBy(_.nextSymbol)
  }

  /**
   * Computes the state reached after shifting a symbol.
   *
   * @param cores            the cores that shift on the symbol being stepped to (see [[itemsByNextSymbol]])
   * @param productionsByLhs all grammar productions, indexed by their LHS non-terminal
   * @return the new state
   */
  def nextState(cores: List[Core], productionsByLhs: Map[NonTerminal, List[Production]]): LR0State =
    cores.foldLeft(LR0State.empty)((acc, core) => LR0State.fromCore(acc, core.nextCore, productionsByLhs))

  /**
   * Constructs a state closure from a single LR(0) item.
   *
   * @param state            the current state to add to
   * @param core             the item to close
   * @param productionsByLhs all grammar productions, indexed by their LHS non-terminal
   * @return the closed state
   */
  def fromCore(state: LR0State, core: Core, productionsByLhs: Map[NonTerminal, List[Production]]): LR0State = {
    @tailrec def loop(state: LR0State, pending: Set[Core], worklist: List[Core]): LR0State = worklist match
      case Nil => state
      case core :: rest if core.isLastItem => loop(state + core, pending, rest)
      case core :: rest =>
        core.nextSymbol match
          case nt: NonTerminal =>
            val newState = state + core
            val newCores = productionsByLhs
              .getOrElse(nt, Nil)
              .iterator
              .map(Core(_))
              .filterNot(candidate => newState.contains(candidate) || pending.contains(candidate))
              .toList
            loop(newState, pending ++ newCores, newCores ::: rest)
          case _: Terminal => loop(state + core, pending, rest)

    loop(state, Set.empty, core :: Nil)
  }
