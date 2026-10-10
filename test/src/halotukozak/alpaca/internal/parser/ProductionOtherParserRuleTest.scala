package halotukozak
package alpaca.internal.parser

import halotukozak.alpaca.{before, lexer, resolutions, rule, Production, Resolutions, Rule, Token}
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.{typeCheckErrors, typeChecks}

val OtherRuleLexer = lexer:
  case "\\+" => Token["PLUS"]
  case n @ "[0-9]+" => Token["NUM"](n.toInt)
  case "\\s+" => Token.Ignored

object OtherRuleParser extends Parser:
  val Expr: Rule[Int] = rule(
    { case (Expr(a), OtherRuleLexer.PLUS(_), OtherRuleLexer.NUM(b)) => a + b.value },
    { case OtherRuleLexer.NUM(n) => n.value },
  )
  val root: Rule[Int] = rule { case Expr(e) => e }

final class ProductionOtherParserRuleTest extends AnyFunSuite with Matchers with LoneElement:

  test("a rule of another parser in Production(...) is reported, even when this parser has a rule of the same name") {
    val error = typeCheckErrors("""
    object SameRuleNameParser extends Parser:
      val Expr: Rule[Int] = rule(
        { case (Expr(a), OtherRuleLexer.PLUS(_), Expr(b)) => a + b },
        { case OtherRuleLexer.NUM(n) => n.value },
      )
      val root: Rule[Int] = rule { case Expr(e) => e }
    given Resolutions[SameRuleNameParser.type] = resolutions(
      Production(SameRuleNameParser.Expr, OtherRuleLexer.PLUS, OtherRuleParser.Expr).before(OtherRuleLexer.PLUS),
    )
    """).loneElement
    error.message shouldBe
      "Rule Expr belongs to another parser, OtherRuleParser; `Production(...)` in the resolutions of SameRuleNameParser can only refer to SameRuleNameParser's rules\n(at line 9: OtherRuleParser.Expr)"
    error.lineContent.trim shouldBe
      "Production(SameRuleNameParser.Expr, OtherRuleLexer.PLUS, OtherRuleParser.Expr).before(OtherRuleLexer.PLUS),"
    error.column shouldBe 79 // the `Expr` of `OtherRuleParser.Expr`
  }

  test("Production(...) with this parser's own rules still resolves") {
    assert(typeChecks("""
    object OwnRuleParser extends Parser:
      val Expr: Rule[Int] = rule(
        { case (Expr(a), OtherRuleLexer.PLUS(_), Expr(b)) => a + b },
        { case OtherRuleLexer.NUM(n) => n.value },
      )
      val root: Rule[Int] = rule { case Expr(e) => e }
    given Resolutions[OwnRuleParser.type] = resolutions(
      Production(OwnRuleParser.Expr, OtherRuleLexer.PLUS, OwnRuleParser.Expr).before(OtherRuleLexer.PLUS),
    )
    """))
  }

  test("a rule referred to through an alias in Production(...) is reported, naming this parser") {
    val error = typeCheckErrors("""
    object AliasedRuleParser extends Parser:
      val Expr: Rule[Int] = rule(
        { case (Expr(a), OtherRuleLexer.PLUS(_), Expr(b)) => a + b },
        { case OtherRuleLexer.NUM(n) => n.value },
      )
      val root: Rule[Int] = rule { case Expr(e) => e }
    val someRule: Rule[Int] = AliasedRuleParser.Expr
    given Resolutions[AliasedRuleParser.type] = resolutions(
      Production(someRule, OtherRuleLexer.PLUS, AliasedRuleParser.Expr).before(OtherRuleLexer.PLUS),
    )
    """).loneElement
    error.message shouldBe
      "someRule is not declared in a parser; `Production(...)` in the resolutions of AliasedRuleParser can only refer to AliasedRuleParser's rules directly, as in `Production(AliasedRuleParser.<rule>, ...)`\n(at line 10: someRule)"
    error.lineContent.trim shouldBe
      "Production(someRule, OtherRuleLexer.PLUS, AliasedRuleParser.Expr).before(OtherRuleLexer.PLUS),"
    error.column shouldBe 17 // the `someRule` argument
  }
