package halotukozak
package alpaca
package internal
package parser

import halotukozak.made.annotation.name
import halotukozak.mcodec.MCodec
import halotukozak.mcodec.annotation.flatten

import scala.annotation.publicInBinary
import scala.quoted.*

/**
 * Represents a parse action in the LR parse table.
 *
 * Parse actions determine what the parser should do in each state
 * when encountering a symbol. It can either shift to a new state
 * or reduce by a production.
 */
private[parser] sealed trait ParseAction extends Any

@publicInBinary private[parser] object ParseAction:

  /**
   * Shift action: read the input symbol and move to a new state.
   */
  sealed case class Shift(state: Int) extends AnyVal, ParseAction

  /**
   * Reduce action: apply a production rule to reduce symbols.
   *
   * @param production the index of the production in the parse table's productions, which is also the index of its
   *                   semantic action
   */
  sealed case class Reduction(production: Int) extends AnyVal, ParseAction

  // $COVERAGE-OFF$
  given ToExpr[ParseAction] with
    def apply(x: ParseAction)(using Quotes): Expr[ParseAction] = x match
      case Shift(state) => '{ ParseAction.Shift(${ Expr(state) }) }
      case Reduction(production) => '{ ParseAction.Reduction(${ Expr(production) }) }

  /** A parse action as exported, with a reduction's production written out rather than as an index. */
  @flatten("type")
  private[parser] enum Export:
    @name("shift") case Shift(state: Int)
    @name("reduce") case Reduce(production: Production)

  private[parser] object Export:
    given MCodec[Production] => MCodec[Export] = MCodec.derived
// $COVERAGE-ON$
