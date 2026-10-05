package halotukozak
package alpaca

import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class CtxRemappingTest extends AnyFunSuite with Matchers with LoneElement:
  test("remapping maps matched text to custom values using ctx.text") {
    val L = lexer:
      case "\\s+" => Token.Ignored
      case x @ "[0-9]+" => Token["int"](x.toInt)
      case s @ "[a-z]+" => Token["id"](s.toUpperCase)

    val res = L.tokenize("12 abc 7").getOrThrow
    res.map(_.name) shouldBe List("int", "id", "int")
    res.map(_.value) shouldBe List[Any](12, "ABC", 7)
  }

  test("ctx manipulation influences error position after ignored token") {
    val L = lexer:
      case "a" => Token["a"]
      case "!" =>
        ctx.position = Column(ctx.position + 5)
        Token.Ignored

    val error = intercept[LexerException](L.tokenize("a!\na!a").getOrThrow).errors.loneElement
    error.column shouldBe Some(8)
  }
