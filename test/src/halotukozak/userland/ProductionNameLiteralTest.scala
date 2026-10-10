package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.typeCheckErrors

val NameLiteralLexer = lexer:
  case "\\+" => Token["PLUS"]
  case "-" => Token["MINUS"]
  case n @ "[0-9]+" => Token["NUM"](n.toInt)
  case "\\s+" => Token.Ignored

final class ProductionNameLiteralTest extends AnyFunSuite with Matchers with LoneElement:

  test("a production name that is not a string literal is reported at it") {
    val error = typeCheckErrors("""
    object NonLiteralNameParser extends Parser:
      val name = "plus"
      val root: Rule[Int] = rule(
        name { case (root(a), NameLiteralLexer.PLUS(_), root(b)) => a + b },
        { case NameLiteralLexer.NUM(n) => n.value },
      )
    """).loneElement
    error.message shouldBe
      "A production name must be a string literal, as in `\"plus\" { case ... }`\n(at line 5: name)"
    error.lineContent.trim shouldBe "name { case (root(a), NameLiteralLexer.PLUS(_), root(b)) => a + b },"
    error.column shouldBe 8
  }

  test("a production named like an encoded Scala name is referred to by that name") {
    typeCheckErrors("""
    object DollarNameParser extends Parser:
      val Expr: Rule[Int] = rule(
        "$minus" { case (Expr(a), NameLiteralLexer.MINUS(_), Expr(b)) => a - b },
        "a$u0020b" { case (Expr(a), NameLiteralLexer.PLUS(_), Expr(b)) => a + b },
        { case NameLiteralLexer.NUM(n) => n.value },
      )
      val root: Rule[Int] = rule { case Expr(e) => e }
    given Resolutions[DollarNameParser.type] = resolutions(
      production.$minus.before(NameLiteralLexer.MINUS, NameLiteralLexer.PLUS),
      production.a$u0020b.before(NameLiteralLexer.MINUS, NameLiteralLexer.PLUS),
    )
    """) shouldBe empty
  }

  test("a production named with symbols is referred to in backticks") {
    typeCheckErrors("""
    object SymbolNameParser extends Parser:
      val Expr: Rule[Int] = rule(
        "-" { case (Expr(a), NameLiteralLexer.MINUS(_), Expr(b)) => a - b },
        "a b" { case (Expr(a), NameLiteralLexer.PLUS(_), Expr(b)) => a + b },
        { case NameLiteralLexer.NUM(n) => n.value },
      )
      val root: Rule[Int] = rule { case Expr(e) => e }
    given Resolutions[SymbolNameParser.type] = resolutions(
      production.`-`.before(NameLiteralLexer.MINUS, NameLiteralLexer.PLUS),
      production.`a b`.before(NameLiteralLexer.MINUS, NameLiteralLexer.PLUS),
    )
    """) shouldBe empty
  }

  test("production names that encode to the same Scala name are reported where they are referred to") {
    val error = typeCheckErrors("""
    object SameEncodingParser extends Parser:
      val Expr: Rule[Int] = rule(
        "-" { case (Expr(a), NameLiteralLexer.MINUS(_), Expr(b)) => a - b },
        "$minus" { case (Expr(a), NameLiteralLexer.PLUS(_), Expr(b)) => a + b },
        { case NameLiteralLexer.NUM(n) => n.value },
      )
      val root: Rule[Int] = rule { case Expr(e) => e }
    given Resolutions[SameEncodingParser.type] = resolutions(
      production.`-`.before(NameLiteralLexer.MINUS, NameLiteralLexer.PLUS),
    )
    """).loneElement
    error.message shouldBe
      "Production names '-', '$minus' are the same Scala name, so `production.<name>` cannot tell them apart; rename all but one\n(at line 10: production.`-`)"
  }
