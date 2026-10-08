package halotukozak
package alpaca.internal.parser

import halotukozak.alpaca.{lexer, parse, rule, Result, Rule, Token}
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

val DollarLexer = lexer:
  case "a" => Token["A"]
  case "\\$" => Token["$"]
  case "ε" => Token["ε"]

// An A is an X before the end of the input and a Y before a "$" token, so the two must stay apart.
object DollarParser extends Parser:
  val X: Rule[String] = rule { case DollarLexer.A(_) => "X" }
  val Y: Rule[String] = rule { case DollarLexer.A(_) => "Y" }
  val root: Rule[String] = rule(
    { case X(x) => x },
    { case (Y(y), DollarLexer.`$`(_)) => y + "$" },
    { case (DollarLexer.`ε`(_), root(r)) => "ε" + r },
  )

// A rule with the name the textbook gives the augmented start symbol.
object PrimeParser extends Parser:
  val `S'`: Rule[String] = rule { case HashLexer.X(_) => "x" }
  val root: Rule[String] = rule { case (`S'`(a), `S'`(b)) => a + b }

final class ReservedSymbolNamesTest extends AnyFunSuite with Matchers with LoneElement:

  test("a token named \"#\" takes part in LALR(1) lookahead propagation like any other token") {
    def parse(input: String) = HashParser.parse(HashLexer.tokenize(input).getOrThrow).getOrThrow

    parse("x#*x") shouldBe "x=*x"
    parse("*x#x") shouldBe "*x=x"
    parse("**x") shouldBe "**x"
  }

  test("tokens named \"$\" and \"ε\" are neither the end of the input nor the empty sequence") {
    def parse(input: String) = DollarParser.parse(DollarLexer.tokenize(input).getOrThrow).getOrThrow

    parse("a") shouldBe "X"
    parse("a$") shouldBe "Y$"
    parse("εεa$") shouldBe "εεY$"
  }

  test("a rule named \"S'\" is not the parser's start symbol") {
    PrimeParser.parse(HashLexer.tokenize("xx").getOrThrow).getOrThrow shouldBe "xx"
  }

  test("a ParserError tells a token named \"$\" from the end of the input") {
    def error(input: String) = DollarParser.parse(DollarLexer.tokenize(input).getOrThrow) match
      case Result.Failure(_, _, errors) => errors.loneElement
      case Result.Success(_, _) => fail("expected a failure")

    val tooLong = error("a$$")
    tooLong.unexpected.map(_.name: String) shouldBe Some("$")
    tooLong.expected shouldBe List("$")
    tooLong.message shouldBe "Unexpected $ \"$\". Expected one of: end of input"

    val either = error("aa")
    either.expected shouldBe List("$", "$")
    either.message should endWith(". Expected one of: end of input, end of input")

    error("").message shouldBe "Unexpected end of input. Expected one of: A, ε"
  }

  test("a conflict on a token named \"$\" shows the token in the situation, unlike the end of the input") {
    typeCheckErrors("""
      object SumParser extends Parser:
        val E: Rule[Int] = rule({ case (E(a), DollarLexer.`$`(_), E(b)) => a + b }, { case DollarLexer.A(_) => 1 })
        val root: Rule[Int] = rule { case E(e) => e }
      """).loneElement.message should include("""
                                                |Shift "$" vs Reduce E -> E $ E
                                                |In situation like:
                                                |E $ E $ ...
                                                |""".stripMargin)
  }

  test("an underscore token name is reported once and leaves the rest of the lexer typed") {
    typeCheckErrors("""
      val L = lexer:
        case "-" => Token["_"]
        case word @ "[a-z]+" => Token["WORD"](word)

      object P extends Parser:
        val root: Rule[String] = rule:
          case L.WORD(word) => word.value
      """).loneElement.message shouldBe "Invalid token name: _"
  }

  test("an empty token name is reported once and leaves the rest of the lexer typed") {
    typeCheckErrors("""
      val L = lexer:
        case "-" => Token[""]
        case word @ "[a-z]+" => Token["WORD"](word)

      object P extends Parser:
        val root: Rule[String] = rule:
          case L.WORD(word) => word.value
      """).loneElement.message shouldBe "Invalid token name: it is empty"
  }
