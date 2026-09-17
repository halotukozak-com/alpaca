package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.parser.ParseAction.*
import halotukozak.alpaca.internal.{AlgorithmError, DebugSettings, NEL}
import halotukozak.alpaca.internal.parser.{ConflictResolutionTable, NonTerminal, ParseTable, Production, Symbol, Terminal}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ParseTableRuntimeTest extends AnyFunSuite with Matchers:
  private given DebugSettings = DebugSettings.default

  private val emptyResolutions = ConflictResolutionTable(Map.empty)

  // Grammar:
  //   S' -> E
  //   E  -> E + Num
  //   E  -> Num
  private val E = NonTerminal("E")
  private val Num = Terminal("Num")
  private val Plus = Terminal("+")

  private val productions: List[Production] = List(
    Production.NonEmpty(Symbol.Start, NEL(E)),
    Production.NonEmpty(E, NEL(E, Plus, Num), "EAdd"),
    Production.NonEmpty(E, NEL(Num), "ENum"),
  )

  private def buildTable(): ParseTable = ParseTable(productions, emptyResolutions) match
    case Right(table) => table
    case Left(err) => fail(s"expected no conflict, got ${err.getMessage}")

  test("builds a parse table for a simple LR(1) grammar") {
    val table = buildTable()

    table(0, Num) shouldBe a[Shift]
  }

  test("apply raises AlgorithmError when no action exists for (state, symbol)") {
    val table = buildTable()
    val unknown = Terminal("<unknown>")

    val ex = intercept[AlgorithmError](table(0, unknown))
    ex.getMessage should (include("Unexpected symbol").and(include("Expected one of:")))
  }

  test("toCsv headers start with State and include all grammar symbols seen in the table") {
    val csv = buildTable().toCsv

    csv.headers.head should include("State")
    val headerNames = csv.headers
    headerNames should contain(Num.name)
    headerNames should contain(Plus.name)
  }

  test("Showable renders a non-empty textual representation with multiple rows") {
    val rendered = buildTable().show

    rendered should include("State")
    rendered.linesIterator.size should be > 1
  }
