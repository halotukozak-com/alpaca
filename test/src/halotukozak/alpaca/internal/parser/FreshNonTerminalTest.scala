package halotukozak
package alpaca
package internal
package parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class FreshNonTerminalTest extends AnyFunSuite with Matchers:

  private val num = Terminal(Printable("Num"))

  private val comma = Terminal(Printable("Comma"))

  test("every use of an extractor on the same symbols gets the same name") {
    NonTerminal.fresh(num, "List") shouldBe NonTerminal.fresh(num, "List")
    NonTerminal.fresh(num, "SeparatedBy", Some(comma)) shouldBe NonTerminal.fresh(num, "SeparatedBy", Some(comma))
  }

  test("different extractors and symbols get different names, all shown as base.extractor") {
    val fresh = List(
      NonTerminal.fresh(num, "List"),
      NonTerminal.fresh(NonTerminal(Printable("Num")), "List"),
      NonTerminal.fresh(num, "Option"),
      NonTerminal.fresh(num, "SeparatedBy", Some(comma)),
      NonTerminal.fresh(num, "SeparatedBy", Some(NonTerminal(Printable("Comma")))),
      NonTerminal.fresh(num, "SeparatedBy.nonEmpty", Some(comma)),
    )
    fresh.distinct should have size fresh.size
    fresh.map(symbol => show"${symbol: Symbol}") shouldBe
      List("Num.List", "Num.List", "Num.Option", "Num.SeparatedBy", "Num.SeparatedBy", "Num.SeparatedBy.nonEmpty")
  }
