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
    """).loneElement.message should include("""
                                              |Shift "+" vs Reduce Expr -> Expr + Expr
                                              |In situation like:
                                              |Expr + Expr + ...
                                              |Consider marking production Expr -> Expr + Expr to be before or after "+"
                                              |""".stripMargin)
  }

  test("conflict messages show EBNF non-terminals as the extractor that created them") {
    val message = typeCheckErrors("""
    object ListConflictParser extends Parser[CalcContext]:
      val Expr: Rule[Int] = rule(
        { case (Expr(expr1), CalcLexer.`+`(_), Expr(expr2)) => expr1 + expr2 },
        { case CalcLexer.Num(lexem) => lexem.value },
      )

      val root = rule:
       case (CalcLexer.`+`(_), Expr.List(exprs)) => exprs.sum
    """).map(_.message).mkString("\n")
    message should include("Expr.List")
    (message should not).include("synthetic")
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
    """).map(e => (e.message.linesIterator.find(_.nonEmpty).get, e.lineContent.trim)) should contain theSameElementsAs
      List(
        (
          "Shift \"+\" vs Reduce Expr -> Expr + Expr",
          "{ case (Expr(a), CalcLexer.`+`(_), Expr(b)) => a + b },",
        ),
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
    cycle.message should startWith("""
                                     |Inconsistent conflict resolution detected:
                                     |A -> Num (A) before "+" before B -> + before A -> Num (A)
                                     |There are elements being both before and after A -> Num (A) at the same time.
                                     |Consider revising the before/after rules to eliminate cycles
                                     |""".stripMargin)
    // points at the rule closing the cycle, not at the parser declaration
    cycle.lineContent.trim shouldBe "P(CalcLexer.`+`).before(production.A),"
    cycle.column shouldBe 32
  }

  test("resolutions not defined by a direct `resolutions` call are reported at the given, not ignored") {
    val alias: scala.compiletime.testing.Error = typeCheckErrors("""
    object AliasParser extends Parser[CalcContext]:
      val Expr: Rule[Int] = rule(
        { case (Expr(a), CalcLexer.`+`(_), Expr(b)) => a + b },
        { case CalcLexer.Num(lexem) => lexem.value },
      )
      val root = rule:
       case Expr(e) => e

    val myResolutions = resolutions[AliasParser.type](P(AliasParser.Expr, CalcLexer.`+`, AliasParser.Expr).before(CalcLexer.`+`))
    given Resolutions[AliasParser.type] = myResolutions
    """).loneElement
    alias.message should include("Cannot read the conflict resolutions of AliasParser.")
    alias.lineContent.trim shouldBe "given Resolutions[AliasParser.type] = myResolutions"
    alias.column shouldBe 42

    val block: scala.compiletime.testing.Error = typeCheckErrors("""
    object BlockParser extends Parser[CalcContext]:
      val Expr: Rule[Int] = rule(
        { case (Expr(a), CalcLexer.`+`(_), Expr(b)) => a + b },
        { case CalcLexer.Num(lexem) => lexem.value },
      )
      val root = rule:
       case Expr(e) => e

    given Resolutions[BlockParser.type] = {
      val unused = 1
      resolutions(P(BlockParser.Expr, CalcLexer.`+`, BlockParser.Expr).before(CalcLexer.`+`))
    }
    """).loneElement
    block.message should include("Cannot read the conflict resolutions of BlockParser.")
    block.lineContent.trim shouldBe "given Resolutions[BlockParser.type] = {"
  }

  test("resolutions given with a using clause are read") {
    typeCheckErrors("""
    object UsingParser extends Parser[CalcContext]:
      val Expr: Rule[Int] = rule(
        { case (Expr(a), CalcLexer.`+`(_), Expr(b)) => a + b },
        { case CalcLexer.Num(lexem) => lexem.value },
      )
      val root = rule:
       case Expr(e) => e

    given (using DummyImplicit): Resolutions[UsingParser.type] =
      resolutions(P(UsingParser.Expr, CalcLexer.`+`, UsingParser.Expr).before(CalcLexer.`+`))
    """) shouldBe empty
  }

  test("production referenced by an ambiguous RHS is reported at the reference") {
    val ambiguous: scala.compiletime.testing.Error = typeCheckErrors("""
    object AmbiguousParser extends Parser[CalcContext]:
      val Integer = rule:
        case CalcLexer.Num(lexem) => lexem.value
      val Float = rule:
        case CalcLexer.Num(lexem) => lexem.value
      val Num = rule(
        { case Integer(i) => i },
        { case Float(f) => f },
      )
      val root = rule:
       case Num(n) => n

    given Resolutions[AmbiguousParser.type] = resolutions(
      P(CalcLexer.Num).before(P(AmbiguousParser.Integer)),
    )
    """).loneElement
    ambiguous.message should include("""Production with RHS 'Num' is ambiguous, it matches:
                                       |  Integer -> Num
                                       |  Float -> Num
                                       |""".stripMargin)
    ambiguous.lineContent.trim shouldBe "P(CalcLexer.Num).before(P(AmbiguousParser.Integer)),"
    ambiguous.column shouldBe 7 // the point of `P(...)` is its argument list
  }

  test("conflict resolution that is not a direct before/after call is reported at the resolution") {
    val indirect: scala.compiletime.testing.Error = typeCheckErrors("""
    object IndirectParser extends Parser[CalcContext]:
      val Expr: Rule[Int] = rule(
        { case (Expr(a), CalcLexer.`+`(_), Expr(b)) => a + b },
        { case CalcLexer.Num(lexem) => lexem.value },
      )
      val root = rule:
       case Expr(e) => e

    given Resolutions[IndirectParser.type] = resolutions(
      if true then CalcLexer.`+`.before(CalcLexer.Num) else CalcLexer.Num.before(CalcLexer.`+`),
    )
    """).loneElement
    indirect.message should include("Each conflict resolution must be a direct `x.before(...)` or `x.after(...)` call")
    indirect.lineContent.trim shouldBe
      "if true then CalcLexer.`+`.before(CalcLexer.Num) else CalcLexer.Num.before(CalcLexer.`+`),"
  }

  test("invalid SeparatedBy separator is reported at the separator") {
    val separator: scala.compiletime.testing.Error = typeCheckErrors("""
    object SeparatorParser extends Parser[CalcContext]:
      val Num = rule:
        case CalcLexer.Num(lexem) => lexem.value
      val root = rule:
        case Num.SeparatedBy[Int](items) => items
    """).loneElement
    separator.message should include("SeparatedBy separator must be a Token or Rule type, but got: scala.Int")
    separator.lineContent.trim shouldBe "case Num.SeparatedBy[Int](items) => items"
    separator.column shouldBe 29
  }
