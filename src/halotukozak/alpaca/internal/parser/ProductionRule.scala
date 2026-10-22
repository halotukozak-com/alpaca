package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.ParserCtx
import halotukozak.alpaca.internal.RevertedArray

import scala.annotation.publicInBinary

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
 * production rule being reduced. An action that assigns context fields
 * returns its value wrapped in a [[CtxUpdated]] with the updated context.
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
/**
 * What an action that assigns context fields returns: its `value`, and the context the parse goes on with. Only
 * such actions allocate one; the rest return their value directly.
 *
 * Not `private`: the actions are built wherever a parser is defined.
 */
final class CtxUpdated @publicInBinary private[alpaca] (val value: Any, val ctx: ParserCtx)

final class ProductionRule[-Ctx <: ParserCtx] @publicInBinary private[parser] (
  val production: Production,
  val action: Action[Ctx],
)

@publicInBinary private[parser] object ProductionRule:
  /** The rule of the synthetic start production, whose reduction accepts the input instead of running an action. */
  def accept[Ctx <: ParserCtx](production: Production): ProductionRule[Ctx] =
    ProductionRule(
      production,
      (_, _) => throw AlgorithmError("The start production is accepted, never reduced by an action"),
    )
