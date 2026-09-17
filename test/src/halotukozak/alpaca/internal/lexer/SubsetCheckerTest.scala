package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.internal.lexer.SubsetChecker
import halotukozak.regex.Subset
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class SubsetCheckerTest extends AnyFunSuite with Matchers:

  private def check(patterns: String*) =
    SubsetChecker.checkRegexes(
      patterns.iterator
        .map: p =>
          Subset.parse(p) match
            case Right(subset) => (name = p, subset = subset.withAnySuffix)
            case Left(err) => fail(s"expected successful parse of /$p/, got $err")
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
    ) shouldBe Some((first = "[a-zA-Z]+", second = "[a-zA-Z_][a-zA-Z0-9_]*"))
  }

  test("checkRegexes should report prefix shadowing") {
    check(
      "i",
      "\\*",
      "if",
      "=",
      "[a-zA-Z]+",
      "[ \\t\\n]+",
    ) shouldBe Some((first = "if", second = "i"))
  }

  test("checkRegexes should report identical patterns as overlapping") {
    check(
      "[a-zA-Z_][a-zA-Z0-9_]*",
      "\\*",
      "=",
      "[a-zA-Z_][a-zA-Z0-9_]*",
      "[ \\t\\n]+",
    ) shouldBe Some((first = "[a-zA-Z_][a-zA-Z0-9_]*", second = "[a-zA-Z_][a-zA-Z0-9_]*"))
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
