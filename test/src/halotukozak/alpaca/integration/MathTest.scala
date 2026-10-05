package halotukozak
package alpaca
package integration

import halotukozak.alpaca.{lexer, resolutions, rule, withLazyReader, Resolutions, Rule, Token}
import halotukozak.alpaca.internal.parser.Parser
import org.scalatest.funsuite.AnyFunSuite

final class MathTest extends AnyFunSuite:
  test("e2e math test") {
    val CalcLexer = lexer:
      // ignore whitespace/comments
      case _ @ "[ \t\r\n]+" => Token.Ignored
      case _ @ "#.*" => Token.Ignored

      // multi-char ops FIRST
      case "\\*\\*" => Token["exp"]
      case "//" => Token["fdiv"]

      // single-char ops
      case literal @ ("\\+" | "-" | "\\*" | "/" | "%" | "\\(" | "\\)" | "!" | ",") =>
        Token[literal.type]

      // math constants (lowercase)
      case keyword @ ("pi" | "e" | "tau" | "inf" | "nan") =>
        Token[keyword.type]

      // trig functions (lowercase)
      case keyword @ ("atan2" | "sinh" | "cosh" | "tanh") =>
        Token[keyword.type]

      case keyword @ ("sin" | "cos" | "tan" | "asin" | "acos" | "atan") =>
        Token[keyword.type]

      // numbers
      case x @ "(\\d+\\.\\d*|\\.\\d+)([eE][+-]?\\d+)?" => Token["float"](x.toDouble)
      case x @ "\\d+" => Token["int"](x.toInt)

    object MathParser extends Parser:
      val Expr: Rule[Double] = rule(
        // binary arithmetic
        "plus" { case (Expr(a), CalcLexer.`\\+`(_), Expr(b)) => a + b },
        "minus" { case (Expr(a), CalcLexer.`-`(_), Expr(b)) => a - b },
        "times" { case (Expr(a), CalcLexer.`\\*`(_), Expr(b)) => a * b },
        "divide" { case (Expr(a), CalcLexer.`/`(_), Expr(b)) => a / b },
        "mod" { case (Expr(a), CalcLexer.`%`(_), Expr(b)) => a % b },
        "fdiv" { case (Expr(a), CalcLexer.`fdiv`(_), Expr(b)) => math.floor(a / b) },
        "exp" { case (Expr(a), CalcLexer.`exp`(_), Expr(b)) => math.pow(a, b) },

        // unary operators
        "uminus" { case (CalcLexer.`-`(_), Expr(a)) => -a },
        "uplus" { case (CalcLexer.`\\+`(_), Expr(a)) => +a },

        // math constants
        "const_pi" { case CalcLexer.pi(_) => math.Pi },
        "const_e" { case CalcLexer.e(_) => math.E },
        "const_tau" { case CalcLexer.tau(_) => 2.0 * math.Pi },
        "const_inf" { case CalcLexer.inf(_) => Double.PositiveInfinity },
        "const_nan" { case CalcLexer.nan(_) => Double.NaN },

        // trig functions (radians)
        "sin" { case (CalcLexer.sin(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.sin(a) },
        "cos" { case (CalcLexer.cos(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.cos(a) },
        "tan" { case (CalcLexer.tan(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.tan(a) },
        "asin" { case (CalcLexer.asin(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.asin(a) },
        "acos" { case (CalcLexer.acos(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.acos(a) },
        "atan" { case (CalcLexer.atan(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.atan(a) },
        "atan2" {
          case (CalcLexer.atan2(_), CalcLexer.`\\(`(_), Expr(y), CalcLexer.`,`(_), Expr(x), CalcLexer.`\\)`(_)) =>
            math.atan2(y, x)
        },
        "sinh" { case (CalcLexer.sinh(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.sinh(a) },
        "cosh" { case (CalcLexer.cosh(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.cosh(a) },
        "tanh" { case (CalcLexer.tanh(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.tanh(a) },

        // parentheses
        { case (CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => a },

        // literals
        { case CalcLexer.float(x) => x.value },
        { case CalcLexer.int(n) => n.value.toDouble },
      )
      val root: Rule[Double] = rule:
        case Expr(v) => v

    given Resolutions[MathParser.type] = resolutions(
      CalcLexer.exp.before(
        production.uplus,
        production.uminus,
        production.mod,
        production.exp,
        production.fdiv,
        production.times,
        production.divide,
      ),
      production.exp.before(CalcLexer.`\\*`, CalcLexer.`/`, CalcLexer.fdiv, CalcLexer.`%`),
      production.uplus.before(CalcLexer.`\\*`, CalcLexer.`/`, CalcLexer.fdiv, CalcLexer.`%`),
      production.uminus.before(CalcLexer.`\\*`, CalcLexer.`/`, CalcLexer.fdiv, CalcLexer.`%`),
      production.times.before(CalcLexer.`\\*`, CalcLexer.`/`, CalcLexer.fdiv, CalcLexer.`%`),
      production.divide.before(CalcLexer.`\\*`, CalcLexer.`/`, CalcLexer.fdiv, CalcLexer.`%`),
      production.fdiv.before(CalcLexer.`\\*`, CalcLexer.`/`, CalcLexer.fdiv, CalcLexer.`%`),
      production.mod.before(CalcLexer.`\\*`, CalcLexer.`/`, CalcLexer.fdiv, CalcLexer.`%`),
      production.plus.after(CalcLexer.`\\*`, CalcLexer.`/`, CalcLexer.fdiv, CalcLexer.`%`),
      production.plus.before(CalcLexer.`\\+`, CalcLexer.`-`),
      production.minus.after(CalcLexer.`\\*`, CalcLexer.`/`, CalcLexer.fdiv, CalcLexer.`%`),
      production.minus.before(CalcLexer.`\\+`, CalcLexer.`-`),
    )

    val input = "1 + 2"
    val lexemes = CalcLexer.tokenize(input).getOrThrow
    val result = MathParser.parse(lexemes).getOrThrow
    assert(result == 3.0)

    withLazyReader("""
      ((12 + 7) * (3 - 8 / (4 + 2)) + (15 - (9 - 3 * (2 + 1))) / 5) 
      * ((6 * (2 + 3) - (4 - 7) * (8 / 2)) + (9 + (10 - 4) * (3 + 2) / (6 - 1))) 
      - (24 / (3 + 1) * (7 - 5) + ((9 - 2 * (3 + 1)) * (8 / 4 - (6 - 2)))) 
      + (11 * (2 + (5 - 3) * (9 - (8 / (4 - 2)))) - ((13 - 7) / (5 + 1) * (2 * 3 - 4)))
    """): input =>
      val lexemes = CalcLexer.tokenize(input).getOrThrow
      val result = MathParser.parse(lexemes).getOrThrow
      assert(result == 2096.0)

    withLazyReader("""
      + sin(pi/6) + cos(pi/3) + tan(pi/4)
      + (2 ** 3 ** 2) / (3 + 1)
      + ((7 // 3) * 5 + (10 % 4))
      - (-cos(0) + +sin(pi/2))
      + (((12 / 5) + (20 // 3) - (17 % 5)) * ((3 + 2) ** 3 / (2 ** 3)))
      + atan2(1, 0)
    """) { input =>
      val lexemes = CalcLexer.tokenize(input).getOrThrow
      val result = MathParser.parse(lexemes).getOrThrow
      val expected = 2.0 + 128.0 + 12.0 + 0.0 + 100.0 + (math.Pi / 2.0)

      assert(result == expected)
    }
  }
