package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

val NumLexer = lexer:
  case n @ "[0-9]+" => Token["NUM"](n.toInt)
  case "\\+" => Token["PLUS"]
  case "[ \t\n]+" => Token.Ignored

object SumParser extends Parser:
  val root: Rule[Int] = rule { case (NumLexer.NUM(a), NumLexer.PLUS(_), NumLexer.NUM(b)) => a.value + b.value }

final class LexemeTest extends AnyFunSuite with Matchers with LoneElement:

  private def parseError(input: String): ParserError = SumParser.parse(NumLexer.tokenize(input).getOrThrow) match
    case Result.Failure(_, _, errors) => errors.loneElement
    case Result.Success(_, _) => fail("expected a parse failure")

  test("lexemes of the same input are equal and have equal hash codes") {
    val first = NumLexer.tokenize("1 + 2").getOrThrow
    val second = NumLexer.tokenize("1 + 2").getOrThrow
    first shouldBe second
    first.map(_.hashCode) shouldBe second.map(_.hashCode)
  }

  test("lexemes differing in their value, text or context fields are not equal") {
    val List(one, _, two) = NumLexer.tokenize("1 + 2").getOrThrow.runtimeChecked
    val List(paddedOne) = NumLexer.tokenize("01").getOrThrow.runtimeChecked
    val List(_, movedOne) = NumLexer.tokenize("2 1").getOrThrow.runtimeChecked
    one should not be two
    one should not be paddedOne
    one should not be movedOne
  }

  test("a lexeme's toString shows its name, value, text and context fields") {
    NumLexer.tokenize("1 +").getOrThrow.map(_.toString) shouldBe List(
      """Lexeme(NUM, 1, "1", column = 1, line = 1)""",
      """Lexeme(PLUS, (), "+", column = 3, line = 1)""",
    )
  }

  test("parser errors from two parses of the same input are equal and print the same") {
    val first = parseError("1 + +")
    val second = parseError("1 + +")
    first shouldBe second
    first.hashCode shouldBe second.hashCode
    first.toString shouldBe """ParserError(Some(Lexeme(PLUS, (), "+", column = 5, line = 1)),List(NUM),None)"""
  }
