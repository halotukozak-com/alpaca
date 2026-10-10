package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.parser.ParseAction.*
import halotukozak.mcodec.MCodec

import scala.annotation.tailrec
import scala.collection.mutable
import scala.quoted.*

/**
 * An opaque type representing the LR parse table.
 *
 * State IDs are dense consecutive integers starting at 0, so the table is
 * stored as an array indexed by state, with each cell holding a symbol ->
 * action map. Lookup is a single array index plus one symbol hash, with no
 * boxing on the state key.
 */
opaque private[parser] type ParseTable = Array[Map[Symbol, ParseAction]]

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
    def apply(state: Int, symbol: Symbol): ParseAction = table(state).get(symbol) match
      case Some(action) => action
      case None =>
        val expected = table(state).keysIterator.toList.sortBy(_.show).mkShow(", ")
        throw AlgorithmError(show"Unexpected symbol '$symbol' in state $state. Expected one of: $expected")

    /** The parse action for a given state and symbol, or `null` if the grammar accepts no such symbol there. */
    def get(state: Int, symbol: Symbol): ParseAction | Null = table(state).get(symbol) match
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
      table(reversedStack.head).keysIterator
        .collect:
          case terminal: Terminal if terminal != Symbol.Dummy && table.leadsToShift(reversedStack, terminal) =>
            terminal
        .toList
        .sortBy(terminal => (terminal.displayName, terminal != Symbol.EOF))

    /** Whether `terminal` is shifted or accepted after the reductions it triggers on `reversedStack` (top first). */
    @tailrec private def leadsToShift(reversedStack: List[Int], terminal: Terminal): Boolean =
      table.get(reversedStack.head, terminal) match
        case null => false
        case Shift(_) => true
        case Reduction(production) =>
          val rest = reversedStack.drop(production.size)
          if production.lhs == Symbol.Start && rest.head == 0 then true
          else table.leadsToShift(table.goto(rest.head, production.lhs) :: rest, terminal)

    /** The state the parser moves to after reducing to `nonTerminal` with `state` uncovered on top of the stack. */
    private[parser] def goto(state: Int, nonTerminal: NonTerminal): Int =
      table(state, nonTerminal).runtimeChecked match
        case Shift(gotoState) => gotoState

    private def allSymbols: List[Symbol] =
      table.iterator.flatMap(_.keysIterator).distinct.toList

    // $COVERAGE-OFF$
    /** The table's rows, one per state (dense, consecutive, starting at 0), each a symbol -> action map. */
    private[parser] def rows: Array[Map[Symbol, ParseAction]] = table
    // $COVERAGE-ON$

    /**
     * Converts the parse table to CSV format for debugging.
     *
     * Creates a table with states as rows and symbols as columns,
     * showing the action for each state/symbol combination.
     *
     * @return a Csv representation of the parse table
     */
    // it shouldn't be eager
    def toCsv: Csv = {
      val symbols = table.allSymbols

      val headers = show"State" :: symbols.map(s => show"$s")
      val rows = table.indices
        .map: i =>
          val row = table(i)
          show"$i" :: symbols.map(s => row.get(s).fold(show"")(_.show))
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
   * @param productions the grammar productions
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
    val reported = mutable.HashSet.empty[Set[Production] | (Symbol, Production)]

    // $COVERAGE-OFF$
    def raiseReduceReduceConflict(red1: Reduction, red2: Reduction, path: List[Symbol]): Unit =
      if reported.add(Set(red1.production, red2.production)) then
        error(
          show"""
                |Reduce $red1 vs Reduce $red2
                |In situation like:
                |${path.filter(_ != Symbol.EOF).mkShow("", " ", " ...")}
                |Conflicting production: ${red1.production} (line ${sources(red1.production).line + 1})
                |Consider marking one of the productions to be before or after the other
                |""".trimMargin,
          sources(red2.production),
        )

    def raiseShiftReduceConflict(symbol: Symbol, red: Reduction, path: List[Symbol]): Unit =
      if reported.add((symbol, red.production)) then
        error(
          show"""
                |Shift "$symbol" vs Reduce $red
                |In situation like:
                |${path.filter(_ != Symbol.EOF).mkShow("", " ", " ...")}
                |Consider marking production $red to be before or after "$symbol"
                |""".trimMargin,
          sources(red.production),
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
          conflictResolutionTable.get(existingAction, action)(symbol) match
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

      for item <- currState if item.isLastItem do addToTable(stateId, item.lookAhead, Reduction(item.production))

      for (stepSymbol, targetStateId) <- automaton.goto(stateId) do addToTable(stateId, stepSymbol, Shift(targetStateId))
    }

    // $COVERAGE-OFF$
    // every conflict is already reported at its own production; abort without an extra error at the call site
    abortOnErrors()
    // $COVERAGE-ON$

    Array.better.tabulate(tableRows.length)(tableRows(_).toMap)
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

    for i <- table.indices do
      val row = table(i)
      result.append('\n')
      result.append(centerText(i.toString))
      result.append("|")
      for s <- symbols do
        result.append(centerText(row.get(s).fold("")(_.show)))
        result.append("|")
    result.append('\n')
    result.result().showRaw
  }

  // $COVERAGE-OFF$
  given ToExpr[ParseTable]:
    def apply(entries: ParseTable)(using quotes: Quotes): Expr[ParseTable] = {
      type Row = Map[Symbol, ParseAction]
      type RowBuilder = mutable.Builder[(Symbol, ParseAction), Row]

      def rowExpr(row: Row): Expr[Row] = avoidTooLargeMethod[(Symbol, ParseAction), Row, RowBuilder](
        builder = '{ Map.newBuilder },
        elements = row.map(Expr(_)),
        empty = '{ Map.empty },
      )

      val arrayExpr = avoidTooLargeMethod[Row, Array[Row], mutable.ArrayBuilder[Row]](
        builder = '{ mutable.ArrayBuilder.ofRef[Row].tap(_.sizeHint(${ Expr(entries.length) })) },
        elements = entries.map(rowExpr),
        empty = '{ Array.empty[Row] },
      )
      '{ $arrayExpr.asInstanceOf[ParseTable] }
    }

  // No constructor for a raw ParseTable outside the algorithm above, hence write-only below.
  // The export carries the parser's own symbol names, so a consumer need not hardcode them.
  given MCodec[Production] => MCodec[ParseTable] =
    import JsonExport.TableFormat
    given MCodec[TableFormat.Cell[Symbol, ParseAction]] = MCodec.derived
    given MCodec[TableFormat[Symbol, ParseAction]] = MCodec.derived
    MCodec[TableFormat[Symbol, ParseAction]].transform(
      onWrite = table =>
        (
          endOfInput = Symbol.EOF.name.raw,
          start = Symbol.Start.name.raw,
          states = table.rows.toList.map(_.iterator.map((symbol, action) => (symbol = symbol, action = action)).toList),
        ),
      onRead = _ => throw UnsupportedOperationException("ParseTable's export codec is write-only"),
    )
// $COVERAGE-ON$
