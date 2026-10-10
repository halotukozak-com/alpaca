package halotukozak
package alpaca
package internal
package parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class SyntheticNonTerminalTest extends AnyFunSuite with Matchers:

  private val num = Terminal(Printable("Num"))

  private val comma = Terminal(Printable("Comma"))

  test("every use of an extractor on the same symbols gets the same name") {
    NonTerminal.synthetic("List", num) shouldBe NonTerminal.synthetic("List", num)
    NonTerminal.synthetic("SeparatedBy", num, comma) shouldBe NonTerminal.synthetic("SeparatedBy", num, comma)
  }

  test("different extractors and symbols get different names, all shown as base.extractor") {
    val fresh = List(
      NonTerminal.synthetic("List", num),
      NonTerminal.synthetic("List", NonTerminal(Printable("Num"))),
      NonTerminal.synthetic("Option", num),
      NonTerminal.synthetic("SeparatedBy", num, comma),
      NonTerminal.synthetic("SeparatedBy", num, NonTerminal(Printable("Comma"))),
      NonTerminal.synthetic("SeparatedBy.nonEmpty", num, comma),
    )
    fresh.distinct should have size fresh.size
    fresh.map(symbol => show"${symbol: Symbol}") shouldBe
      List("Num.List", "Num.List", "Num.Option", "Num.SeparatedBy", "Num.SeparatedBy", "Num.SeparatedBy.nonEmpty")
  }
