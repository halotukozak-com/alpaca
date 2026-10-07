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

  // compiled with -Wall -Werror in CI, where a spurious -Wtostring-interpolated fails the build (#673)
  test("string interpolation of bound values in an action") {
    val InterpLexer = lexer:
      case "\\s+" => Token.Ignored
      case x @ "[a-z]+" => Token["ID"](x)
      case x @ "[0-9]+" => Token["NUM"](x.toInt)
      case "=" => Token["EQ"]
      case ":" => Token["COLON"]

    object LexemeInterp extends Parser:
      override val root: Rule[String] = rule(
        { case (InterpLexer.ID(l), InterpLexer.EQ(_), InterpLexer.ID(r)) => s"${l.value}=${r.value}" },
        { case (InterpLexer.ID(l), InterpLexer.COLON(_), InterpLexer.NUM(r)) => s"${l.text}:${r.text}" },
      )

    object RuleInterp extends Parser:
      val Name: Rule[String] = rule:
        case InterpLexer.ID(l) => l.value
      val Num: Rule[Int] = rule:
        case InterpLexer.NUM(n) => n.value
      override val root: Rule[String] = rule(
        { case (Name(l), InterpLexer.EQ(_), Name(r)) => s"$l=$r" },
        { case (Name(l), InterpLexer.COLON(_), Num(r)) => s"$l:$r" },
      )

    assert(LexemeInterp.parse(InterpLexer.tokenize("a = b").getOrThrow).getOrThrow == "a=b")
    assert(LexemeInterp.parse(InterpLexer.tokenize("a : 42").getOrThrow).getOrThrow == "a:42")
    assert(RuleInterp.parse(InterpLexer.tokenize("a = b").getOrThrow).getOrThrow == "a=b")
    assert(RuleInterp.parse(InterpLexer.tokenize("a : 42").getOrThrow).getOrThrow == "a:42")
  }
