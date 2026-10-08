package halotukozak
package alpaca
package internal
package parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class FreshNonTerminalTest extends AnyFunSuite with Matchers:

  private def names(using NonTerminal.Fresh) =
    val num = Terminal(Printable("Num"))
    List(NonTerminal.fresh(num, "List"), NonTerminal.fresh(num, "List"), NonTerminal.fresh(num, "Option"))

  test("fresh non-terminals are unique within one numbering and shown as base.extractor") {
    val fresh = names(using NonTerminal.Fresh())
    fresh.distinct should have size 3
    fresh.map(symbol => show"${symbol: Symbol}") shouldBe List("Num.List", "Num.List", "Num.Option")
  }

  test("fresh non-terminals are named the same on every expansion") {
    names(using NonTerminal.Fresh()) shouldBe names(using NonTerminal.Fresh())
  }
