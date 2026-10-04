package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.internal.lexer.SubsetChecker
import halotukozak.alpaca.internal.show
import halotukozak.regex.Subset
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class SubsetCheckerTest extends AnyFunSuite with Matchers:

  private def check(patterns: String*) =
    SubsetChecker.checkRegexes(
      patterns.iterator
        .map: p =>
          Subset.parse(p) match
            case Right(subset) => (name = p, subset = subset)
            case Left(err) => fail(show"expected successful parse of /$p/, got $err")
        .toList,
    )

  test("checkRegexes should pass for non-overlapping patterns") {
    check(
      "[a-zA-Z_][a-zA-Z0-9_]*",
      "[+-]?[0-9]+",
      "=",
      "[ \\t\\n]+",
    ) shouldBe None
  }

  test("checkRegexes should report shadowing for overlapping patterns") {
    check(
      "[a-zA-Z_][a-zA-Z0-9_]*",
      "\\*",
      "=",
      "[a-zA-Z]+",
      "[ \\t\\n]+",
    ) shouldBe Some((first = "[a-zA-Z]+", second = List("[a-zA-Z_][a-zA-Z0-9_]*")))
  }

  test("checkRegexes should not report a pattern whose prefix an earlier one matches (longest match wins)") {
    check(
      "i",
      "\\*",
      "if",
      "=",
      "[ \\t\\n]+",
    ) shouldBe None
    check("a", "ab") shouldBe None
    check("[0-9]+", "[0-9]+(\\.[0-9]+)?") shouldBe None
  }

  test("checkRegexes should report a pattern fully covered by an earlier one even when it is longer") {
    check("[0-9]+(\\.[0-9]+)?", "[0-9]+") shouldBe Some((first = "[0-9]+", second = List("[0-9]+(\\.[0-9]+)?")))
    check("[a-z]+", "if") shouldBe Some((first = "if", second = List("[a-z]+")))
  }

  test("checkRegexes should report a pattern covered only by the union of earlier ones") {
    check("[a-m]", "[n-z]", "=", "[a-z]") shouldBe Some((first = "[a-z]", second = List("[a-m]", "[n-z]")))
  }

  test("checkRegexes should not report a pattern the union of earlier ones does not fully cover") {
    check("[a-m]+", "[n-z]+", "[a-z]+") shouldBe None
  }

  test("checkRegexes should report identical patterns as overlapping") {
    check(
      "[a-zA-Z_][a-zA-Z0-9_]*",
      "\\*",
      "=",
      "[a-zA-Z_][a-zA-Z0-9_]*",
      "[ \\t\\n]+",
    ) shouldBe Some((first = "[a-zA-Z_][a-zA-Z0-9_]*", second = List("[a-zA-Z_][a-zA-Z0-9_]*")))
  }

  test("checkRegexes should not report patterns in proper order") {
    check(
      "if",
      "\\*",
      "when",
      "i",
      "=",
      "[a-zA-Z_][a-zA-Z0-9_]*",
      "[ \\t\\n]+",
    ) shouldBe None
  }

  test("checkRegexes should handle empty pattern list") {
    SubsetChecker.checkRegexes(Nil) shouldBe None
  }

  test("checkRegexes should handle single pattern") {
    check("[a-zA-Z_][a-zA-Z0-9_]*") shouldBe None
  }

  test("handles CalcLexer-like patterns") {
    check(
      " ",
      "\\t",
      "[a-zA-Z_][a-zA-Z0-9_]*",
      "\\+",
      "-",
      "\\*",
      "/",
      "=",
      ",",
      "\\(",
      "\\)",
      "\\d+",
      "#.*",
      "\n+",
    ) shouldBe None
  }
