package halotukozak
package alpaca
package internal
package parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class FreshNonTerminalTest extends AnyFunSuite with Matchers:

  private val num = Terminal(Printable("Num"))

  test("the same occurrence gets the same name") {
    NonTerminal.fresh(num, "List", 42) shouldBe NonTerminal.fresh(num, "List", 42)
  }

  test("different occurrences get different names, all shown as base.extractor") {
    val fresh = List(
      NonTerminal.fresh(num, "List", 42),
      NonTerminal.fresh(num, "List", 57),
      NonTerminal.fresh(num, "Option", 42),
      NonTerminal.fresh(num, "SeparatedBy", 42),
      NonTerminal.fresh(num, "SeparatedBy.nonEmpty", 42),
    )
    fresh.distinct should have size fresh.size
    fresh.map(symbol => show"${symbol: Symbol}") shouldBe
      List("Num.List", "Num.List", "Num.Option", "Num.SeparatedBy", "Num.SeparatedBy.nonEmpty")
  }
