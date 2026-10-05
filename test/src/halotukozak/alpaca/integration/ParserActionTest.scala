package halotukozak
package alpaca
package integration

import org.scalatest.funsuite.AnyFunSuite

final class ParserActionTest extends AnyFunSuite:
  test("multiline action in parser rule - single production") {
    val BugLexer = lexer:
      case "T" => Token["T"]

    object SingleActionBug extends Parser:
      override val root: Rule[Any] = rule:
        case BugLexer.T(_) =>
          val x = 1
          x

    val lexemes = BugLexer.tokenize("T").getOrThrow
    val result = SingleActionBug.parse(lexemes).getOrThrow
    assert(result == 1)
  }

  test("multiline action in parser rule - multiple productions") {
    val BugLexer = lexer:
      case "\\s+" => Token.Ignored
      case x @ "[0-9]+" => Token["num"](x.toInt)
      case "\\+" => Token["+"]

    object MultiActionBug extends Parser:
      val Expr: Rule[Int] = rule(
        "add" { case (Expr(a), BugLexer.`+`(_), Expr(b)) =>
          val sum = a + b
          sum
        },
        { case BugLexer.num(n) =>
          val v = n.value
          v
        },
      )
      override val root: Rule[Int] = rule:
        case Expr(v) =>
          val result = v * 2
          result

    given Resolutions[MultiActionBug.type] = resolutions(
      production.add.before(BugLexer.`+`),
    )

    val lexemes = BugLexer.tokenize("1 + 2").getOrThrow
    val result = MultiActionBug.parse(lexemes).getOrThrow
    assert(result == 6) // (1 + 2) * 2
  }

  test("lambda action in parser rule (#147)") {
    val BugLexer = lexer:
      case "T" => Token["T"]

    object LambdaActionBug extends Parser:
      val R1: Rule[String] = rule:
        case BugLexer.T(_) => "T"

      val R2: Rule[Any => String] = rule:
        case R1(op) => _ => op

      override val root: Rule[Any] = rule:
        case R2(f) => f(())

    val lexemes = BugLexer.tokenize("T").getOrThrow
    val result = LambdaActionBug.parse(lexemes).getOrThrow
    assert(result == "T")
  }
