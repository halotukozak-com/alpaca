package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.testing.{typeCheckErrors, typeChecks}

// Compiled outside the `alpaca` package, as user code is: under -Werror any warning the macros' expansions produce
// here (such as the inaccessible `given Tables` these parsers used to pick up) fails the build.
val WordLexer = lexer:
  case w @ "[a-zżółw]+" => Token["WORD"](w)
  case "\\s+" => Token.Ignored

object WordsParser extends Parser:
  val Word: Rule[String] = rule { case WordLexer.WORD(w) => w.value }
  val root: Rule[List[String]] = rule { case Word.List(words) => words }

final case class CollectingCtx(seen: mutable.ListBuffer[String] = mutable.ListBuffer.empty) extends ParserCtx

object CollectingParser extends Parser[CollectingCtx]:
  val Word: Rule[String] = rule:
    case WordLexer.WORD(w) =>
      ctx.seen.append(w.value)
      w.value
  val root: Rule[Int] = rule { case Word.List(words) => words.size }

final class PublicApiTest extends AnyFunSuite with Matchers:

  test("an empty input parses when the root rule accepts no tokens") {
    WordsParser.parse(Nil) shouldBe Result.Success(ParserCtx.Empty(), Nil)
  }

  test("a lexer error points at a whole code point") {
    WordLexer.tokenize("ab 😀").toEither.left.map(_.map(_.message)) shouldBe
      Left(List("Unexpected character '😀' at line 1, column 4"))
  }

  test("every parse starts from a fresh context, even one with mutable fields") {
    def parse(input: String) = CollectingParser.parse(WordLexer.tokenize(input).getOrThrow)

    parse("a b c").ctx.seen shouldBe List("a", "b", "c")
    parse("d").ctx.seen shouldBe List("d")
  }

  // Known API issues: each check fails today, and `pendingUntilFixed` fails the test once it passes.
  private def knownIssue(check: => Any) = pendingUntilFixed(check: Unit)

  test("KNOWN ISSUE: the Token type users import is the type of a lexer's tokens") {
    knownIssue:
      assert(typeChecks("""val token: Token["WORD", LexerCtx.Default, String] = WordLexer.WORD"""))
  }

  test("KNOWN ISSUE: the lexer's internal bookkeeping cannot be overwritten from user code") {
    knownIssue:
      assert(!typeChecks("""LexerCtx.Default().lastRawMatched = "x""""))
  }

  test("KNOWN ISSUE: the DSL's marker types cannot be instantiated from user code") {
    knownIssue:
      assert(!typeChecks("""new Token["A", LexerCtx.Default, Int]"""))
      assert(!typeChecks("""new IgnoredToken[LexerCtx.Default]"""))
      assert(!typeChecks("""new Rule[Int] {}"""))
  }

  test("KNOWN ISSUE: a lexer's type can be named") {
    knownIssue:
      assert(typeChecks("""def lexemes(lexer: Tokenization[LexerCtx.Default]) = lexer.tokenize("")"""))
  }

  test("KNOWN ISSUE: a production name that is not a string literal is reported") {
    knownIssue:
      assert(typeCheckErrors("""
      val name = "plus"
      object NamedParser extends Parser:
        val root: Rule[Int] = rule(name { case WordLexer.WORD(_) => 1 })
      """).nonEmpty)
  }

  test("KNOWN ISSUE: the remaining text handed to ErrorHandling keeps its content after the callback") {
    var seen: CharSequence | Null = null
    case class RecordingCtx(n: Int = 0) extends LexerCtx
    given ErrorHandling[RecordingCtx, LexerError] = (ctx, _) =>
      if seen == null then seen = ctx.remainingText
      ErrorHandling.Strategy.SkipOne
    val Lexer = lexer[RecordingCtx]:
      case "a" => Token["A"]

    Lexer.tokenize("a!aa!").toEither.left.map(_.size) shouldBe Left(2)
    knownIssue:
      seen.nn.toString shouldBe "!aa!"
  }
