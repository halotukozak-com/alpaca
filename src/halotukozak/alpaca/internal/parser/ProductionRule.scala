package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.ParserCtx
import halotukozak.alpaca.internal.RevertedArray

/**
 * Type alias for semantic actions in the parser.
 *
 * An action is a function that takes the parser context and a sequence
 * of child values (from recognized symbols) and produces a result value.
 * Actions define how to build the abstract syntax tree or other output
 * from the parsed input.
 *
 * Note: The return type is Any for flexibility during compilation, but
 * the actual return value should match the expected result type for the
 * production rule being reduced.
 *
 * @tparam Ctx the parser context type
 */
private[parser] type Action[-Ctx <: ParserCtx] = (Ctx, RevertedArray[Any]) => Any

/**
 * A production with its semantic action, which a runtime [[ParseAction.Reduction]] reduces by. There is one per
 * production, shared by every cell of the parse table that reduces by it.
 *
 * @tparam Ctx the parser context type
 */
final class ProductionRule[-Ctx <: ParserCtx] private[parser] (
  val production: Production,
  val action: Action[Ctx],
)

private[parser] object ProductionRule:
  /** The rule of the synthetic start production, whose reduction accepts the input instead of running an action. */
  def accept[Ctx <: ParserCtx](production: Production): ProductionRule[Ctx] =
    ProductionRule(
      production,
      (_, _) => throw AlgorithmError("The start production is accepted, never reduced by an action"),
    )
