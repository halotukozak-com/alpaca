package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.{lexer, rule, ErrorHandling, ParserCtx, ParserError, Result, Rule, Token}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ParserErrorHandlingTest extends AnyFunSuite with Matchers:

  private val CalcLexer = lexer:
    case "\\+" => Token["+"]
    case value @ "[1-9][0-9]*" => Token["Num"](value.toInt)

  extension [A](result: Result[?, A, ParserError withFields CalcLexer.LexemeFields])
    private def failure: (recovered: Option[A], errors: List[ParserError withFields CalcLexer.LexemeFields]) =
      result match
        case Result.Failure(_, recovered, errors) => (recovered, errors)
        case Result.Success(_, _) => fail("expected a failure")

  extension (error: ParserError withFields CalcLexer.LexemeFields)
    private def at: (text: String, column: Int) = (error.unexpected.get.text, error.unexpected.get.column)

  case class StoppingContext() extends ParserCtx
  case class SkippingContext() extends ParserCtx
  case class FirstSkipContext() extends ParserCtx
  case class SkipAheadContext() extends ParserCtx

  given ErrorHandling[SkippingContext, ParserError] = (_, _) => ErrorHandling.Strategy.SkipOne

  given ErrorHandling[SkipAheadContext, ParserError] = (_, _) => ErrorHandling.Strategy.SkipToNextMatch

  private var seen = List.empty[ParserError]
  given ErrorHandling[FirstSkipContext, ParserError] = (_, error) =>
    seen = seen :+ error
    if seen.sizeIs == 1 then ErrorHandling.Strategy.SkipOne else ErrorHandling.Strategy.Stop

  // E -> E + Num | Num
  object StoppingParser extends Parser[StoppingContext]:
    val Expr: Rule[Int] = rule(
      { case (Expr(sum), CalcLexer.`+`(_), CalcLexer.Num(lexeme)) => sum + lexeme.value },
      { case CalcLexer.Num(lexeme) => lexeme.value },
    )
    val root: Rule[Int] = rule:
      case Expr(result) => result

  object SkippingParser extends Parser[SkippingContext]:
    val Expr: Rule[Int] = rule(
      { case (Expr(sum), CalcLexer.`+`(_), CalcLexer.Num(lexeme)) => sum + lexeme.value },
      { case CalcLexer.Num(lexeme) => lexeme.value },
    )
    val root: Rule[Int] = rule:
      case Expr(result) => result

  object FirstSkipParser extends Parser[FirstSkipContext]:
    val Expr: Rule[Int] = rule(
      { case (Expr(sum), CalcLexer.`+`(_), CalcLexer.Num(lexeme)) => sum + lexeme.value },
      { case CalcLexer.Num(lexeme) => lexeme.value },
    )
    val root: Rule[Int] = rule:
      case Expr(result) => result

  object SkipAheadParser extends Parser[SkipAheadContext]:
    val Expr: Rule[Int] = rule(
      { case (Expr(sum), CalcLexer.`+`(_), CalcLexer.Num(lexeme)) => sum + lexeme.value },
      { case CalcLexer.Num(lexeme) => lexeme.value },
    )
    val root: Rule[Int] = rule:
      case Expr(result) => result

  test("the default strategy stops at the first error and recovers nothing") {
    val result = StoppingParser.parse(CalcLexer.tokenize("1++2++3").getOrThrow).failure
    result.recovered shouldBe None
    result.errors.map(_.at) shouldBe List(("+", 3))
  }

  test("SkipOne skips the unexpected lexeme and recovers the value") {
    val result = SkippingParser.parse(CalcLexer.tokenize("1++2").getOrThrow).failure
    result.recovered shouldBe Some(3)
    result.errors.map(_.at) shouldBe List(("+", 3))
    result.errors.head.expected shouldBe List("Num")
  }

  test("SkipOne reports every skipped lexeme in input order") {
    val result = SkippingParser.parse(CalcLexer.tokenize("1+++2++3").getOrThrow).failure
    result.recovered shouldBe Some(6)
    result.errors.map(_.at) shouldBe List(("+", 3), ("+", 4), ("+", 7))
  }

  test("SkipOne cannot skip the end of the input") {
    val result = SkippingParser.parse(CalcLexer.tokenize("1+").getOrThrow).failure
    result.recovered shouldBe None
    result.errors.map(_.unexpected.map(_.name: String)) shouldBe List(None)
  }

  test("input without errors is a success whatever the strategy") {
    SkippingParser.parse(CalcLexer.tokenize("1+2").getOrThrow) shouldBe Result.Success(SkippingContext(), 3)
  }

  test("the strategy is given each error and can decide per error") {
    seen = Nil
    val result = FirstSkipParser.parse(CalcLexer.tokenize("1+++2").getOrThrow).failure
    result.recovered shouldBe None
    result.errors.map(_.at) shouldBe List(("+", 3), ("+", 4))
    seen shouldBe result.errors
  }

  test("SkipToNextMatch skips to the next acceptable lexeme and reports one error per skipped run") {
    val result = SkipAheadParser.parse(CalcLexer.tokenize("1+++2++3").getOrThrow).failure
    result.recovered shouldBe Some(6)
    result.errors.map(_.at) shouldBe List(("+", 3), ("+", 7))
  }

  test("SkipToNextMatch reaches the end of the input when nothing further is acceptable") {
    val result = SkipAheadParser.parse(CalcLexer.tokenize("1+++").getOrThrow).failure
    result.recovered shouldBe None
    result.errors.map(_.unexpected.map(_.name: String)) shouldBe List(Some("+"), None)
  }
