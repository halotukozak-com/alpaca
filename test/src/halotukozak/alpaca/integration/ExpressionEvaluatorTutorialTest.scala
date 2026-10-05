package halotukozak
package alpaca
package integration

import alpaca.{Rule, Token}
import org.scalatest.funsuite.AnyFunSuite

/** Validates the expression-evaluator tutorial code compiles and runs correctly. */
final class ExpressionEvaluatorTutorialTest extends AnyFunSuite:
  val CalcLexer = lexer:
    // Ignore whitespace and comments
    case "\\s+" => Token.Ignored
    case "#.*" => Token.Ignored

    // Multi-character operators FIRST to avoid partial matching
    case "\\*\\*" => Token["exp"]
    case "//" => Token["fdiv"]

    // Single-character operators
    case literal @ ("\\+" | "-" | "\\*" | "/" | "%" | "\\(" | "\\)" | ",") =>
      Token[literal.type]

    // Keywords (constants and functions)
    case keyword @ ("pi" | "e" | "sin" | "cos" | "tan" | "atan2") =>
      Token[keyword.type]

    // Numbers (floats and ints)
    case x @ """(\d+\.\d*|\.\d+)([eE][+-]?\d+)?""" => Token["float"](x.toDouble)
    case x @ "\\d+" => Token["int"](x.toInt)

  object ExprEvalParser extends Parser:
    val root: Rule[Double] = rule:
      case Expr(v) => v

    val Expr: Rule[Double] = rule(
      // Binary arithmetic
      "plus" { case (Expr(a), CalcLexer.`\\+`(_), Expr(b)) => a + b },
      "minus" { case (Expr(a), CalcLexer.`-`(_), Expr(b)) => a - b },
      "times" { case (Expr(a), CalcLexer.`\\*`(_), Expr(b)) => a * b },
      "divide" { case (Expr(a), CalcLexer.`/`(_), Expr(b)) => a / b },
      "exp" { case (Expr(a), CalcLexer.`exp`(_), Expr(b)) => math.pow(a, b) },

      // Unary operators
      "uminus" { case (CalcLexer.`-`(_), Expr(a)) => -a },

      // Constants and Functions
      "pi" { case CalcLexer.pi(_) => math.Pi },
      "sin" { case (CalcLexer.sin(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.sin(a) },
      "atan2" { case (CalcLexer.atan2(_), CalcLexer.`\\(`(_), Expr(y), CalcLexer.`,`(_), Expr(x), CalcLexer.`\\)`(_)) =>
        math.atan2(y, x)
      },

      // Parentheses and literals
      { case (CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => a },
      { case CalcLexer.float(x) => x.value },
      { case CalcLexer.int(n) => n.value.toDouble },
    )

  given Resolutions[ExprEvalParser.type] = resolutions(
    // Exponentiation: right-associative, highest binary precedence
    CalcLexer.exp.before(production.uminus, production.exp, production.times, production.divide),
    production.exp.before(CalcLexer.`\\*`, CalcLexer.`/`),

    // Unary minus binds tighter than * /
    production.uminus.before(CalcLexer.`\\*`, CalcLexer.`/`),

    // Multiplication/division: left-associative, higher than +/-
    production.times.before(CalcLexer.`\\*`, CalcLexer.`/`),
    production.divide.before(CalcLexer.`\\*`, CalcLexer.`/`),

    // Addition/subtraction: left-associative, lowest binary precedence
    production.plus.after(CalcLexer.`\\*`, CalcLexer.`/`),
    production.plus.before(CalcLexer.`\\+`, CalcLexer.`-`),
    production.minus.after(CalcLexer.`\\*`, CalcLexer.`/`),
    production.minus.before(CalcLexer.`\\+`, CalcLexer.`-`),
  )

  test("basic arithmetic") {
    val (_, lexemes) = CalcLexer.tokenize("1 + 2 * 3")
    val result = ExprEvalParser.parse(lexemes).getOrThrow
    assert(result == 7.0)
  }

  test("sin(pi / 2) + 2 ** 3 * 4") {
    val (_, lexemes) = CalcLexer.tokenize("sin(pi / 2) + 2 ** 3 * 4")
    val result = ExprEvalParser.parse(lexemes).getOrThrow
    assert(result == 33.0)
  }
