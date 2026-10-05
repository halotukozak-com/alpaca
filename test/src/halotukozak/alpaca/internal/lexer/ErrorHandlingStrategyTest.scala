package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.{lexer, ErrorHandling, LexerCtx, LexerError, Result, Token}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ErrorHandlingStrategyTest extends AnyFunSuite with Matchers:

  extension [Ctx, L](result: Result[Ctx, List[L], LexerError])
    private def failure: (recovered: Option[List[L]], errors: List[LexerError]) = result match
      case Result.Failure(_, recovered, errors) => (recovered, errors)
      case Result.Success(_, _) => fail("expected a failure")

  test("Strategy.Stop stops tokenization, reports the error and recovers nothing") {
    given ErrorHandling[LexerCtx.Default, LexerError] = (_, _) => ErrorHandling.Strategy.Stop

    val L = lexer:
      case "a" => Token["A"]

    val result = L.tokenize("aaabaa")
    result.failure.recovered shouldBe None
    result.failure.errors shouldBe List(LexerError("b", Some(1), Some(4)))
    result.ctx.text.toString shouldBe "" // Stop currently sets text to empty string
  }

  test("Strategy.SkipOne skips one character, reports it and recovers the lexemes") {
    var ignoredChars = 0
    given ErrorHandling[LexerCtx.Default, LexerError] = (_, _) =>
      ignoredChars += 1
      ErrorHandling.Strategy.SkipOne

    val L = lexer:
      case "a" => Token["A"]

    val result = L.tokenize("aabaa")
    result.failure.recovered.map(_.map(_.name)) shouldBe Some(List("A", "A", "A", "A"))
    result.failure.errors shouldBe List(LexerError("b", Some(1), Some(3)))
    ignoredChars shouldBe 1
  }

  test("Strategy.SkipToNextMatch skips until the next match and reports each skipped run") {
    var ignoredTokens = 0
    given ErrorHandling[LexerCtx.Default, LexerError] = (_, _) =>
      ignoredTokens += 1
      ErrorHandling.Strategy.SkipToNextMatch

    val L = lexer:
      case "a" => Token["A"]
      case "b" => Token["B"]

    val result = L.tokenize("aa...bb..a")
    result.failure.recovered.map(_.map(_.name)) shouldBe Some(List("A", "A", "B", "B", "A"))
    result.failure.errors shouldBe List(LexerError("...", Some(1), Some(3)), LexerError("..", Some(1), Some(8)))
    result.failure.errors.head.message shouldBe """Unexpected input "..." at line 1, column 3"""
    ignoredTokens shouldBe 2
  }

  test("Strategy.SkipToNextMatch skips only the character if no token matches after it") {
    var ignoredTokens = 0
    given ErrorHandling[LexerCtx.Default, LexerError] = (_, _) =>
      ignoredTokens += 1
      ErrorHandling.Strategy.SkipToNextMatch

    val L = lexer:
      case "a" => Token["A"]

    val result = L.tokenize("aab")
    result.failure.recovered.map(_.map(_.name)) shouldBe Some(List("A", "A"))
    result.failure.errors shouldBe List(LexerError("b", Some(1), Some(3)))
    ignoredTokens shouldBe 1
  }

  test("a stop after a skip recovers nothing but reports both errors") {
    given ErrorHandling[LexerCtx.Default, LexerError] = (ctx, _) =>
      if ctx.remainingText.charAt(0) == '!' then ErrorHandling.Strategy.SkipOne else ErrorHandling.Strategy.Stop

    val L = lexer:
      case "a" => Token["A"]

    val result = L.tokenize("a!a?a")
    result.failure.recovered shouldBe None
    result.failure.errors.map(_.unexpected) shouldBe List("!", "?")
  }

  test("default ErrorHandling for LexerCtx stops and reports the character without a position") {
    val L = lexer[LexerCtx.Empty]:
      case "a" => Token["A"]

    val result = L.tokenize("b")
    result.failure.errors shouldBe List(LexerError("b", None, None))
    result.failure.errors.head.message shouldBe "Unexpected character 'b'"
  }

  test("a custom context with line and position fields gets positioned errors") {
    final case class Tracked(position: Column = Column.Start, line: Line = Line.Start) extends LexerCtx

    val L = lexer[Tracked]:
      case "a" => Token["A"]
      case "\n" => Token.Ignored

    L.tokenize("a\naab").failure.errors shouldBe List(LexerError("b", Some(2), Some(3)))
  }

  test("the strategy is given the error for the unmatched character") {
    var seen = List.empty[LexerError]
    given ErrorHandling[LexerCtx.Default, LexerError] = (_, error) =>
      seen = seen :+ error
      ErrorHandling.Strategy.SkipToNextMatch

    val L = lexer:
      case "a" => Token["A"]

    // the strategy sees the first unmatched character; the reported error covers the whole skipped run
    L.tokenize("a!?a#").failure.errors shouldBe List(LexerError("!?", Some(1), Some(2)), LexerError("#", Some(1), Some(5)))
    seen shouldBe List(LexerError("!", Some(1), Some(2)), LexerError("#", Some(1), Some(5)))
  }

  test("remainingText should expose the unmatched input to a custom ErrorHandling instance") {
    var seenFirstChar: Char = ' '
    given ErrorHandling[LexerCtx.Default, LexerError] = (ctx, _) =>
      seenFirstChar = ctx.remainingText.charAt(0)
      ErrorHandling.Strategy.SkipOne

    val L = lexer:
      case "a" => Token["A"]

    L.tokenize("a!a"): Unit
    seenFirstChar shouldBe '!'
  }

  test("Strategy.SkipOne should update position correctly") {
    given ErrorHandling[LexerCtx.Default, LexerError] = (_, _) => ErrorHandling.Strategy.SkipOne

    val L = lexer:
      case "a" => Token["A"]

    val result = L.tokenize("a!a")
    result.failure.recovered.map(_.map(_.name)) shouldBe Some(List("A", "A"))
    // Default context has position tracking
    result.ctx.position shouldBe 4 // 'a' (1) + '!' (2) + 'a' (3) -> next is 4
  }
