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
