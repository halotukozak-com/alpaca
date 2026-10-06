package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.{typeCheckErrors, Error}

final class LexerMacroErrorsTest extends AnyFunSuite with Matchers with LoneElement:

  private val NotAToken =
    "A lexer rule must end with `Token[\"NAME\"]`, `Token[\"NAME\"](value)` or `Token.Ignored`, written directly as its last expression"
  private val NotARegex =
    "A lexer rule must match a regex string literal or alternatives of them, as in `case \"[0-9]+\"` or `case \"a\" | \"b\"`"
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
