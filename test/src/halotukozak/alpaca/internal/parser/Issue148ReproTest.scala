package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.parser.Parser
import halotukozak.alpaca.{lexer, rule, ParserCtx, Rule, Token}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class Issue148ReproTest extends AnyFunSuite with Matchers:

  val MyLexer = lexer:
    case "T" => Token["T"]

  case class MyCtx() extends ParserCtx

  test("Parser should support multiline actions") {
    object Issue148Parser extends Parser[MyCtx]:
      override val root: Rule[Any] = rule:
        case MyLexer.T(_) =>
          val x = 1
          val y = 2
          x + y

    val lexemes = MyLexer.tokenize("T").getOrThrow
    val result = Issue148Parser.parse(lexemes).getOrThrow
    result shouldBe 3
  }
