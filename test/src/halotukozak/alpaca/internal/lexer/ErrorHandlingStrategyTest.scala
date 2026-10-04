package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.{lexer, LexerCtx, Token}
import halotukozak.alpaca.internal.lexer.ErrorHandling
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ErrorHandlingStrategyTest extends AnyFunSuite with Matchers:

  test("Strategy.Throw should throw the provided exception") {
    class MyException extends RuntimeException("test")
    given ErrorHandling[LexerCtx.Default] = _ => ErrorHandling.Strategy.Throw(new MyException)

    val L = lexer:
      case "a" => Token["A"]

    intercept[MyException]:
      L.tokenize("b")
  }

  test("Strategy.Stop should stop tokenization and return collected lexemes") {
    given ErrorHandling[LexerCtx.Default] = _ => ErrorHandling.Strategy.Stop

    val L = lexer:
      case "a" => Token["A"]

    val (ctx, res) = L.tokenize("aaabaa")
    res.map(_.name) shouldBe List("A", "A", "A")
    ctx.text.toString shouldBe "" // Stop currently sets text to empty string
  }

  test("Strategy.IgnoreChar should skip one character and continue") {
    var ignoredChars = 0
    given ErrorHandling[LexerCtx.Default] = _ =>
      ignoredChars += 1
      ErrorHandling.Strategy.IgnoreChar

    val L = lexer:
      case "a" => Token["A"]

    val (_, res) = L.tokenize("aabaa")
    res.map(_.name) shouldBe List("A", "A", "A", "A")
    ignoredChars shouldBe 1
  }

  test("Strategy.IgnoreToken should skip until next match and continue") {
    var ignoredTokens = 0
    given ErrorHandling[LexerCtx.Default] = _ =>
      ignoredTokens += 1
      ErrorHandling.Strategy.IgnoreToken

    val L = lexer:
      case "a" => Token["A"]
      case "b" => Token["B"]

    val (_, res) = L.tokenize("aa...bb..a")
    res.map(_.name) shouldBe List("A", "A", "B", "B", "A")
    ignoredTokens shouldBe 2
  }

  test("Strategy.IgnoreToken should skip only char if no token matched") {
    var ignoredTokens = 0
    given ErrorHandling[LexerCtx.Default] = _ =>
      ignoredTokens += 1
      ErrorHandling.Strategy.IgnoreToken

    val L = lexer:
      case "a" => Token["A"]

    val (_, res) = L.tokenize("aab")
    res.map(_.name) shouldBe List("A", "A")
    ignoredTokens shouldBe 1
  }

  test("default ErrorHandling for LexerCtx throws on unexpected character") {
    val L = lexer[LexerCtx.Empty]:
      case "a" => Token["A"]

    val exception = intercept[RuntimeException]:
      L.tokenize("b")

    exception.getMessage should include("Unexpected character: 'b'")
  }

  test("remainingText should expose the unmatched input to a custom ErrorHandling instance") {
    var seenFirstChar: Char = ' '
    given ErrorHandling[LexerCtx.Default] = ctx =>
      seenFirstChar = ctx.remainingText.charAt(0)
      ErrorHandling.Strategy.IgnoreChar

    val L = lexer:
      case "a" => Token["A"]

    L.tokenize("a!a"): Unit
    seenFirstChar shouldBe '!'
  }

  test("Strategy.IgnoreChar should update position correctly") {
    given ErrorHandling[LexerCtx.Default] = _ => ErrorHandling.Strategy.IgnoreChar

    val L = lexer:
      case "a" => Token["A"]

    val (ctx, res) = L.tokenize("a!a")
    res.map(_.name) shouldBe List("A", "A")
    // Default context has position tracking
    ctx.position shouldBe 4 // 'a' (1) + '!' (2) + 'a' (3) -> next is 4
  }
