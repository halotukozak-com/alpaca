package halotukozak
package alpaca
package internal
package parser

import halotukozak.made.annotation.name
import halotukozak.mcodec.MCodec
import halotukozak.mcodec.annotation.flatten

/**
 * Represents a parse action in the LR parse table.
 *
 * Parse actions determine what the parser should do in each state
 * when encountering a symbol. It can either shift to a new state
 * or reduce by a production.
 *
 * @tparam Reduce what a reduction reduces by: its [[Production]] while the table is built, its [[ProductionRule]] at runtime
 */
private[parser] sealed trait ParseAction[+Reduce] extends Any

private[parser] object ParseAction:

  /**
   * Shift action: read the input symbol and move to a new state.
   */
  sealed case class Shift(state: Int) extends AnyVal, ParseAction[Nothing]

  /**
   * Reduce action: apply a production rule to reduce symbols.
   */
  sealed case class Reduction[+Reduce](rule: Reduce) extends AnyVal, ParseAction[Reduce]

  given Showable[ParseAction[Production]] =
    case Shift(state) => show"S$state"
    case Reduction(production) => show"$production"

  // $COVERAGE-OFF$
  // Local tagging enum instead of `derives MCodec` on ParseAction itself, which stays AnyVal.
  @flatten("type")
  private enum ActionExport:
    @name("shift") case Shift(state: Int)
    @name("reduce") case Reduce(production: Production)

  given MCodec[Production] => MCodec[ParseAction[Production]] = MCodec
    .derived[ActionExport]
    .transform(
      onWrite = {
        case Shift(state) => ActionExport.Shift(state)
        case Reduction(production) => ActionExport.Reduce(production)
      },
      onRead = {
        case ActionExport.Shift(state) => Shift(state)
        case ActionExport.Reduce(production) => Reduction(production)
      },
    )
// $COVERAGE-ON$
