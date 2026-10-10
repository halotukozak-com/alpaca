package halotukozak
package alpaca.internal.parser

import halotukozak.alpaca.{apply, before, lexer, production, resolutions, rule, Resolutions, Rule, Token}
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.typeCheckErrors

val DuplicateNameLexer = lexer:
  case "\\+" => Token["PLUS"]
  case "\\*" => Token["TIMES"]
  case n @ "[0-9]+" => Token["NUM"](n.toInt)
  case "\\s+" => Token.Ignored

final class ProductionNameDuplicatesTest extends AnyFunSuite with Matchers with LoneElement:

  test("a production name used twice is reported at the second production, even when nothing refers to it") {
    val error = typeCheckErrors("""
    object UnreferencedDuplicateParser extends Parser:
      val Num: Rule[Int] = rule("num" { case DuplicateNameLexer.NUM(n) => n.value })
      val root: Rule[Int] = rule(
        { case Num(n) => n },
        "num" { case (DuplicateNameLexer.PLUS(_), Num(n)) => n },
      )
    """).loneElement
    error.message shouldBe
      "Production name 'num' is already used by Num -> NUM (num); give each production its own name\n(at line 6: case (DuplicateNameLexer.PLUS(_), Num(n)) => n)"
    error.lineContent.trim shouldBe """"num" { case (DuplicateNameLexer.PLUS(_), Num(n)) => n },"""
  }

  test("a production name used twice is reported once, not also as an ambiguous reference") {
    typeCheckErrors("""
    object ReferencedDuplicateParser extends Parser:
      val root: Rule[Int] = rule(
        "op" { case (root(a), DuplicateNameLexer.PLUS(_), root(b)) => a + b },
        "op" { case (root(a), DuplicateNameLexer.TIMES(_), root(b)) => a * b },
        { case DuplicateNameLexer.NUM(n) => n.value },
      )
    given Resolutions[ReferencedDuplicateParser.type] =
      resolutions(production.op.before(DuplicateNameLexer.PLUS, DuplicateNameLexer.TIMES))
    """).loneElement.message shouldBe
      "Production name 'op' is already used by root -> root PLUS root (op); give each production its own name\n(at line 5: case (root(a), DuplicateNameLexer.TIMES(_), root(b)) => a * b)"
  }

  test("a production defined twice is reported at the second definition") {
    val error = typeCheckErrors("""
    object DuplicateProductionParser extends Parser:
      val root: Rule[Int] = rule(
        { case DuplicateNameLexer.NUM(_) => 1 },
        { case DuplicateNameLexer.NUM(_) => 2 },
      )
    """).loneElement
    error.message shouldBe "Production root -> NUM is already defined at line 4\n(at line 5: case DuplicateNameLexer.NUM(_) => 2)"
    error.lineContent.trim shouldBe "{ case DuplicateNameLexer.NUM(_) => 2 },"
  }

  test("duplicate productions and production names are reported in source order") {
    // typeCheckErrors lists the errors latest first
    val errors = typeCheckErrors("""
    object ManyDuplicatesParser extends Parser:
      val root: Rule[Int] = rule(
        "z" { case DuplicateNameLexer.NUM(_) => 1 },
        "a" { case DuplicateNameLexer.PLUS(_) => 2 },
        { case DuplicateNameLexer.TIMES(_) => 3 },
        { case (DuplicateNameLexer.NUM(_), DuplicateNameLexer.NUM(_)) => 4 },
        "z" { case (DuplicateNameLexer.PLUS(_), DuplicateNameLexer.NUM(_)) => 5 },
        { case (DuplicateNameLexer.NUM(_), DuplicateNameLexer.NUM(_)) => 6 },
        "a" { case (DuplicateNameLexer.TIMES(_), DuplicateNameLexer.NUM(_)) => 7 },
        { case DuplicateNameLexer.TIMES(_) => 8 },
      )
    """).reverse
    errors.map(_.message.linesIterator.next()) shouldBe List(
      "Production root -> TIMES is already defined at line 6",
      "Production root -> NUM NUM is already defined at line 7",
      "Production name 'z' is already used by root -> NUM (z); give each production its own name",
      "Production name 'a' is already used by root -> PLUS (a); give each production its own name",
    )
  }
