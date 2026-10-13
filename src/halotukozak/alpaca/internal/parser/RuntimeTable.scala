package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.ParserCtx

import scala.annotation.{publicInBinary, tailrec}

/**
 * A parse action as the parser runs it: shift to a state, or reduce by a production's [[ProductionRule]].
 *
 * @tparam Ctx the parser context type
 */
private[parser] sealed trait RuntimeAction[-Ctx <: ParserCtx] extends Any

@publicInBinary private[parser] object RuntimeAction:
  sealed case class Shift(state: Int) extends AnyVal, RuntimeAction[ParserCtx]
  sealed case class Reduce[-Ctx <: ParserCtx](rule: ProductionRule[Ctx]) extends AnyVal, RuntimeAction[Ctx]

/**
 * The parse table as the parser runs it, built by the code [[ParseTable.toRuntime]] generates.
 *
 * @param rows one per state (dense, consecutive, starting at 0), each a symbol -> action map
 * @tparam Ctx the parser context type
 */
final class RuntimeTable[Ctx <: ParserCtx] @publicInBinary private[parser] (
  rows: Array[Map[Symbol, RuntimeAction[Ctx]]],
):
  import RuntimeAction.*

  /** The action for a given state and symbol, or `null` if the grammar accepts no such symbol there. */
  def get(state: Int, symbol: Symbol): RuntimeAction[Ctx] | Null = rows(state).get(symbol) match
    case Some(action) => action
    case None => null

  /** The state the parser moves to after reducing to `nonTerminal` with `state` uncovered on top of the stack. */
  def goto(state: Int, nonTerminal: NonTerminal): Int = rows(state).get(nonTerminal) match
    case Some(Shift(gotoState)) => gotoState
    case _ => throw AlgorithmError(show"No goto on $nonTerminal in state $state")

  /**
   * The terminals the input may continue with on `stateStack` (bottom first), sorted by name, with the end of the
   * input ahead of a token named `$`.
   *
   * LALR(1) merges lookaheads of states with the same core, so a state may reduce on a terminal that cannot follow
   * there. Each candidate is kept only if replaying the reductions on it reaches a shift or the accept.
   */
  def expectedTerminals(stateStack: collection.IndexedSeq[Int]): List[Terminal] =
    val reversedStack = stateStack.reverseIterator.toList
    rows(reversedStack.head).keysIterator
      .collect:
        case terminal: Terminal if leadsToShift(reversedStack, terminal) =>
          terminal
      .toList
      .sortBy(terminal => (terminal.displayName, terminal != Symbol.EOF))

  /** Whether `terminal` is shifted or accepted after the reductions it triggers on `reversedStack` (top first). */
  @tailrec private def leadsToShift(reversedStack: List[Int], terminal: Terminal): Boolean =
    get(reversedStack.head, terminal) match
      case null => false
      case Shift(_) => true
      case Reduce(rule) =>
        val production = rule.production
        val rest = reversedStack.drop(production.size)
        if production.lhs == Symbol.Start && rest.head == 0 then true
        else leadsToShift(goto(rest.head, production.lhs) :: rest, terminal)
