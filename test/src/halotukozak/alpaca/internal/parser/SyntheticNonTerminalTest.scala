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
    NonTerminal.synthetic(num, "List") shouldBe NonTerminal.synthetic(num, "List")
    NonTerminal.synthetic(num, "SeparatedBy", Some(comma)) shouldBe NonTerminal.synthetic(num, "SeparatedBy", Some(comma))
  }

  test("different extractors and symbols get different names, all shown as base.extractor") {
    val fresh = List(
      NonTerminal.synthetic(num, "List"),
      NonTerminal.synthetic(NonTerminal(Printable("Num")), "List"),
      NonTerminal.synthetic(num, "Option"),
      NonTerminal.synthetic(num, "SeparatedBy", Some(comma)),
      NonTerminal.synthetic(num, "SeparatedBy", Some(NonTerminal(Printable("Comma")))),
      NonTerminal.synthetic(num, "SeparatedBy.nonEmpty", Some(comma)),
    )
    fresh.distinct should have size fresh.size
    fresh.map(symbol => show"${symbol: Symbol}") shouldBe
      List("Num.List", "Num.List", "Num.Option", "Num.SeparatedBy", "Num.SeparatedBy", "Num.SeparatedBy.nonEmpty")
  }
