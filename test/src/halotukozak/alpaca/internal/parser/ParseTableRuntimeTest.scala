package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.{lexer, rule, ParseError, ParserCtx, Rule, Token}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

// ParseTable.apply now requires a real macro Quotes (it reports compile errors on
// conflict instead of returning a value), so it can no longer be called directly from a plain
// unit test -- these tests go through the actual lexer/parser DSL instead, same as ParseTableTest.
final class ParseTableRuntimeTest extends AnyFunSuite with Matchers:

  private val CalcLexer = lexer:
    case "\\+" => Token["+"]
    case value @ "[1-9][0-9]*" => Token["Num"](value.toInt)

  case class CalcContext() extends ParserCtx

  // E -> E + Num | Num: left-recursive but unambiguous (no shift/reduce conflict, unlike
  // ParseTableTest's Expr -> Expr + Expr, where both operands recursing creates real ambiguity).
  object CalcParser extends Parser[CalcContext]:
    val Expr: Rule[Int] = rule(
      { case (Expr(sum), CalcLexer.`+`(_), CalcLexer.Num(lexeme)) => sum + lexeme.value },
      { case CalcLexer.Num(lexeme) => lexeme.value },
    )

    val root: Rule[Int] = rule:
      case Expr(result) => result

  test("builds a parse table for a simple LR(1) grammar without a false-positive conflict") {
    val (_, lexemes) = CalcLexer.tokenize("1+2+3")
    val (_, result) = CalcParser.parse(lexemes)

    result shouldBe 6
  }

  test("unexpected token raises a ParseError naming the token, its position and the expected terminals") {
    val (_, lexemes) = CalcLexer.tokenize("1++2")

    val ex = intercept[ParseError](CalcParser.parse(lexemes))
    (ex.unexpected.name: String) shouldBe "+"
    ex.unexpected.text shouldBe "+"
    ex.expected shouldBe List("Num")
    ex.getMessage shouldBe """Unexpected + "+" at line 1, column 3. Expected one of: Num"""
  }

  test("input that ends too early raises a ParseError for the end of input") {
    val (_, lexemes) = CalcLexer.tokenize("1+")

    val ex = intercept[ParseError](CalcParser.parse(lexemes))
    (ex.unexpected.name: String) shouldBe "$"
    ex.expected shouldBe List("Num")
    ex.getMessage shouldBe "Unexpected end of input. Expected one of: Num"
  }

  test("ParseError lists only terminals, with the end of input among them when it is accepted") {
    val (_, leading) = CalcLexer.tokenize("+")
    intercept[ParseError](CalcParser.parse(leading)).expected shouldBe List("Num")

    val (_, one) = CalcLexer.tokenize("1")
    val ex = intercept[ParseError](CalcParser.parse(one :+ one.head))
    ex.expected shouldBe List("$", "+")
    ex.getMessage should endWith("Expected one of: end of input, +")
  }
