package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class Issue198FixTest extends AnyFunSuite with Matchers:

  val MyLexer = lexer:
    case "if" => Token["IF"]
    case "else" => Token["ELSE"]
    case value @ "[1-9][0-9]*" => Token["Num"](value.toInt)

  case class MyCtx() extends ParserCtx

  test("a hyphenated production name can be referenced in resolutions") {
    object Issue198Parser extends Parser[MyCtx]:
      val root: Rule[Int] = rule:
        case Expr(e) => e

      val Expr: Rule[Int] = rule(
        "if-else" { case (MyLexer.IF(_), MyLexer.Num(n), MyLexer.ELSE(_)) => n.value },
        { case MyLexer.Num(n) => n.value },
      )

    given Resolutions[Issue198Parser.type] = resolutions(production.`if-else`.after(MyLexer.Num))

    Issue198Parser.parse(MyLexer.tokenize("if7else").getOrThrow).getOrThrow shouldBe 7
    Issue198Parser.parse(MyLexer.tokenize("7").getOrThrow).getOrThrow shouldBe 7
  }
