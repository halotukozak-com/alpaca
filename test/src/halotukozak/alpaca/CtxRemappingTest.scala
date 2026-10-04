package halotukozak
package alpaca

import halotukozak.alpaca.internal.lexer.ErrorHandling
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class CtxRemappingTest extends AnyFunSuite with Matchers:
  test("remapping maps matched text to custom values using ctx.text") {
    val L = lexer:
      case "\\s+" => Token.Ignored
      case x @ "[0-9]+" => Token["int"](x.toInt)
      case s @ "[a-z]+" => Token["id"](s.toUpperCase)

    val (_, res) = L.tokenize("12 abc 7")
    res.map(_.name) shouldBe List("int", "id", "int")
    res.map(_.value) shouldBe List[Any](12, "ABC", 7)
  }

  test("ctx manipulation influences error position after ignored token") {
    final class CustomException(message: String) extends RuntimeException(message)

    given ErrorHandling[LexerCtx.Default] = ctx =>
      ErrorHandling.Strategy.Throw:
        CustomException(s"Error at position ${ctx.position}")

    val L = lexer:
      case "a" => Token["a"]
      case "!" =>
        ctx.position = Column(ctx.position + 5)
        Token.Ignored

    val exception = intercept[CustomException] {
      L.tokenize("a!\na!a")
    }
    exception.getMessage should include("Error at position 8")
  }
