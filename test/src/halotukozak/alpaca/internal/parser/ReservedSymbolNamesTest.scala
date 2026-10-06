package halotukozak
package alpaca.internal.parser

import halotukozak.alpaca.{lexer, parse, rule, Rule, Token}
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.typeCheckErrors

val HashLexer = lexer:
  case "#" => Token["#"]
  case "x" => Token["X"]
  case "\\*" => Token["STAR"]

// The classic grammar that needs LALR(1) lookahead propagation, with "#" for "=":
// S -> L # R | R ; L -> * R | x ; R -> L
object HashParser extends Parser:
  val L: Rule[String] = rule({ case (HashLexer.STAR(_), R(r)) => "*" + r }, { case HashLexer.X(_) => "x" })
  val R: Rule[String] = rule { case L(l) => l }
  val root: Rule[String] = rule({ case (L(l), HashLexer.`#`(_), R(r)) => l + "=" + r }, { case R(r) => r })

final class ReservedSymbolNamesTest extends AnyFunSuite with Matchers with LoneElement:

  test("a token named \"#\" takes part in LALR(1) lookahead propagation like any other token") {
    def parse(input: String) = HashParser.parse(HashLexer.tokenize(input).getOrThrow).getOrThrow

    parse("x#*x") shouldBe "x=*x"
    parse("*x#x") shouldBe "*x=x"
    parse("**x") shouldBe "**x"
  }

  test("token names the parser reserves are rejected") {
    val reserved = "is reserved: the parser uses \"$\" for the end of the input and \"ε\" for the empty sequence"
    typeCheckErrors("""lexer { case "a" => Token["$"] }""").loneElement.message shouldBe s"Token name \"$$\" $reserved"
    typeCheckErrors("""lexer { case "a" => Token["ε"] }""").loneElement.message shouldBe s"Token name \"ε\" $reserved"
  }

  test("an empty token name is rejected") {
    typeCheckErrors("""lexer { case "a" => Token[""] }""").loneElement.message shouldBe
      "Invalid token name: it is empty"
  }
