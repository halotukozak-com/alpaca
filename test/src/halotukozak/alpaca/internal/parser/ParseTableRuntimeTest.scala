package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.{lexer, rule, ParserCtx, ParserError, ParserException, Result, Rule, Token}
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

// ParseTable.apply now requires a real macro Quotes (it reports compile errors on
// conflict instead of returning a value), so it can no longer be called directly from a plain
// unit test -- these tests go through the actual lexer/parser DSL instead, same as ParseTableTest.
final class ParseTableRuntimeTest extends AnyFunSuite with Matchers with LoneElement:

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
    val lexemes = CalcLexer.tokenize("1+2+3").getOrThrow
    val result = CalcParser.parse(lexemes).getOrThrow

    result shouldBe 6
  }

  private def errorsOf(result: Result[?, ?, ParserError]): List[ParserError] = result match
    case Result.Failure(_, _, errors) => errors
    case Result.Success(_, _) => fail("expected a parse failure")

  test("unexpected token fails with a ParserError naming the token, its position and the expected terminals") {
    val lexemes = CalcLexer.tokenize("1++2").getOrThrow

    val error = errorsOf(CalcParser.parse(lexemes)).loneElement
    (error.unexpected.name: String) shouldBe "+"
    error.unexpected.text shouldBe "+"
    error.expected shouldBe List("Num")
    error.message shouldBe """Unexpected + "+" at line 1, column 3. Expected one of: Num"""
  }

  test("input that ends too early fails with a ParserError for the end of input") {
    val lexemes = CalcLexer.tokenize("1+").getOrThrow

    val error = errorsOf(CalcParser.parse(lexemes)).loneElement
    (error.unexpected.name: String) shouldBe "$"
    error.expected shouldBe List("Num")
    error.after.map(_.text) shouldBe Some("+")
    error.message shouldBe """Unexpected end of input after + "+" at line 1, column 2. Expected one of: Num"""
  }

  test("ParserError lists only terminals, with the end of input among them when it is accepted") {
    val leading = CalcLexer.tokenize("+").getOrThrow
    errorsOf(CalcParser.parse(leading)).loneElement.expected shouldBe List("Num")

    val one = CalcLexer.tokenize("1").getOrThrow
    val error = errorsOf(CalcParser.parse(one :+ one.head)).loneElement
    error.expected shouldBe List("$", "+")
    error.message should endWith("Expected one of: end of input, +")
  }

  test("a successful Result gives the value through every accessor") {
    val lexemes = CalcLexer.tokenize("1+2").getOrThrow
    val result = CalcParser.parse(lexemes)

    result shouldBe a[Result.Success[?, ?, ?]]
    result.ctx shouldBe CalcContext()
    result.getOrThrow shouldBe 3
    result.toOption shouldBe Some(3)
    result.toEither shouldBe Right(3)
  }

  test("a failed Result keeps the context, and getOrThrow throws its errors as a ParserException") {
    val lexemes = CalcLexer.tokenize("1+").getOrThrow
    val result = CalcParser.parse(lexemes)
    val error = errorsOf(result).head

    result.ctx shouldBe CalcContext()
    result.toOption shouldBe None
    result.toEither shouldBe Left(List(error))
    val exception = intercept[ParserException](result.getOrThrow)
    exception.errors shouldBe List(error)
    exception.getMessage shouldBe error.message
  }
