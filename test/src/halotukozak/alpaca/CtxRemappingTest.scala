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

  // compiles without "unused explicit parameter" under -Werror (#679)
  test("remapping to a value that ignores the context") {
    val L = lexer:
      case "\\s+" => Token.Ignored
      case "null" => Token["null"](null)
      case "one" => Token["one"](1)
      case "pair" =>
        Token["pair"]:
          val half = 2
          half -> half

    val res = L.tokenize("null one pair").getOrThrow
    res.map(_.value) shouldBe List[Any](null, 1, 2 -> 2)
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
