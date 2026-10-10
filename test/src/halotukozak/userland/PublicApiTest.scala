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

final case class MyCtx() extends LexerCtx

val MinusLexer = lexer:
  case "-" => Token["MINUS"]
  case number @ "[0-9]+" => Token["NUM"](number.toInt)
  case "\\s+" => Token.Ignored

// resolutions given as the parser object's last member: left- and right-associative minus
object LeftMinusParser extends Parser:
  val Expr: Rule[Int] = rule(
    "minus" { case (Expr(left), MinusLexer.MINUS(_), Expr(right)) => left - right },
    { case MinusLexer.NUM(number) => number.value },
  )
  val root: Rule[Int] = rule { case Expr(value) => value }
  given Resolutions[LeftMinusParser.type] = resolutions(production.minus.before(MinusLexer.MINUS))

object RightMinusParser extends Parser:
  val Expr: Rule[Int] = rule(
    "minus" { case (Expr(left), MinusLexer.MINUS(_), Expr(right)) => left - right },
    { case MinusLexer.NUM(number) => number.value },
  )
  val root: Rule[Int] = rule { case Expr(value) => value }
  given Resolutions[RightMinusParser.type] = resolutions(production.minus.after(MinusLexer.MINUS))

final class PublicApiTest extends AnyFunSuite with Matchers:

  test("an empty input parses when the root rule accepts no tokens") {
    WordsParser.parse(Nil) shouldBe Result.Success(ParserCtx.Empty(), Nil)
  }

  test("a lexer error points at a whole code point") {
    WordLexer.tokenize("ab 😀").toEither.left.map(_.map(e => (e.message, e.column))) shouldBe
      Left(List(("Unexpected character '😀'", 4)))
  }

  test("every parse starts from a fresh context, even one with mutable fields") {
    def parse(input: String) = CollectingParser.parse(WordLexer.tokenize(input).getOrThrow)

    parse("a b c").ctx.seen shouldBe List("a", "b", "c")
    parse("d").ctx.seen shouldBe List("d")
  }

  test("a lexer's token is named by its path") {
    assert(typeChecks("""val token: WordLexer.WORD = WordLexer.WORD"""))
  }

  test("the lexer's internal bookkeeping cannot be overwritten from user code") {
    assert(!typeChecks("""LexerCtx.Default().lastRawMatched = "x""""))
  }

  test("ctx and its field assignments are compile errors outside a lexer rule") {
    case class CountingCtx(count: Int = 0) extends LexerCtx
    typeCheckErrors("""
      given CountingCtx = CountingCtx()
      ctx.count
    """).map(_.message) shouldBe List("`ctx` and `Token` can only be used inside a lexer rule")
    typeCheckErrors("""
      def bump(using CountingCtx) = ctx.count += 1
    """).map(_.message) shouldBe List("`ctx` and `Token` can only be used inside a lexer rule")
    typeCheckErrors("""
      CountingCtx().applyDynamic("count_=")(1)
    """).map(_.message) shouldBe List("Lexer context fields can only be assigned inside a lexer rule")
  }

  test("Token is a compile error outside a lexer rule") {
    typeCheckErrors("""Token["X"]""").map(_.message) shouldBe
      List("`ctx` and `Token` can only be used inside a lexer rule")
    assert(!typeChecks("""val scope: LexerScope.Of[LexerCtx.Default] = LexerCtx.Default()"""))
    assert(!typeChecks("""LexerScope.refl(LexerCtx.Default())"""))
  }

  test("Token carries the lexer's context type, and only a lexer rule provides it") {
    typeCheckErrors("""
      lexer[MyCtx]:
        case "x" =>
          val _: Token["X", MyCtx, Unit] = Token["X"]
          Token["X"]
    """).map(_.message) shouldBe Nil
    assert(!typeChecks("""
      lexer[MyCtx]:
        case "x" =>
          val _: Token["X", LexerCtx.Empty, Unit] = Token["X"]
          Token["X"]
    """))
    assert(!typeChecks("""
      object Fake { type Ctx = MyCtx }
      given Fake.type = Fake
      Token["X"]
    """))
  }

  test("the lexer DSL's marker types cannot be created or extended from user code") {
    assert(!typeChecks("""new Token["A", LexerCtx.Default, Int]"""))
    assert(!typeChecks("""new IgnoredToken[LexerCtx.Default]"""))
    assert(!typeChecks("""class MyToken extends Token["A", LexerCtx.Default, Int]"""))
  }

  test("a Rule cannot be instantiated from user code") {
    assert(!typeChecks("""new Rule[Int] {}"""))
  }

  test("a lexer's type can be named") {
    def lexemes(lexer: Lexer[LexerCtx.Default]) = lexer.tokenize("ab c").getOrThrow
    lexemes(WordLexer).map(_.text) shouldBe List("ab", "c")
  }

  test("a lexeme's type can be named") {
    def texts(lexemes: List[Lexeme[?, ?]]) = lexemes.map(l => (l.name, l.text))
    texts(WordLexer.tokenize("ab c").getOrThrow) shouldBe List(("WORD", "ab"), ("WORD", "c"))
  }

  test("lexers and lexemes cannot be constructed from user code") {
    def inaccessible(errors: List[scala.compiletime.testing.Error]) =
      errors.map(_.message) should matchPattern { case List(m: String) if m.contains("cannot be accessed") => }

    inaccessible(typeCheckErrors("""new Lexeme["A", Int]("A", 1, "a", Array.empty[String], Array.empty[Any])"""))
    inaccessible(typeCheckErrors("""abstract class MyLexer extends Lexer[LexerCtx.Default]((_, _, ctx) => ctx)"""))
    assert(!typeChecks("""Lexeme.EOF"""))
  }

  test("a production name that is not a string literal is reported") {
    typeCheckErrors("""
    val name = "plus"
    object NamedParser extends Parser:
      val root: Rule[Int] = rule(name { case WordLexer.WORD(_) => 1 })
    """).map(_.message) shouldBe List(
      "A production name must be a string literal, as in `\"plus\" { case ... }`\n(at line 4: name)",
    )
  }

  private val outsideParser =
    "`rule`, named productions, and token and rule extractors can only be used inside a parser definition"
  private val outsideResolutions =
    "`production`, `Production(...)`, `before` and `after` can only be used inside resolutions(...)"

  // the compiler appends import suggestions, and a failed extractor is also reported as malformed
  inline private def errors(inline code: String) = typeCheckErrors(code).map(_.message.linesIterator.next())

  test("the parser DSL outside a parser is reported") {
    errors("""val r: Rule[Int] = rule { case WordLexer.WORD(_) => 1 }""") should contain(outsideParser)
    errors("""(null: Any) match { case WordLexer.WORD(_) => () }""") should contain(outsideParser)
  }

  test("the resolutions DSL outside resolutions is reported") {
    errors("""val p = Production(WordLexer.WORD)""") shouldBe List(outsideResolutions)
  }

  test("the resolutions DSL's types can be named but not created from user code") {
    assert(typeChecks("""def resolve(selector: ProductionSelector, production: Production): Unit = ()"""))
    assert(!typeChecks("""val p: Production = "plus""""))
    assert(!typeChecks("""new ProductionSelector { def selectDynamic(name: String): Any = null }"""))
  }

  test("resolutions given as the parser's last member resolve its conflicts") {
    val lexemes = MinusLexer.tokenize("5 - 2 - 1").getOrThrow
    LeftMinusParser.parse(lexemes) shouldBe Result.Success(ParserCtx.Empty(), 2)
    RightMinusParser.parse(lexemes) shouldBe Result.Success(ParserCtx.Empty(), 4)
  }

  test("resolutions given between the parser's rules are reported") {
    typeCheckErrors("""
    object MiddleMinusParser extends Parser:
      val Expr: Rule[Int] = rule(
        "minus" { case (Expr(left), MinusLexer.MINUS(_), Expr(right)) => left - right },
        { case MinusLexer.NUM(number) => number.value },
      )
      given Resolutions[MiddleMinusParser.type] = resolutions(production.minus.before(MinusLexer.MINUS))
      val root: Rule[Int] = rule { case Expr(value) => value }
    """).map(_.message) shouldBe List("Define resolutions as the last field of the parser.")
  }

  test("the text peeked in ErrorHandling keeps its content after the callback") {
    var seen: String | Null = null
    case class RecordingCtx(n: Int = 0) extends LexerCtx
    given ErrorHandling[RecordingCtx, LexerError] = (ctx, _) =>
      if seen == null then seen = ctx.peek(10)
      ErrorHandling.Strategy.SkipOne
    val Lexer = lexer[RecordingCtx]:
      case "a" => Token["A"]

    Lexer.tokenize("a!aa!").toEither.left.map(_.size) shouldBe Left(2)
    seen shouldBe "!aa!"
  }
