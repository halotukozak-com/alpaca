package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.{typeCheckErrors, Error}

final case class AssigningCtx(count: Int = 0, other: Int = 0) extends LexerCtx

final class LexerMacroErrorsTest extends AnyFunSuite with Matchers with LoneElement:

  private val NotAToken =
    "A lexer rule must end with `Token[\"NAME\"]`, `Token[\"NAME\"](value)` or `Token.Ignored`, written directly as its last expression"
  private val NotARegex =
    "A lexer rule must match a regex string literal or alternatives of them, as in `case \"[0-9]+\"` or `case \"a\" | \"b\"`"
  private val NotALiteral = "Each alternative of a lexer rule must be a regex string literal"
  private val NotACaseList =
    "Lexer rules must be a list of `case`s written directly in the `lexer` block, as in `case \"[0-9]+\" => Token[\"NUM\"]`"
  private val NotAName =
    "A token name must be a string literal, as in `Token[\"NAME\"]`, or the type of the bound match, as in `case x @ \"regex\" => Token[x.type]`"

  test("a lexer rule ending in an `if` is reported at the rule's body") {
    val error: Error = typeCheckErrors("""
    lexer:
      case "a" => if true then Token["A"] else Token["B"]
    """).loneElement
    error.message shouldBe NotAToken
    error.lineContent.trim shouldBe "case \"a\" => if true then Token[\"A\"] else Token[\"B\"]"
  }

  test("a lexer rule returning a token through a local val is reported at the rule's body") {
    typeCheckErrors("""
    lexer:
      case "a" =>
        val token = Token["A"]
        token
    """).loneElement.message shouldBe NotAToken
  }

  test("a wildcard lexer pattern is reported at the pattern") {
    val error: Error = typeCheckErrors("""
    lexer:
      case "a" => Token["A"]
      case _ => Token["B"]
    """).loneElement
    error.message shouldBe NotARegex
    error.lineContent.trim shouldBe "case _ => Token[\"B\"]"
  }

  test("a typed lexer pattern is reported at the pattern") {
    typeCheckErrors("""
    lexer:
      case s: String => Token["A"]
    """).loneElement.message shouldBe NotARegex
  }

  test("a token named by a non-literal type is reported at its case") {
    val error: Error = typeCheckErrors("""
    val name: String = "A"
    lexer:
      case "a" => Token[name.type]
    """).loneElement
    error.message shouldBe NotAName
    error.lineContent.trim shouldBe "case \"a\" => Token[name.type]"
  }

  test("a rejected case keeps the lexer typed from its other cases") {
    typeCheckErrors("""
    val L = lexer:
      case name @ "[a-z]+" => Token["IDENT"](name)
      case _ => Token["OTHER"]

    object P extends Parser:
      val root: Rule[String] = rule:
        case L.IDENT(name) => name.value
    """).loneElement.message shouldBe NotARegex
  }

  test("a rule with a non-literal alternative contributes none of its alternatives") {
    val error: Error = typeCheckErrors("""
    val L = lexer:
      case x @ ("a" | _) => Token[x.type]
      case "a" => Token["a"]
      case name @ "[b-z]+" => Token["IDENT"](name)

    object P extends Parser:
      val root: Rule[String] = rule:
        case L.IDENT(name) => name.value
    """).loneElement
    error.message shouldBe NotALiteral
    error.lineContent.trim shouldBe "case x @ (\"a\" | _) => Token[x.type]"
  }

  test("an assignment nested in another one inside a token's value is reported too") {
    val errors = typeCheckErrors("""
    lexer[AssigningCtx]:
      case "a" =>
        Token["A"]({
          ctx.count = {
            ctx.other = 1
            2
          }
          ctx.count
        })
    """)
    errors.map(_.message).distinct shouldBe List("Assign context fields before `Token[...]`, not inside its value")
    errors.map(_.lineContent.trim) should contain theSameElementsAs List("ctx.count = {", "ctx.other = 1")
  }

  test("assigning a context field inside a token's value is reported at the assignment") {
    val error: Error = typeCheckErrors("""
    lexer[AssigningCtx]:
      case "a" =>
        Token["A"]({
          ctx.count += 1
          ctx.count
        })
    """).loneElement
    error.message shouldBe "Assign context fields before `Token[...]`, not inside its value"
    error.lineContent.trim shouldBe "ctx.count += 1"
  }

  test("an empty partial function as the lexer rules is reported at the argument") {
    val error: Error = typeCheckErrors("""
    lexer(PartialFunction.empty)
    """).loneElement
    error.message shouldBe NotACaseList
    error.lineContent.trim shouldBe "lexer(PartialFunction.empty)"
  }

  test("lexer rules defined elsewhere are reported at the argument") {
    val error: Error = typeCheckErrors("""
    def rules(using LexerScope.Of[LexerCtx.Default]): LexerDefinition[LexerCtx.Default] =
      case "a" => Token["A"]
    lexer(rules)
    """).loneElement
    error.message shouldBe NotACaseList
    error.lineContent.trim shouldBe "lexer(rules)"
  }

  test("lexer rules combined with orElse are reported at the argument") {
    typeCheckErrors("""
    val first: LexerScope.Of[LexerCtx.Default] ?=> LexerDefinition[LexerCtx.Default] = { case "a" => Token["A"] }
    val second: LexerScope.Of[LexerCtx.Default] ?=> LexerDefinition[LexerCtx.Default] = { case "b" => Token["B"] }
    lexer(first.orElse(second))
    """).loneElement.message shouldBe NotACaseList
  }
