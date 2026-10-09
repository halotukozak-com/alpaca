package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class NullableSymbolTest extends AnyFunSuite with Matchers with LoneElement:

  val AbcLexer = lexer:
    case "a" => Token["a"]
    case "b" => Token["b"]
    case "c" => Token["c"]
    case "\\s+" => Token.Ignored

  object OptionInMiddleParser extends Parser:
    val A: Rule[String] = rule { case AbcLexer.a(_) => "A" }
    val root: Rule[String] = rule { case (A(a), AbcLexer.b.Option(b), AbcLexer.c(_)) => a + b.isDefined }

  object ListInMiddleParser extends Parser:
    val A: Rule[String] = rule { case AbcLexer.a(_) => "A" }
    val root: Rule[String] = rule { case (A(a), AbcLexer.b.List(bs), AbcLexer.c(_)) => a + bs.size }

  // a user rule can only derive the empty input through a nullable symbol
  object NullableRuleInMiddleParser extends Parser:
    val A: Rule[String] = rule { case AbcLexer.a(_) => "A" }
    val B: Rule[Int] = rule { case AbcLexer.b.Option(b) => b.size }
    val root: Rule[String] = rule { case (A(a), B(b), AbcLexer.c(_)) => a + b }

  object NullablesInARowParser extends Parser:
    val A: Rule[String] = rule { case AbcLexer.a(_) => "A" }
    val root: Rule[String] = rule:
      case (A(a), AbcLexer.b.Option(b), AbcLexer.c.List(cs), AbcLexer.a(_)) => a + b.isDefined + cs.size

  object OptionAtEndParser extends Parser:
    val A: Rule[String] = rule { case AbcLexer.a(_) => "A" }
    val root: Rule[String] = rule { case (A(a), AbcLexer.b.Option(b)) => a + b.isDefined }

  private def tokens(input: String) = AbcLexer.tokenize(input).getOrThrow

  test("a nullable symbol in the middle of a production can be skipped") {
    OptionInMiddleParser.parse(tokens("a b c")).toOption shouldBe Some("Atrue")
    OptionInMiddleParser.parse(tokens("a c")).toOption shouldBe Some("Afalse")
    ListInMiddleParser.parse(tokens("a b b c")).toOption shouldBe Some("A2")
    ListInMiddleParser.parse(tokens("a c")).toOption shouldBe Some("A0")
    NullableRuleInMiddleParser.parse(tokens("a b c")).toOption shouldBe Some("A1")
    NullableRuleInMiddleParser.parse(tokens("a c")).toOption shouldBe Some("A0")
  }

  test("consecutive nullable symbols can all be skipped") {
    NullablesInARowParser.parse(tokens("a b c c a")).toOption shouldBe Some("Atrue2")
    NullablesInARowParser.parse(tokens("a a")).toOption shouldBe Some("Afalse0")
  }

  test("a nullable symbol at the end of a production can be skipped") {
    OptionAtEndParser.parse(tokens("a b")).toOption shouldBe Some("Atrue")
    OptionAtEndParser.parse(tokens("a")).toOption shouldBe Some("Afalse")
  }

  test("the terminals after a nullable symbol are expected") {
    OptionInMiddleParser.parse(tokens("a")).toEither.left.map(_.loneElement.expected) shouldBe Left(List("b", "c"))
    OptionAtEndParser.parse(tokens("a a")).toEither.left.map(_.loneElement.expected) shouldBe
      Left(List[String | ParserError.EndOfInput](ParserError.EndOfInput, "b"))
  }
