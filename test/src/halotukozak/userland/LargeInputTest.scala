package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

// Shared sources, so every input here also runs on Scala.js and Scala Native: the lexer and the parser must not
// recurse per token or per nesting level.
val LargeInputLexer = lexer:
  case "\\(" => Token["LPAREN"]
  case "\\)" => Token["RPAREN"]
  case "," => Token["COMMA"]
  case number @ "[0-9]+" => Token["NUM"](number.toInt)
  case word @ "[a-z]+" => Token["WORD"](word)
  case "\\s+" => Token.Ignored

object NestingParser extends Parser:
  val Expr: Rule[Int] = rule(
    { case (LargeInputLexer.LPAREN(_), Expr(depth), LargeInputLexer.RPAREN(_)) => depth + 1 },
    { case LargeInputLexer.NUM(_) => 0 },
  )
  val root: Rule[Int] = rule { case Expr(depth) => depth }

object RightRecursionParser extends Parser:
  val Numbers: Rule[List[Int]] = rule(
    { case (LargeInputLexer.NUM(number), Numbers(rest)) => number.value :: rest },
    { case LargeInputLexer.NUM(number) => number.value :: Nil },
  )
  val root: Rule[List[Int]] = rule { case Numbers(numbers) => numbers }

object NumberListParser extends Parser:
  val root: Rule[List[Int]] = rule { case LargeInputLexer.NUM.List(numbers) => numbers.map(_.value) }

object SeparatedNumbersParser extends Parser:
  val root: Rule[Int] = rule:
    case LargeInputLexer.NUM.SeparatedBy[LargeInputLexer.COMMA](items) => items.size

object WordParser extends Parser:
  val root: Rule[String] = rule { case LargeInputLexer.WORD(word) => word.value }

final class LargeInputTest extends AnyFunSuite with Matchers:
  private val count = 100_000

  test("100k nested parentheses parse") {
    val input = "(" * count + "1" + ")" * count
    NestingParser.parse(LargeInputLexer.tokenize(input).getOrThrow) shouldBe Result.Success(ParserCtx.Empty(), count)
  }

  test("a 100k-long right recursion parses") {
    val input = Iterator.tabulate(count)(_.toString).mkString(" ")
    RightRecursionParser.parse(LargeInputLexer.tokenize(input).getOrThrow) shouldBe
      Result.Success(ParserCtx.Empty(), List.range(0, count))
  }

  test("a List of 100k items parses") {
    val input = Iterator.tabulate(count)(_.toString).mkString(" ")
    NumberListParser.parse(LargeInputLexer.tokenize(input).getOrThrow) shouldBe
      Result.Success(ParserCtx.Empty(), List.range(0, count))
  }

  test("a SeparatedBy of 100k items parses") {
    val input = Iterator.fill(count)("1").mkString(",")
    SeparatedNumbersParser.parse(LargeInputLexer.tokenize(input).getOrThrow) shouldBe
      Result.Success(ParserCtx.Empty(), 2 * count - 1)
  }

  test("a single 1 MB token is one lexeme") {
    val word = "a" * (1024 * 1024)
    val lexemes = LargeInputLexer.tokenize(word).getOrThrow
    lexemes.map(lexeme => (lexeme.name, lexeme.text.length)) shouldBe List(("WORD", word.length))
    WordParser.parse(lexemes) shouldBe Result.Success(ParserCtx.Empty(), word)
  }
