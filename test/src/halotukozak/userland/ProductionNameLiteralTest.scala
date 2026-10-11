package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.typeCheckErrors

val NameLiteralLexer = lexer:
  case "\\+" => Token["PLUS"]
  case n @ "[0-9]+" => Token["NUM"](n.toInt)
  case "\\s+" => Token.Ignored

final class ProductionNameLiteralTest extends AnyFunSuite with Matchers with LoneElement:

  test("a production name that is not a string literal is reported at it") {
    val error = typeCheckErrors("""
    object NonLiteralNameParser extends Parser:
      val name = "plus"
      val root: Rule[Int] = rule(
        name { case (root(a), NameLiteralLexer.PLUS(_), root(b)) => a + b },
        { case NameLiteralLexer.NUM(n) => n.value },
      )
    """).loneElement
    error.message shouldBe
      "A production name must be a string literal, as in `\"plus\" { case ... }`\n(at line 5: name)"
    error.lineContent.trim shouldBe "name { case (root(a), NameLiteralLexer.PLUS(_), root(b)) => a + b },"
    error.column shouldBe 8
  }
