package halotukozak
package alpaca
package internal
package parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class SymbolShowTest extends AnyFunSuite with Matchers:

  test("printable escapes line breaks and tabs as Scala escapes") {
    Symbol.printable("\n") shouldBe "\\n"
    Symbol.printable("\r\n") shouldBe "\\r\\n"
    Symbol.printable("\t") shouldBe "\\t"
  }

  test("printable escapes other control and format characters as unicode escapes") {
    Symbol.printable("\u0000") shouldBe "\\u0000"
    Symbol.printable("a\u001bb") shouldBe "a\\u001bb"
    Symbol.printable("​") shouldBe "\\u200b"
  }

  test("printable leaves visible characters alone") {
    Symbol.printable("+") shouldBe "+"
    Symbol.printable("\\+") shouldBe "\\+"
    Symbol.printable("zażółć") shouldBe "zażółć"
    Symbol.printable("a b") shouldBe "a b"
  }

  test("symbols with non-printable names are shown escaped") {
    show"${Terminal("\t"): Symbol}" shouldBe "\\t"
    show"${NonTerminal("\n"): Symbol}" shouldBe "\\n"
  }
