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
 *
 * @tparam Reduce what a reduction reduces by: its [[Production]] while the table is built, its [[ProductionRule]] at runtime
 */
opaque private[parser] type ParseTable[Reduce] = Array[Map[Symbol, ParseAction[Reduce]]]

private[parser] object ParseTable:
  extension [Reduce](table: ParseTable[Reduce]) {

    /**
     * Gets the parse action for a given state and symbol.
     *
     * @param state the current parser state
     * @param symbol the symbol being processed
     * @return the parse action to take
     * @throws AlgorithmError if no action is defined for this state/symbol combination
     */
    def action(state: Int, symbol: Symbol): ParseAction[Reduce] = table(state).get(symbol) match
      case Some(action) => action
      case None =>
        val expected = table(state).keysIterator.toList.sortBy(_.show).mkShow(", ")
        throw AlgorithmError(show"Unexpected symbol '$symbol' in state $state. Expected one of: $expected")

    /** The parse action for a given state and symbol, or `null` if the grammar accepts no such symbol there. */
    def get(state: Int, symbol: Symbol): ParseAction[Reduce] | Null = table(state).get(symbol) match
      case Some(action) => action
      case None => null

    /** The state the parser moves to after reducing to `nonTerminal` with `state` uncovered on top of the stack. */
    private[parser] def goto(state: Int, nonTerminal: NonTerminal): Int =
      table.action(state, nonTerminal).runtimeChecked match
        case Shift(gotoState) => gotoState

    private def allSymbols: List[Symbol] =
      table.iterator.flatMap(_.keysIterator).distinct.toList
  }

  extension [Ctx <: ParserCtx](table: ParseTable[ProductionRule[Ctx]]) {

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
          case terminal: Terminal if table.leadsToShift(reversedStack, terminal) =>
            terminal
        .toList
        .sortBy(terminal => (terminal.displayName, terminal != Symbol.EOF))

    /** Whether `terminal` is shifted or accepted after the reductions it triggers on `reversedStack` (top first). */
    @tailrec private def leadsToShift(reversedStack: List[Int], terminal: Terminal): Boolean =
      table.get(reversedStack.head, terminal) match
        case null => false
        case Shift(_) => true
        case Reduction(rule) =>
          val production = rule.production
          val rest = reversedStack.drop(production.size)
          if production.lhs == Symbol.Start && rest.head == 0 then true
          else table.leadsToShift(table.goto(rest.head, production.lhs) :: rest, terminal)

  }

  extension (table: ParseTable[Production]) {

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
  ): ParseTable[Production] = {
    val reported = mutable.HashSet.empty[Set[Production] | (Symbol, Production)]

    // $COVERAGE-OFF$
    def raiseReduceReduceConflict(red1: Reduction[Production], red2: Reduction[Production], path: List[Symbol]): Unit =
      val (production1, production2) = (red1.rule, red2.rule)
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

    def raiseShiftReduceConflict(symbol: Symbol, red: Reduction[Production], path: List[Symbol]): Unit =
      val production = red.rule
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

    val tableRows =
      mutable.ArrayBuffer.fill(automaton.states.length)(mutable.HashMap.empty[Symbol, ParseAction[Production]])

    def addToTable(stateId: Int, symbol: Symbol, action: ParseAction[Production]): Unit =
      val row = tableRows(stateId)
      row.get(symbol) match
        case None => row.update(symbol, action)
        case Some(existingAction) =>
          conflictResolutionTable.get(existingAction, action)(symbol) match
            case Some(action) => row.update(symbol, action)
            case None =>
              val path = toPath(stateId, List(symbol))
              (existingAction, action) match
                case (red1: Reduction[Production], red2: Reduction[Production]) =>
                  raiseReduceReduceConflict(red1, red2, path)
                case (Shift(_), red: Reduction[Production]) => raiseShiftReduceConflict(symbol, red, path)
                case (red: Reduction[Production], Shift(_)) => raiseShiftReduceConflict(symbol, red, path)
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

  given Showable[ParseTable[Production]] = table => {
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

  /**
   * Decodes a table from the [[TableEncoding]] text [[encode]] wrote, with one [[ProductionRule]] per production,
   * shared by the cells that reduce by it.
   *
   * @param actions the semantic action of every production after the synthetic start production, in the order they
   *                were encoded in
   * @throws IllegalStateException if the text was written in another format
   */
  def decode[Ctx <: ParserCtx](encoded: Array[String], actions: Array[Action[Ctx]]): ParseTable[ProductionRule[Ctx]] = {
    val reader = TableEncoding.Reader(encoded)
    val symbols = Array.better.tabulate[Symbol.NonEmpty](reader.readInt()): _ =>
      val isTerminal = reader.readInt() == 0
      // interned like the names in the lexer's code, so that comparing a lexeme's name with it is mostly a reference check
      val name = Printable(reader.readString().intern())
      if isTerminal then Terminal(name) else NonTerminal(name)

    val rules = Array.better.tabulate[ProductionRule[Ctx]](reader.readInt()): index =>
      val lhs = symbols(reader.readInt()).asInstanceOf[NonTerminal & Symbol.NonEmpty]
      val rhs = Vector.fill(reader.readInt())(symbols(reader.readInt()))
      val name = if reader.readInt() == 0 then null else Printable(reader.readString())
      val production =
        if rhs.isEmpty then Production.Empty(lhs, name) else Production.NonEmpty(lhs, NEL.unsafe(rhs), name)
      if index == 0 then ProductionRule.accept(production) else ProductionRule(production, actions(index - 1))

    Array.better.tabulate[Map[Symbol, ParseAction[ProductionRule[Ctx]]]](reader.readInt()): _ =>
      val row = Map.newBuilder[Symbol, ParseAction[ProductionRule[Ctx]]]
      for _ <- 0 until reader.readInt() do
        val symbol = symbols(reader.readInt())
        val action = reader.readInt()
        row += symbol -> (if action % 2 == 0 then Shift(action / 2) else Reduction(rules(action / 2)))
      row.result()
  }

  // $COVERAGE-OFF$
  extension (table: ParseTable[Production]) {

    /**
     * Encodes the table as [[TableEncoding]] text, split into string constants: the symbols (kind, 0 for a terminal,
     * and name), the productions (left-hand side, right-hand side size and symbols, 0 or 1 and the name), and the
     * states (cell count, then each cell's symbol and action, `2 * state` for a shift and `2 * production + 1` for a
     * reduction). Symbols and productions are written once, and referred to by their index in the text.
     *
     * @param productions the grammar productions, the synthetic start production first, in the order [[decode]] gets
     *                    their actions in
     */
    def encode(productions: Seq[Production]): List[String] = {
      val productionSymbols = productions.iterator.flatMap:
        case Production.NonEmpty(lhs, rhs, _) => lhs +: rhs
        case Production.Empty(lhs, _) => List(lhs)
      val symbols = (productionSymbols ++ table.iterator.flatMap(_.keysIterator)).distinct.toVector
      val symbolIndex = symbols.zipWithIndex.toMap
      val productionIndex = productions.zipWithIndex.toMap
      val writer = TableEncoding.Writer()

      writer.writeInt(symbols.size)
      for symbol <- symbols do
        writer.writeInt(if symbol.isInstanceOf[Terminal] then 0 else 1)
        writer.writeString(symbol.name.raw)

      writer.writeInt(productions.size)
      for production <- productions do
        writer.writeInt(symbolIndex(production.lhs))
        production match
          case Production.NonEmpty(_, rhs, _) =>
            writer.writeInt(rhs.size)
            rhs.foreach(symbol => writer.writeInt(symbolIndex(symbol)))
          case Production.Empty(_, _) => writer.writeInt(0)
        production.name match
          case null => writer.writeInt(0)
          case name =>
            writer.writeInt(1)
            writer.writeString(name.raw)

      writer.writeInt(table.length)
      for row <- table do
        writer.writeInt(row.size)
        for (symbol, action) <- row do
          writer.writeInt(symbolIndex(symbol))
          writer.writeInt(action match
            case Shift(state) => 2 * state
            case Reduction(production) => 2 * productionIndex(production) + 1)

      writer.chunks
    }
  }

  // Write-only: tooling reads the export, nothing reads it back into a table.
  // The export carries the parser's own symbol names, so a consumer need not hardcode them.
  given MCodec[Production] => MCodec[ParseTable[Production]] =
    import JsonExport.TableFormat
    given MCodec[TableFormat.Cell[Symbol, ParseAction[Production]]] = MCodec.derived
    given MCodec[TableFormat[Symbol, ParseAction[Production]]] = MCodec.derived
    MCodec[TableFormat[Symbol, ParseAction[Production]]].transform(
      onWrite = table =>
        (
          endOfInput = Symbol.EOF.name.raw,
          start = Symbol.Start.name.raw,
          states = table.toList.map(_.iterator.map((symbol, action) => (symbol = symbol, action = action)).toList),
        ),
      onRead = _ => throw UnsupportedOperationException("ParseTable's export codec is write-only"),
    )
// $COVERAGE-ON$
