package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.parser.{ConflictKey, ConflictResolutionTable, NonTerminal, Production, Terminal}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ConflictResolutionTableTest extends AnyFunSuite with Matchers:
  private val prodA =
    Production.NonEmpty(NonTerminal(Printable("A")), NEL(Terminal(Printable("a"))), source = TestSource)
  private val prodB =
    Production.NonEmpty(NonTerminal(Printable("B")), NEL(Terminal(Printable("b"))), Printable("named"), TestSource)
  private val tokenX = ConflictKey.Shift(Printable("X"))
  private val tokenY = ConflictKey.Shift(Printable("Y"))

  test("toMermaid produces a graph TD header") {

    val table = ConflictResolutionTable(Map.empty)
    table.toMermaid should startWith("graph TD\n")
  }

  test("toMermaid assigns safe unique IDs for productions and tokens") {

    val table = ConflictResolutionTable(
      Map(ConflictKey.Reduction(prodA) -> Map(tokenX -> TestSource)),
    )
    val output = table.toMermaid
    // Production gets P_ prefix, token gets T_ prefix
    output should include("P_1")
    output should include("T_1")
    // Safe IDs do not contain spaces, parens, arrows, or epsilon
    output.linesIterator
      .filter(line => line.trim.startsWith("P_") || line.trim.startsWith("T_"))
      .foreach: line =>
        val id = line.trim.takeWhile(c => c != '[' && c != ' ' && c != '-')
        (id should fullyMatch).regex("[PT]_[0-9]+")
  }

  test("toMermaid emits node declarations with human-readable labels") {

    val table = ConflictResolutionTable(
      Map(ConflictKey.Reduction(prodA) -> Map(tokenX -> TestSource)),
    )
    val output = table.toMermaid
    output should include(show"A -> a")
    output should include("Token(X)")
  }

  test("toMermaid emits edge declarations") {

    val table = ConflictResolutionTable(
      Map(ConflictKey.Reduction(prodA) -> Map(tokenX -> TestSource)),
    )
    val output = table.toMermaid
    output should include("-->")
    // P_1 should point to T_1
    output should include("P_1 --> T_1")
  }

  test("toMermaid output is deterministic across repeated calls") {

    val table = ConflictResolutionTable(
      Map(
        ConflictKey.Reduction(prodA) -> Map(tokenX -> TestSource, tokenY -> TestSource),
        ConflictKey.Reduction(prodB) -> Map(tokenX -> TestSource),
      ),
    )
    val first = table.toMermaid
    val second = table.toMermaid
    first shouldEqual second
  }

  test("toMermaid escapes double quotes in labels") {

    val prodWithQuote =
      Production.NonEmpty(NonTerminal(Printable("A\"B")), NEL(Terminal(Printable("a"))), source = TestSource)
    val table = ConflictResolutionTable(
      Map(ConflictKey.Reduction(prodWithQuote) -> Map.empty),
    )
    val output = table.toMermaid
    output should include("\\\"")
  }

  test("toMermaid escapes backslashes in labels") {

    val prodWithBackslash =
      Production.NonEmpty(NonTerminal(Printable("A\\B")), NEL(Terminal(Printable("a"))), source = TestSource)
    val table = ConflictResolutionTable(
      Map(ConflictKey.Reduction(prodWithBackslash) -> Map.empty),
    )
    val output = table.toMermaid
    output should include("\\\\")
  }

  test("toMermaid escapes newlines in labels") {

    val prodWithNewline =
      Production.NonEmpty(NonTerminal(Printable("A\nB")), NEL(Terminal(Printable("a"))), source = TestSource)
    val table = ConflictResolutionTable(
      Map(ConflictKey.Reduction(prodWithNewline) -> Map.empty),
    )
    val output = table.toMermaid
    // header + 1 node line = 2 lines; if the newline in the label were not escaped,
    // there would be an extra line
    output.linesIterator.length shouldBe 2
    // The literal two-char sequence \n should appear inside the node label brackets
    val nodeLine = output.linesIterator.find(_.contains("[\"")).getOrElse(fail("no node line found"))
    nodeLine should include("\\n")
  }

  test("toMermaid handles multiple nodes and edges in sorted order") {

    val table = ConflictResolutionTable(
      Map(
        ConflictKey.Reduction(prodA) -> Map(tokenX -> TestSource),
        ConflictKey.Reduction(prodB) -> Map(tokenY -> TestSource),
      ),
    )
    val lines = table.toMermaid.linesIterator.toList
    val nodeLine1 = lines.indexWhere(_.contains("A -> a"))
    val nodeLine2 = lines.indexWhere(_.contains("named"))
    // Nodes are listed before edges
    val edgeLine = lines.indexWhere(_.contains("-->"))
    nodeLine1 should be >= 0
    nodeLine2 should be >= 0
    edgeLine should be > nodeLine1
    edgeLine should be > nodeLine2
  }

  test("toMermaid includes nodes referenced only as targets") {

    // tokenX appears only as a target, not as a key in the table
    val table = ConflictResolutionTable(
      Map(ConflictKey.Reduction(prodA) -> Map(tokenX -> TestSource)),
    )
    val output = table.toMermaid
    output should include("Token(X)")
  }
