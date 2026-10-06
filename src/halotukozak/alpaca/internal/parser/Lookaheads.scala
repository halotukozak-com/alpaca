package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.DebugSettings

import scala.collection.mutable

/**
 * Computes LALR(1) lookaheads for every kernel item of every state of an [[LR0Automaton]]
 * (#504), by spontaneous generation and propagation of lookaheads: Aho, Lam, Sethi & Ullman, *Compilers: Principles,
 * Techniques, and Tools* (2nd ed.), section 4.7.5 "Efficient Construction of LALR Parsing Tables". (Not DeRemer &
 * Pennello 1982, which computes the same sets differently, from the reads/includes/lookback relations.)
 *
 * For each kernel item, this closes it once on its own, seeded with the placeholder lookahead
 * [[Symbol.Dummy]] (reusing [[State.fromItem]], the same closure [[ParseTable]] uses once real
 * lookaheads are known). Within that one-off closure, whatever a resulting item's lookahead is
 * tells us how the *target* item (the one reached by shifting past it) gets its lookahead:
 *   - a real terminal means that terminal is generated spontaneously for the target item,
 *     regardless of what the seed kernel item's own lookahead eventually turns out to be.
 *   - the placeholder surviving through means the target item's lookahead set is whatever the
 *     seed kernel item's turns out to be -- a propagation edge, resolved below.
 *
 * Propagation edges are then followed to a fixed point over the automaton (small relative to
 * canonical LR(1) by construction), rather than over an exploded per-lookahead state space.
 */
private[parser] object Lookaheads:
  def apply(
    automaton: LR0Automaton,
    productionsByLhs: Map[NonTerminal, List[Production]],
    firstSet: FirstSet,
  )(using DebugSettings,
  ): IndexedSeq[Map[Core, Set[Terminal]]] = {
    val lookaheads =
      automaton.kernels.map(kernel => mutable.Map.from(kernel.iterator.map(_ -> mutable.Set.empty[Terminal])))
    val propagatesTo =
      automaton.kernels.map(kernel => mutable.Map.from(kernel.iterator.map(_ -> List.empty[(Int, Core)])))

    val worklist = mutable.ArrayDeque.empty[(Int, Core)]

    // The augmented start item is the one place a lookahead is known outright, not derived.
    // It must be seeded onto the worklist too, or nothing ever propagates from it.
    lookaheads(0)(automaton.kernels(0).head) += Symbol.EOF
    worklist.append((0, automaton.kernels(0).head))

    for
      stateId <- automaton.states.indices
      kernelCore <- automaton.kernels(stateId)
      seed = Item(kernelCore.production, kernelCore.dotPosition, Symbol.Dummy)
      dummyClosure = State.fromItem(State.empty, seed, productionsByLhs, firstSet)
    do {
      for item <- dummyClosure if !item.isLastItem do {
        val targetState = automaton.goto(stateId)(item.nextSymbol)
        val targetCore = Core(item.production, item.dotPosition + 1)

        if item.lookAhead == Symbol.Dummy then propagatesTo(stateId)(kernelCore) ::= (targetState, targetCore)
        else if lookaheads(targetState)(targetCore).add(item.lookAhead) then worklist.append((targetState, targetCore))
      }
    }

    while worklist.nonEmpty do
      val (stateId, kernelCore) = worklist.removeHead()
      for (targetState, targetCore) <- propagatesTo(stateId)(kernelCore) do
        val toAdd = lookaheads(stateId)(kernelCore).diff(lookaheads(targetState)(targetCore))
        if toAdd.nonEmpty then
          lookaheads(targetState)(targetCore) ++= toAdd
          worklist.append((targetState, targetCore))

    lookaheads.map(_.view.mapValues(_.toSet).toMap)
  }
