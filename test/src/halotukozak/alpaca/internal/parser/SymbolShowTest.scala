package halotukozak
package alpaca
package internal
package parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class SymbolShowTest extends AnyFunSuite with Matchers:

  test("symbols with non-printable names are shown escaped") {
    show"${Terminal(Printable("\t")): Symbol}" shouldBe "\\t"
    show"${NonTerminal(Printable("\n")): Symbol}" shouldBe "\\n"
  }
