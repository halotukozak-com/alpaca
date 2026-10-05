package halotukozak
package alpaca
package internal

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class PrintableTest extends AnyFunSuite with Matchers:

  test("escapes line breaks and tabs as Scala escapes") {
    Printable("\n").show shouldBe "\\n"
    Printable("\r\n").show shouldBe "\\r\\n"
    Printable("\t").show shouldBe "\\t"
  }

  test("escapes other control and format characters as unicode escapes") {
    Printable("\u0000").show shouldBe "\\u0000"
    Printable("a\u001bb").show shouldBe "a\\u001bb"
    Printable("​").show shouldBe "\\u200b"
  }

  test("escapes Unicode line and paragraph separators, which break the line like a newline") {
    Printable("a\u2028b").show shouldBe "a\\u2028b"
    Printable("a\u2029b").show shouldBe "a\\u2029b"
  }

  test("leaves visible characters alone") {
    Printable("+").show shouldBe "+"
    Printable("\\+").show shouldBe "\\+"
    Printable("zażółć").show shouldBe "zażółć"
    Printable("a b").show shouldBe "a b"
  }
