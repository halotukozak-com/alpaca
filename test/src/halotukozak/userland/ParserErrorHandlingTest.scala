package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ParserErrorHandlingTest extends AnyFunSuite with Matchers:

  private val CalcLexer = lexer:
    case "\\+" => Token["+"]
    case "-" => Token["-"]
    case "\\*" => Token["*"]
    case value @ "[1-9][0-9]*" => Token["Num"](value.toInt)

  extension [A](result: Result[?, A, ParserError { type Fields = CalcLexer.LexemeFields }])
    private def failure: (recovered: Option[A], errors: List[ParserError { type Fields = CalcLexer.LexemeFields }]) =
      result match
        case Result.Failure(_, recovered, errors) => (recovered, errors)
        case Result.Success(_, _) => fail("expected a failure")

  extension (error: ParserError { type Fields = CalcLexer.LexemeFields })
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

  // Operand -> Num . is one LALR(1) state for both operands, so it reduces on `+` and on `-`
  object MergedLookaheadParser extends Parser[SkipAheadContext]:
    val Operand: Rule[Int] = rule { case CalcLexer.Num(lexeme) => lexeme.value }
    val root: Rule[Int] = rule:
      case (Operand(left), CalcLexer.`+`(_), Operand(right), CalcLexer.`-`(_)) => left + right

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

  test("SkipToNextMatch skips a lexeme that only a merged LALR(1) lookahead reduces on") {
    val result = MergedLookaheadParser.parse(CalcLexer.tokenize("1*-+2-").getOrThrow).failure
    result.recovered shouldBe Some(3)
    result.errors.map(_.at) shouldBe List(("*", 2))
    result.errors.head.expected shouldBe List("+")
  }

  object SkippingListParser extends Parser[SkippingContext]:
    val root: Rule[List[Int]] = rule:
      case CalcLexer.Num.List(numbers) => numbers.map(_.value)

  object SkippingSeparatedParser extends Parser[SkippingContext]:
    val root: Rule[String] = rule:
      case CalcLexer.Num.SeparatedBy[CalcLexer.`-`](items) => items.map(_.text).mkString

  test("SkipOne recovers a List past the lexemes it skips") {
    val result = SkippingListParser.parse(CalcLexer.tokenize("1+2*3").getOrThrow).failure
    result.recovered shouldBe Some(List(1, 2, 3))
    result.errors.map(_.at) shouldBe List(("+", 2), ("*", 4))
  }

  test("SkipOne recovers a SeparatedBy past the lexemes it skips") {
    val result = SkippingSeparatedParser.parse(CalcLexer.tokenize("1-2+-3").getOrThrow).failure
    result.recovered shouldBe Some("1-2-3")
    result.errors.map(_.at) shouldBe List(("+", 4))
  }

  case class BudgetContext(var skipsLeft: Int = 1) extends ParserCtx

  given ErrorHandling[BudgetContext, ParserError] = (ctx, _) =>
    if ctx.skipsLeft > 0 then
      ctx.skipsLeft -= 1
      ErrorHandling.Strategy.SkipOne
    else ErrorHandling.Strategy.Stop

  object BudgetParser extends Parser[BudgetContext]:
    val Expr: Rule[Int] = rule(
      { case (Expr(sum), CalcLexer.`+`(_), CalcLexer.Num(lexeme)) => sum + lexeme.value },
      { case CalcLexer.Num(lexeme) => lexeme.value },
    )
    val root: Rule[Int] = rule:
      case Expr(result) => result

  test("the strategy is given the parse's context and decides by it") {
    val lexemes = CalcLexer.tokenize("1++2++3").getOrThrow
    // each parse starts with a fresh context, so the budget is the same every time
    for _ <- 1 to 2 do
      val result = BudgetParser.parse(lexemes)
      result.ctx shouldBe BudgetContext(skipsLeft = 0)
      result.failure.recovered shouldBe None
      result.failure.errors.map(_.at) shouldBe List(("+", 3), ("+", 6))
  }

  test("getOrThrow throws every error, one message per line") {
    val exception = intercept[ParserException](SkippingParser.parse(CalcLexer.tokenize("1++2++3").getOrThrow).getOrThrow)
    exception.errors.map(_.unexpected.map(_.text)) shouldBe List(Some("+"), Some("+"))
    exception.getMessage shouldBe """Unexpected + "+". Expected one of: Num
                                    |Unexpected + "+". Expected one of: Num""".stripMargin
  }
