package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.{lexer, ErrorHandling, LexerCtx, LexerError, Result, Token}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ErrorHandlingStrategyTest extends AnyFunSuite with Matchers:

  extension [Ctx, Lex, Error](result: Result[Ctx, List[Lex], Error])
    private def failure: (recovered: Option[List[Lex]], errors: List[Error]) = result match
      case Result.Failure(_, recovered, errors) => (recovered, errors)
      case Result.Success(_, _) => fail("expected a failure")

  test("Strategy.Stop stops tokenization, reports the error and recovers nothing") {
    given ErrorHandling[LexerCtx.Default, LexerError] = (_, _) => ErrorHandling.Strategy.Stop

    val L = lexer:
      case "a" => Token["A"]

    val result = L.tokenize("aaabaa")
    result.failure.recovered shouldBe None
    result.failure.errors.map(e => (e.unexpected, e.line, e.column)) shouldBe List(("b", 1, 4))
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
    result.failure.errors.map(e => (e.unexpected, e.line, e.column)) shouldBe List(("b", 1, 3))
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
    result.failure.errors.map(e => (e.unexpected, e.line, e.column)) shouldBe List(("...", 1, 3), ("..", 1, 8))
    result.failure.errors.head.message shouldBe """Unexpected input "...""""
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
    result.failure.errors.map(e => (e.unexpected, e.line, e.column)) shouldBe List(("b", 1, 3))
    ignoredTokens shouldBe 1
  }

  test("a stop after a skip recovers nothing but reports both errors") {
    given ErrorHandling[LexerCtx.Default, LexerError] =
      (ctx, _) => if ctx.peek(1) == "!" then ErrorHandling.Strategy.SkipOne else ErrorHandling.Strategy.Stop

    val L = lexer:
      case "a" => Token["A"]

    val result = L.tokenize("a!a?a")
    result.failure.recovered shouldBe None
    result.failure.errors.map(_.unexpected) shouldBe List("!", "?")
  }

  test("default ErrorHandling for LexerCtx stops and reports the character") {
    val L = lexer[LexerCtx.Empty]:
      case "a" => Token["A"]

    val result = L.tokenize("b")
    result.failure.errors.map(_.unexpected) shouldBe List("b")
    result.failure.errors.head.message shouldBe "Unexpected character 'b'"
  }

  test("an error carries a custom context's fields, whatever their names") {
    final case class Tracked(col: Column = Column.Start, ln: Line = Line.Start) extends LexerCtx

    val L = lexer[Tracked]:
      case "a" => Token["A"]
      case "\n" => Token.Ignored

    L.tokenize("a\naab").failure.errors.map(e => (e.unexpected, e.ln, e.col)) shouldBe List(("b", 2, 3))
  }

  test("the strategy is given the error for the unmatched character") {
    var seen = List.empty[(String, Int, Int)]
    given ErrorHandling[LexerCtx.Default, LexerError.Of[LexerCtx.Default]] = (_, error) =>
      seen = seen :+ (error.unexpected, error.line, error.column)
      ErrorHandling.Strategy.SkipToNextMatch

    val L = lexer:
      case "a" => Token["A"]

    // the strategy sees the first unmatched character; the reported error covers the whole skipped run
    L.tokenize("a!?a#").failure.errors.map(e => (e.unexpected, e.line, e.column)) shouldBe List(("!?", 1, 2), ("#", 1, 5))
    seen shouldBe List(("!", 1, 2), ("#", 1, 5))
  }

  test("the strategy reads a custom context's fields from the error") {
    final case class MyCtx(line: Line = Line.Start) extends LexerCtx
    given ErrorHandling[MyCtx, LexerError.Of[MyCtx]] =
      (_, error) => if error.line > 1 then ErrorHandling.Strategy.Stop else ErrorHandling.Strategy.SkipOne

    val L = lexer[MyCtx]:
      case "a" => Token["A"]
      case "\n" => Token.Ignored

    val result = L.tokenize("a!a\n?a!")
    result.failure.recovered shouldBe None
    result.failure.errors.map(e => (e.unexpected, e.line)) shouldBe List(("!", 1), ("?", 2))
  }

  test("peek should expose at most n characters of the unmatched input to a custom ErrorHandling instance") {
    var seen = List.empty[String]
    given ErrorHandling[LexerCtx.Default, LexerError] = (ctx, _) =>
      seen = seen :+ ctx.peek(2)
      ErrorHandling.Strategy.SkipOne

    val L = lexer:
      case "a" => Token["A"]

    L.tokenize("a!aa?"): Unit
    seen shouldBe List("!a", "?")
  }

  test("peek should reject a negative length") {
    given ErrorHandling[LexerCtx.Default, LexerError] = (ctx, _) =>
      ctx.peek(-1): Unit
      ErrorHandling.Strategy.Stop

    val L = lexer:
      case "a" => Token["A"]

    an[IllegalArgumentException] should be thrownBy L.tokenize("!")
  }

  test("Strategy.SkipOne should update column correctly") {
    given ErrorHandling[LexerCtx.Default, LexerError] = (_, _) => ErrorHandling.Strategy.SkipOne

    val L = lexer:
      case "a" => Token["A"]

    val result = L.tokenize("a!a")
    result.failure.recovered.map(_.map(_.name)) shouldBe Some(List("A", "A"))
    // Default context has column tracking
    result.ctx.column shouldBe 4 // 'a' (1) + '!' (2) + 'a' (3) -> next is 4
  }
