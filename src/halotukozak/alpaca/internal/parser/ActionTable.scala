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
 * production rule being reduced.
 *
 * @tparam Ctx the parser context type
 * @tparam R the result type
 */
private[parser] type Action[-Ctx <: ParserCtx] = (Ctx, RevertedArray[Any]) => Any

/**
 * An opaque type representing the parser action table.
 *
 * The action table maps productions to their semantic actions.
 * When a production is reduced during parsing, its action is executed
 * to compute the result value for the non-terminal on the left-hand side.
 *
 * @tparam Ctx the parser context type
 * @tparam R the result type
 */
opaque private[parser] type ActionTable[Ctx <: ParserCtx] =
  Map[Production, Action[Ctx]] // todo: Action should be based on Production type

@publicInBinary private[parser] object ActionTable:

  /**
   * Creates an ActionTable from a map of productions to actions.
   *
   * @param table the map of productions to their actions
   * @return an ActionTable
   */
  def apply[Ctx <: ParserCtx](table: Map[Production, Action[Ctx]]): ActionTable[Ctx] = table

  extension [Ctx <: ParserCtx, R](table: ActionTable[Ctx])
    /**
     * Gets the action for a production.
     *
     * @param production the production to get the action for
     * @return the semantic action for that production
     */
    def apply(production: Production): Action[Ctx] = table(production)
