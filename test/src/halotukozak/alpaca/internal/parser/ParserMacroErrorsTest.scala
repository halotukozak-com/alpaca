package halotukozak
package alpaca.internal.parser

import halotukozak.alpaca.{lexer, rule, Rule, Token}
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.{typeCheckErrors, Error}

val ErrLexer = lexer:
  case "\\+" => Token["PLUS"]
  case "\\*" => Token["TIMES"]
  case n @ "[0-9]+" => Token["NUM"](n.toInt)
  case "\\s+" => Token.Ignored

final class ParserMacroErrorsTest extends AnyFunSuite with Matchers with LoneElement:

  private val NotASymbol =
    "Each element of a production's pattern must be a token or rule extractor, as in `MyLexer.NUM(n)`, `MyLexer.PLUS(_)`, `Expr(e)` or `Expr.List(es)`"

  test("a bare `_` in a production is reported at it, not as a macro crash") {
    val error: Error = typeCheckErrors("""
    object WildcardParser extends Parser:
      val root: Rule[Int] = rule({ case (ErrLexer.NUM(n), _) => n.value })
    """).loneElement
    error.message shouldBe NotASymbol
    error.lineContent.trim shouldBe "val root: Rule[Int] = rule({ case (ErrLexer.NUM(n), _) => n.value })"
    error.column shouldBe 58
  }

  test("a literal in a production is reported at it, not as a macro crash") {
    typeCheckErrors("""
    object LiteralParser extends Parser:
      val root: Rule[Int] = rule({ case (ErrLexer.NUM(n), 1) => n.value })
    """).loneElement.message shouldBe NotASymbol
  }

  test("a nested tuple in a production is reported at it, not as a macro crash") {
    typeCheckErrors("""
    object NestedParser extends Parser:
      val root: Rule[Int] = rule({ case (ErrLexer.NUM(n), (ErrLexer.PLUS(_), ErrLexer.NUM(m))) => n.value })
    """).loneElement.message shouldBe NotASymbol
  }

  test("every unsupported element of a production is reported, without follow-up errors") {
    typeCheckErrors("""
    object TwoBadParser extends Parser:
      val root: Rule[Int] = rule({ case (_, ErrLexer.NUM(n), 1) => n.value })
    """).map(_.message) shouldBe List(NotASymbol, NotASymbol)
  }

  test("a production matching a bare binding is reported at its pattern, not as a macro crash") {
    typeCheckErrors("""
    object BindParser extends Parser:
      val root: Rule[Int] = rule({ case x => 1 })
    """).loneElement.message shouldBe
      "A production must match a token or rule extractor, or a tuple of them, as in `case (Expr(a), MyLexer.PLUS(_), Expr(b))`"
  }

  test("a guard in a production does not also report a missing root rule") {
    typeCheckErrors("""
    object GuardParser extends Parser:
      val root: Rule[Int] = rule({ case ErrLexer.NUM(n) if n.value > 0 => n.value })
    """).loneElement.message shouldBe "Guards are not supported yet"
  }
