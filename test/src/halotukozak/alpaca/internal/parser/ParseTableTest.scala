package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.{lexer, ParserCtx, Production as P, Token}
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.typeCheckErrors

final class ParseTableTest extends AnyFunSuite with Matchers with LoneElement:

  val CalcLexer = lexer:
    case "\\+" => Token["+"]
    case value @ "[1-9][0-9]*" => Token["Num"](value.toInt)

  case class CalcContext() extends ParserCtx

  test("parse table Shift-Reduce conflict") {
    typeCheckErrors("""
    object ShiftReduceCalcParser extends Parser[CalcContext]:
      val Expr: Rule[Int] = rule(
        { case (Expr(expr1), CalcLexer.`+`(_), Expr(expr2)) => expr1 + expr2 },
        { case CalcLexer.Num(lexem) => lexem.value },
      )

      val root = rule:
       case Expr(expr) => expr 
    """).loneElement.lineContent.trim shouldBe "{ case (Expr(expr1), CalcLexer.`+`(_), Expr(expr2)) => expr1 + expr2 },"
  }

  test("parse table Shift-Reduce conflict message") {
    typeCheckErrors("""
    object ShiftReduceCalcParser extends Parser[CalcContext]:
      val Expr: Rule[Int] = rule(
        { case (Expr(expr1), CalcLexer.`+`(_), Expr(expr2)) => expr1 + expr2 },
        { case CalcLexer.Num(lexem) => lexem.value },
      )

      val root = rule:
       case Expr(expr) => expr 
    """).loneElement.message should
      include("""
                |Shift "+ ($plus)" vs Reduce Expr -> Expr + ($plus) Expr
                |In situation like:
                |Expr + ($plus) Expr + ($plus) ...
                |Consider marking production Expr -> Expr + ($plus) Expr to be before or after "+ ($plus)"
                |""".stripMargin)
  }

  test("parse table Reduce-Reduce conflict") {
    val conflict: scala.compiletime.testing.Error = typeCheckErrors("""
    object ReduceReduceCalcParser extends Parser[CalcContext]:
      val Integer = rule:
       case CalcLexer.Num(lexem) => lexem.value 

      val Float = rule:
        case CalcLexer.Num(lexem) => lexem.value.toFloat 

      val Expr = rule[Any](
        { case Integer(value) => value },
        { case Float(value) => value },
      )

      val root = rule:
       case Expr(expr) => expr
    """).loneElement
    conflict.message should include("""
                                   |Reduce Float -> Num vs Reduce Integer -> Num
                                   |In situation like:
                                   |Num ...
                                   |Conflicting production: Float -> Num (line 7)
                                   |Consider marking one of the productions to be before or after the other
                                   |""".stripMargin)
    conflict.lineContent.trim shouldBe "case CalcLexer.Num(lexem) => lexem.value"
  }

  test("parse table reports every conflict, each at its own production") {
    typeCheckErrors("""
    object MultiConflictParser extends Parser[CalcContext]:
      val Integer = rule:
        case CalcLexer.Num(lexem) => lexem.value
      val Float = rule:
        case CalcLexer.Num(lexem) => lexem.value
      val Expr: Rule[Int] = rule(
        { case (Expr(a), CalcLexer.`+`(_), Expr(b)) => a + b },
        { case Integer(i) => i },
        { case Float(f) => f },
      )
      val root = rule:
       case Expr(e) => e
    """).map(e => (e.message.linesIterator.find(_.nonEmpty).get, e.lineContent.trim)) should contain theSameElementsAs List(
      ("Shift \"+ ($plus)\" vs Reduce Expr -> Expr + ($plus) Expr", "{ case (Expr(a), CalcLexer.`+`(_), Expr(b)) => a + b },"),
      ("Reduce Float -> Num vs Reduce Integer -> Num", "case CalcLexer.Num(lexem) => lexem.value"),
    )
  }

  test("conflict resolution cycle detection") {
    val cycle: scala.compiletime.testing.Error = typeCheckErrors("""
    object CycleParser extends Parser[CalcContext]:
      val A = rule("A" { case CalcLexer.Num(lexem) => lexem.value })
      val B = rule:
       case CalcLexer.`+`(_) => "+"
      val root = rule:
       case A(a) => a 

    given Resolutions[CycleParser.type] = resolutions(
        production.A.before(CalcLexer.`+`),
        CalcLexer.`+`.before(P(CalcLexer.`+`)),
        P(CalcLexer.`+`).before(production.A),
      )
    """).loneElement
    cycle.message should
      include("""
                |Inconsistent conflict resolution detected:
                |Reduction(A) before Shift(+) before Reduction(+ ($plus) -> B) before Reduction(A)
                |There are elements being both before and after Reduction(A) at the same time.
                |Consider revising the before/after rules to eliminate cycles
                |""".stripMargin)
    // points at the rule closing the cycle, not at the parser declaration
    cycle.lineContent.trim shouldBe "P(CalcLexer.`+`).before(production.A),"
    cycle.column shouldBe 32
  }
