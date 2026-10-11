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
 * The action table holds the semantic actions of the productions, indexed like the parse table's productions.
 * When a production is reduced during parsing, its action is executed
 * to compute the result value for the non-terminal on the left-hand side.
 *
 * The synthetic start production, at index 0, is never reduced by an action, so the array starts with the action of
 * production 1.
 *
 * @tparam Ctx the parser context type
 */
opaque private[parser] type ActionTable[Ctx <: ParserCtx] = Array[Action[Ctx]]

@publicInBinary private[parser] object ActionTable:

  /**
   * Creates an ActionTable from the actions of the productions after the start production, in the parse table's order.
   *
   * @param actions the actions, the one of production `index` at `index - 1`
   * @return an ActionTable
   */
  def apply[Ctx <: ParserCtx](actions: Array[Action[Ctx]]): ActionTable[Ctx] = actions

  extension [Ctx <: ParserCtx](table: ActionTable[Ctx])
    /**
     * Gets the action for a production.
     *
     * @param production the index of the production in the parse table's productions, never the start production's 0
     * @return the semantic action for that production
     */
    def apply(production: Int): Action[Ctx] = table(production - 1)
