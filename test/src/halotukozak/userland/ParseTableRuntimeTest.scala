package halotukozak.userland

import halotukozak.alpaca.*
import halotukozak.alpaca.ParserError.EndOfInput
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

  private def errorsOf[E](result: Result[?, ?, E]): List[E] = result match
    case Result.Failure(_, _, errors) => errors
    case Result.Success(_, _) => fail("expected a parse failure")

  test("unexpected token fails with a ParserError naming the token and the expected terminals") {
    val lexemes = CalcLexer.tokenize("1++2").getOrThrow

    val error = errorsOf(CalcParser.parse(lexemes)).loneElement
    error.unexpected.map(l => (l.name: String, l.text)) shouldBe Some(("+", "+"))
    error.expected shouldBe List("Num")
    error.message shouldBe """Unexpected + "+". Expected one of: Num"""
  }

  test("input that ends too early fails with a ParserError for the end of input") {
    val lexemes = CalcLexer.tokenize("1+").getOrThrow

    val error = errorsOf(CalcParser.parse(lexemes)).loneElement
    error.unexpected shouldBe None
    error.expected shouldBe List("Num")
    error.last.map(_.text) shouldBe Some("+")
    error.message shouldBe """Unexpected end of input after + "+". Expected one of: Num"""
  }

  test("ParserError lists only terminals, with the end of input among them when it is accepted") {
    val leading = CalcLexer.tokenize("+").getOrThrow
    errorsOf(CalcParser.parse(leading)).loneElement.expected shouldBe List("Num")

    val one = CalcLexer.tokenize("1").getOrThrow
    val error = errorsOf(CalcParser.parse(one :+ one.head)).loneElement
    error.expected shouldBe List[String | EndOfInput](EndOfInput, "+")
    error.message should endWith("Expected one of: end of input, +")
  }

  // Operand -> Num . is one LALR(1) state for both operands, so it reduces on `+` and on the end of the input
  object SumParser extends Parser[CalcContext]:
    val Operand: Rule[Int] = rule { case CalcLexer.Num(lexeme) => lexeme.value }
    val root: Rule[Int] = rule { case (Operand(left), CalcLexer.`+`(_), Operand(right)) => left + right }

  test("ParserError leaves out a terminal that only a merged LALR(1) lookahead reduces on") {
    val one = CalcLexer.tokenize("1").getOrThrow
    errorsOf(SumParser.parse(one :+ one.head)).loneElement.expected shouldBe List("+")
    errorsOf(SumParser.parse(one)).loneElement.expected shouldBe List("+")
    errorsOf(SumParser.parse(CalcLexer.tokenize("1+2").getOrThrow :+ one.head)).loneElement.expected shouldBe
      List(EndOfInput)
  }
