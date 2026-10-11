package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.parser.ParseAction.*
import halotukozak.mcodec.MCodec

import scala.annotation.tailrec
import scala.collection.mutable
import scala.quoted.*
import scala.quoted.QuotedFactoryGivens.given

/**
 * An opaque type representing the LR parse table.
 *
 * State IDs are dense consecutive integers starting at 0, so the table is
 * stored as an array indexed by state, with each cell holding a symbol ->
 * action map. Lookup is a single array index plus one symbol hash, with no
 * boxing on the state key.
 *
 * A reduction refers to its production by index into `productions`, which holds each production once; the synthetic
 * start production is at index 0.
 */
opaque private[parser] type ParseTable = (rows: Array[Map[Symbol, ParseAction]], productions: Array[Production])

private[parser] object ParseTable:
  extension (table: ParseTable) {

    /**
     * Gets the parse action for a given state and symbol.
     *
     * @param state the current parser state
     * @param symbol the symbol being processed
     * @return the parse action to take
     * @throws AlgorithmError if no action is defined for this state/symbol combination
     */
    def action(state: Int, symbol: Symbol): ParseAction = table.rows(state).get(symbol) match
      case Some(action) => action
      case None =>
        val expected = table.rows(state).keysIterator.toList.sortBy(_.show).mkShow(", ")
        throw AlgorithmError(show"Unexpected symbol '$symbol' in state $state. Expected one of: $expected")

    /** The parse action for a given state and symbol, or `null` if the grammar accepts no such symbol there. */
    def get(state: Int, symbol: Symbol): ParseAction | Null = table.rows(state).get(symbol) match
      case Some(action) => action
      case None => null

    /**
     * The terminals the input may continue with on `stateStack` (bottom first), sorted by name, with the end of the
     * input ahead of a token named `$`.
     *
     * LALR(1) merges lookaheads of states with the same core, so a state may reduce on a terminal that cannot follow
     * there. Each candidate is kept only if replaying the reductions on it reaches a shift or the accept.
     */
    def expectedTerminals(stateStack: collection.IndexedSeq[Int]): List[Terminal] =
      val reversedStack = stateStack.reverseIterator.toList
      table
        .rows(reversedStack.head)
        .keysIterator
        .collect:
          case terminal: Terminal if table.leadsToShift(reversedStack, terminal) =>
            terminal
        .toList
        .sortBy(terminal => (terminal.displayName, terminal != Symbol.EOF))

    /** Whether `terminal` is shifted or accepted after the reductions it triggers on `reversedStack` (top first). */
    @tailrec private def leadsToShift(reversedStack: List[Int], terminal: Terminal): Boolean =
      table.get(reversedStack.head, terminal) match
        case null => false
        case Shift(_) => true
        case Reduction(index) =>
          val production = table.production(index)
          val rest = reversedStack.drop(production.size)
          if production.lhs == Symbol.Start && rest.head == 0 then true
          else table.leadsToShift(table.goto(rest.head, production.lhs) :: rest, terminal)

    /** The state the parser moves to after reducing to `nonTerminal` with `state` uncovered on top of the stack. */
    private[parser] def goto(state: Int, nonTerminal: NonTerminal): Int =
      table.action(state, nonTerminal).runtimeChecked match
        case Shift(gotoState) => gotoState

    /** The production a [[ParseAction.Reduction]] reduces by. */
    private[parser] def production(index: Int): Production = table.productions(index)

    private def allSymbols: List[Symbol] =
      table.rows.iterator.flatMap(_.keysIterator).distinct.toList

    private def showAction(action: ParseAction): Shown = action match
      case Shift(state) => show"S$state"
      case Reduction(index) => show"${table.production(index)}"

    // $COVERAGE-OFF$
    private def exported(action: ParseAction): ParseAction.Export = action match
      case Shift(state) => ParseAction.Export.Shift(state)
      case Reduction(index) => ParseAction.Export.Reduce(table.production(index))
    // $COVERAGE-ON$

    /**
     * Converts the parse table to CSV format for debugging.
     *
     * Creates a table with states as rows and symbols as columns,
     * showing the action for each state/symbol combination.
     *
     * @return a Csv representation of the parse table
     */
    def toCsv: Csv = {
      val symbols = table.allSymbols

      val headers = show"State" :: symbols.map(s => show"$s")
      val rows = table.rows.indices
        .map: i =>
          val row = table.rows(i)
          show"$i" :: symbols.map(s => row.get(s).fold(show"")(table.showAction))
        .toList

      Csv(headers, rows)
    }
  }

  /**
   * Constructs the LALR(1) parse table from a list of productions (#504).
   *
   * This builds the (much smaller than canonical-LR(1)) LR(0) automaton first
   * ([[LR0Automaton]]), determines each state's lookaheads by propagation over it
   * ([[Lookaheads]]), then closes each state once more with its real lookaheads (reusing
   * [[State.fromItem]]) to read off reduce actions; shift actions come straight from the
   * automaton's already-computed goto transitions.
   *
   * @param productions the grammar productions, the synthetic start production first; a reduction refers to a
   *                    production by its index here
   * @param sources where each of `productions` is defined, for conflict messages
   * @return the constructed parse table
   */
  def apply(using
    Quotes,
    Diagnostics,
  )(
    productions: List[Production],
    sources: Map[Production, Source],
    conflictResolutionTable: ConflictResolutionTable,
  ): ParseTable = {
    val productionArray = productions.toArray
    val productionIndex = productions.iterator.zipWithIndex.toMap
    val reported = mutable.HashSet.empty[Set[Production] | (Symbol, Production)]

    // $COVERAGE-OFF$
    def raiseReduceReduceConflict(red1: Reduction, red2: Reduction, path: List[Symbol]): Unit =
      val (production1, production2) = (productionArray(red1.production), productionArray(red2.production))
      if reported.add(Set(production1, production2)) then
        error(
          show"""
                |Reduce $production1 vs Reduce $production2
                |In situation like:
                |${path.filter(_ != Symbol.EOF).mkShow("", " ", " ...")}
                |Conflicting production: $production1 (line ${sources(production1).line + 1})
                |Consider marking one of the productions to be before or after the other
                |""".trimMargin,
          sources(production2),
        )

    def raiseShiftReduceConflict(symbol: Symbol, red: Reduction, path: List[Symbol]): Unit =
      val production = productionArray(red.production)
      if reported.add((symbol, production)) then
        error(
          show"""
                |Shift "$symbol" vs Reduce $production
                |In situation like:
                |${path.filter(_ != Symbol.EOF).mkShow("", " ", " ...")}
                |Consider marking production $production to be before or after "$symbol"
                |""".trimMargin,
          sources(production),
        )
    // $COVERAGE-ON$

    val firstSet = FirstSet(productions)
    val productionsByLhs = productions.groupBy(_.lhs)
    val automaton = LR0Automaton(productionsByLhs)
    val lookaheads = Lookaheads(automaton, productionsByLhs, firstSet)

    val tableRows = mutable.ArrayBuffer.fill(automaton.states.length)(mutable.HashMap.empty[Symbol, ParseAction])

    def addToTable(stateId: Int, symbol: Symbol, action: ParseAction): Unit =
      val row = tableRows(stateId)
      row.get(symbol) match
        case None => row.update(symbol, action)
        case Some(existingAction) =>
          conflictResolutionTable.get(existingAction, action)(symbol, productionArray(_)) match
            case Some(action) => row.update(symbol, action)
            case None =>
              val path = toPath(stateId, List(symbol))
              (existingAction, action) match
                case (red1: Reduction, red2: Reduction) => raiseReduceReduceConflict(red1, red2, path)
                case (Shift(_), red: Reduction) => raiseShiftReduceConflict(symbol, red, path)
                case (red: Reduction, Shift(_)) => raiseShiftReduceConflict(symbol, red, path)
                case (Shift(_), Shift(_)) => throw AlgorithmError("Shift-Shift conflict should never happen")

    // read off the automaton, not the table: a shift lost to an unresolved conflict is still a way into its state
    lazy val predecessor: Array[(stateId: Int, symbol: Symbol) | Null] =
      val result = Array.fill[(stateId: Int, symbol: Symbol) | Null](automaton.states.length)(null)
      for
        srcId <- automaton.goto.indices
        (symbol, targetId) <- automaton.goto(srcId)
        if targetId != 0 && result(targetId) == null
      do result(targetId) = (stateId = srcId, symbol = symbol)
      result

    @tailrec def toPath(stateId: Int, acc: List[Symbol]): List[Symbol] =
      if stateId == 0 then acc
      else
        predecessor(stateId) match
          case null => throw AlgorithmError(show"No predecessor state found for state $stateId")
          case (stateId = sourceStateId, symbol = symbol) => toPath(sourceStateId, symbol :: acc)

    for stateId <- automaton.states.indices do {
      val kernelItems = for
        kernelCore <- automaton.kernels(stateId)
        la <- lookaheads(stateId)(kernelCore)
      yield Item(kernelCore.production, kernelCore.dotPosition, la)

      val currState =
        kernelItems.foldLeft(State.empty)((acc, item) => State.fromItem(acc, item, productionsByLhs, firstSet))

      for item <- currState if item.isLastItem do
        addToTable(stateId, item.lookAhead, Reduction(productionIndex(item.production)))

      for (stepSymbol, targetStateId) <- automaton.goto(stateId) do addToTable(stateId, stepSymbol, Shift(targetStateId))
    }

    // $COVERAGE-OFF$
    // every conflict is already reported at its own production; abort without an extra error at the call site
    abortOnErrors()
    // $COVERAGE-ON$

    (rows = Array.better.tabulate(tableRows.length)(tableRows(_).toMap), productions = productionArray)
  }

  given Showable[ParseTable] = table => {
    val symbols = table.allSymbols

    def centerText(text: String, width: Int = 10): String =
      if text.length >= width then text
      else
        val padding = width - text.length
        val leftPad = padding / 2
        val rightPad = padding - leftPad
        (" " * leftPad) + text + (" " * rightPad)

    val result = new StringBuilder
    result.append(centerText("State"))
    result.append("|")
    for s <- symbols do
      result.append(centerText(s.show))
      result.append("|")

    for i <- table.rows.indices do
      val row = table.rows(i)
      result.append('\n')
      result.append(centerText(i.toString))
      result.append("|")
      for s <- symbols do
        result.append(centerText(row.get(s).fold("")(table.showAction)))
        result.append("|")
    result.append('\n')
    result.result().showRaw
  }

  // $COVERAGE-OFF$
  given ToExpr[ParseTable]:
    def apply(table: ParseTable)(using quotes: Quotes): Expr[ParseTable] = {
      type Row = Map[Symbol, ParseAction]
      type RowBuilder = mutable.Builder[(Symbol, ParseAction), Row]

      def rowExpr(row: Row): Expr[Row] = avoidTooLargeMethod[(Symbol, ParseAction), Row, RowBuilder](
        builder = '{ Map.newBuilder },
        elements = row.map(Expr(_)),
        empty = '{ Map.empty },
      )

      val rowsExpr = avoidTooLargeMethod[Row, Array[Row], mutable.ArrayBuilder[Row]](
        builder = '{ mutable.ArrayBuilder.ofRef[Row].tap(_.sizeHint(${ Expr(table.rows.length) })) },
        elements = table.rows.map(rowExpr),
        empty = '{ Array.empty[Row] },
      )

      // each production once; the cells refer to them by index
      val productionsExpr = avoidTooLargeMethod[Production, Array[Production], mutable.ArrayBuilder[Production]](
        builder = '{ mutable.ArrayBuilder.ofRef[Production].tap(_.sizeHint(${ Expr(table.productions.length) })) },
        elements = table.productions.map(Expr(_)),
        empty = '{ Array.empty[Production] },
      )
      '{ (rows = $rowsExpr, productions = $productionsExpr).asInstanceOf[ParseTable] }
    }

  // No constructor for a raw ParseTable outside the algorithm above, hence write-only below.
  // The export carries the parser's own symbol names, so a consumer need not hardcode them.
  // A reduction is written with its production, not with the index it refers to it by.
  given MCodec[Production] => MCodec[ParseTable] =
    import JsonExport.TableFormat
    given MCodec[TableFormat.Cell[Symbol, ParseAction.Export]] = MCodec.derived
    given MCodec[TableFormat[Symbol, ParseAction.Export]] = MCodec.derived
    MCodec[TableFormat[Symbol, ParseAction.Export]].transform(
      onWrite = table =>
        (
          endOfInput = Symbol.EOF.name.raw,
          start = Symbol.Start.name.raw,
          states = table.rows.toList.map(
            _.iterator.map((symbol, action) => (symbol = symbol, action = table.exported(action))).toList,
          ),
        ),
      onRead = _ => throw UnsupportedOperationException("ParseTable's export codec is write-only"),
    )
// $COVERAGE-ON$
