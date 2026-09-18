package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.AlgorithmError
import halotukozak.alpaca.{lexer, rule, ParserCtx, Rule, Token}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

// ParseTable.apply now requires a real macro Quotes (it calls report.errorAndAbort directly on
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

  test("unexpected token raises AlgorithmError naming the offending symbol") {
    val (_, lexemes) = CalcLexer.tokenize("+")

    val ex = intercept[AlgorithmError](CalcParser.parse(lexemes))
    ex.getMessage should (include("Unexpected symbol").and(include("Expected one of:")))
  }
