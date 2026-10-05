package halotukozak
package alpaca
package internal

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class PrintableTest extends AnyFunSuite with Matchers:

  test("escapes line breaks and tabs as Scala escapes") {
    printable("\n") shouldBe "\\n"
    printable("\r\n") shouldBe "\\r\\n"
    printable("\t") shouldBe "\\t"
  }

  test("escapes other control and format characters as unicode escapes") {
    printable("\u0000") shouldBe "\\u0000"
    printable("a\u001bb") shouldBe "a\\u001bb"
    printable("​") shouldBe "\\u200b"
  }

  test("leaves visible characters alone") {
    printable("+") shouldBe "+"
    printable("\\+") shouldBe "\\+"
    printable("zażółć") shouldBe "zażółć"
    printable("a b") shouldBe "a b"
  }
