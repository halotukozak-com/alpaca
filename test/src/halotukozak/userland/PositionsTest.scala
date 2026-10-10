package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

val PositionLexer = lexer:
  case w @ "[a-z]+" => Token["WORD"](w)
  case "\\+" => Token["PLUS"]
  case "\n" => Token.Ignored
  case "[ \t]+" => Token.Ignored

final case class SkippingCtx(line: Line = Line.Start, column: Column = Column.Start) extends LexerCtx

object SkippingCtx:
  given ErrorHandling[SkippingCtx, LexerError] = (_, _) => ErrorHandling.Strategy.SkipOne

val SkippingLexer = lexer[SkippingCtx]:
  case w @ "[a-z]+" => Token["WORD"](w)
  case "[ \t]+" => Token.Ignored

object PlusParser extends Parser:
  val Word: Rule[String] = rule { case PositionLexer.WORD(w) => w.value }
  val root: Rule[String] = rule { case (Word(a), PositionLexer.PLUS(_), Word(b)) => a + b }

final case class RenamedCtx(ln: Line = Line.Start, col: Column = Column.Start, words: Int = 0) extends LexerCtx

val RenamedLexer = lexer[RenamedCtx]:
  case w @ "[a-z]+" =>
    ctx.words += 1
    Token["WORD"](w)
  case "\\+" => Token["PLUS"]
  case "\n" => Token.Ignored
  case "[ \t]+" => Token.Ignored

object RenamedParser extends Parser:
  val Word: Rule[String] = rule { case RenamedLexer.WORD(w) => w.value }
  val root: Rule[String] = rule { case (Word(a), RenamedLexer.PLUS(_), Word(b)) => a + b }

opaque type Offset <: Int = Int

object Offset:
  def apply(n: Int): Offset = n
  given Tracking[Offset] = (matched, offset) => offset + matched.length

final case class OffsetCtx(offset: Offset = Offset(0), words: Int = 0) extends LexerCtx

val OffsetLexer = lexer[OffsetCtx]:
  case w @ "[a-z]+" =>
    ctx.words += 1
    Token["WORD"](w)
  case "!" =>
    ctx.offset = Offset(0)
    Token["RESET"]
  case "[ \t]+" => Token.Ignored

final case class TwoOffsetsCtx(fromStart: Offset = Offset(0), fromTen: Offset = Offset(10)) extends LexerCtx

val TwoOffsetsLexer = lexer[TwoOffsetsCtx]:
  case w @ "[a-z]+" => Token["WORD"](w)
  case "[ \t]+" => Token.Ignored

final case class CountingCtx(count: Int = 0) extends LexerCtx

object IntTracking:
  given Tracking[Int] = (_, count) => count + 100

  val CountingLexer = lexer[CountingCtx]:
    case w @ "[a-z]+" => Token["WORD"](w)
    case "[ \t]+" => Token.Ignored

object WideTabs:
  given Tracking[Column] = (matched, column) => Column(column + matched.map(char => if char == '\t' then 4 else 1).sum)

  val WideTabLexer = lexer:
    case w @ "[a-z]+" => Token["WORD"](w)
    case "[ \t]+" => Token.Ignored

val UntrackedLexer = lexer[LexerCtx.Empty]:
  case w @ "[a-z]+" => Token["WORD"](w)
  case "\\+" => Token["PLUS"]
  case "\\s+" => Token.Ignored

object UntrackedParser extends Parser:
  val Word: Rule[String] = rule { case UntrackedLexer.WORD(w) => w.value }
  val root: Rule[String] = rule { case (Word(a), UntrackedLexer.PLUS(_), Word(b)) => a + b }

final class PositionsTest extends AnyFunSuite with Matchers with LoneElement:

  private def loneError[E](result: Result[?, ?, E]): E = result match
    case Result.Failure(_, _, errors) => errors.loneElement
    case Result.Success(_, _) => fail("expected a failure")

  test("the default context's column field is called column") {
    LexerCtx.Default().column shouldBe 1
    PositionLexer.tokenize("ab cd").ctx.column shouldBe 6
  }

  test("a lexeme records where its token starts") {
    val List(word) = PositionLexer.tokenize("abc").getOrThrow.runtimeChecked
    (word.line, word.column) shouldBe (1, 1)
  }

  test("several tokens on one line start at their own columns") {
    PositionLexer.tokenize("ab + cde").getOrThrow.map(l => (l.text, l.line, l.column)) shouldBe
      List(("ab", 1, 1), ("+", 1, 4), ("cde", 1, 6))
  }

  test("a token after a newline starts at column 1 of the next line") {
    PositionLexer.tokenize("ab\ncd\n  e").getOrThrow.map(l => (l.text, l.line, l.column)) shouldBe
      List(("ab", 1, 1), ("cd", 2, 1), ("e", 3, 3))
  }

  test("columns count code points, so an emoji is one column") {
    val result = SkippingLexer.tokenize("😀 ab 😀😀cd")
    result.toEither.left.map(_.map(_.column)) shouldBe Left(List(1, 6, 7))
    result match
      case Result.Failure(ctx, recovered, _) =>
        recovered.map(_.map(l => (l.text, l.column))) shouldBe Some(List(("ab", 3), ("cd", 8)))
        ctx.column shouldBe 10
      case Result.Success(_, _) => fail("expected the emoji to be reported")
  }

  test("untracked fields are snapshotted after the rule body") {
    RenamedLexer.tokenize("ab + cd").getOrThrow.map(l => (l.text, l.ln, l.col, l.words)) shouldBe
      List(("ab", 1, 1, 1), ("+", 1, 4, 1), ("cd", 1, 6, 2))
  }

  test("a user-defined tracked field is the token start in a lexeme") {
    OffsetLexer.tokenize("ab  cde f").getOrThrow.map(l => (l.text, l.offset, l.words)) shouldBe
      List(("ab", 0, 1), ("cde", 4, 2), ("f", 8, 3))
  }

  test("a tracked field assigned in the rule body is the token start in its lexeme and the assigned value after") {
    OffsetLexer.tokenize("ab ! cd").getOrThrow.map(l => (l.text, l.offset)) shouldBe List(("ab", 0), ("!", 3), ("cd", 1))
  }

  test("two fields of the same fragment type are both tracked") {
    TwoOffsetsLexer.tokenize("ab cd").getOrThrow.map(l => (l.text, l.fromStart, l.fromTen)) shouldBe
      List(("ab", 0, 10), ("cd", 3, 13))
  }

  test("a given Tracking in scope at the lexer call tracks every field of its type") {
    IntTracking.CountingLexer.tokenize("ab cd").ctx.count shouldBe 300
  }

  test("a given Tracking in scope at the lexer call replaces the fragment's own, for that lexer only") {
    WideTabs.WideTabLexer.tokenize("ab\tcd").getOrThrow.map(l => (l.text, l.column)) shouldBe List(("ab", 1), ("cd", 7))
    PositionLexer.tokenize("ab\tcd").getOrThrow.map(l => (l.text, l.column)) shouldBe List(("ab", 1), ("cd", 4))
  }

  test("a lexer error has the lexer's typed fields where the input starts") {
    val error = loneError(OffsetLexer.tokenize("ab ?"))
    (error.unexpected, error.offset, error.words) shouldBe ("?", 3, 1)
    error.message shouldBe "Unexpected character '?'"
  }

  test("a parser error's unexpected lexeme has the lexer's typed fields") {
    PlusParser.parse(PositionLexer.tokenize("ab\n  cd + e").getOrThrow) match
      case Result.Failure(_, _, errors) =>
        val error = errors.loneElement
        error.unexpected.map(l => (l.text, l.line, l.column)) shouldBe Some(("cd", 2, 3))
        error.last shouldBe None
        error.message shouldBe """Unexpected WORD "cd". Expected one of: PLUS"""
      case Result.Success(_, _) => fail("expected a parse failure")
  }

  test("a lexer's types name its lexemes and errors with their typed fields") {
    def where(lexeme: PositionLexer.Lexeme): (Int, Int) = (lexeme.line, lexeme.column)
    def reported(error: PositionLexer.ParserError): Option[(Int, Int)] = error.unexpected.map(where)

    reported(loneError(PlusParser.parse(PositionLexer.tokenize("ab\n  cd + e").getOrThrow))) shouldBe Some((2, 3))
  }

  test("parser errors compare by their lexemes and expected input") {
    val lexemes = PositionLexer.tokenize("ab\n  cd + e").getOrThrow
    val error = loneError(PlusParser.parse(lexemes))

    loneError(PlusParser.parse(lexemes)) shouldBe error
    loneError(PlusParser.parse(lexemes)).hashCode shouldBe error.hashCode
    loneError(PlusParser.parse(lexemes.take(1))) should not be error
  }

  test("at the end of the input, a parser error points at the last lexeme") {
    RenamedParser.parse(RenamedLexer.tokenize("ab +\n").getOrThrow) match
      case Result.Failure(_, _, errors) =>
        val error = errors.loneElement
        error.unexpected shouldBe None
        error.last.map(l => (l.text, l.ln, l.col, l.words)) shouldBe Some(("+", 1, 4, 1))
      case Result.Success(_, _) => fail("expected a parse failure")
  }

  test("the message at the end of the input names the last lexeme") {
    loneError(PlusParser.parse(PositionLexer.tokenize("ab +\n").getOrThrow)).message shouldBe
      """Unexpected end of input after PLUS "+". Expected one of: WORD"""
  }

  test("a parser error with no lexemes at all has no last lexeme") {
    val error = loneError(PlusParser.parse(Nil))
    error.last shouldBe None
    error.message shouldBe "Unexpected end of input. Expected one of: WORD"
  }

  test("a parser error's message has no position") {
    loneError(UntrackedParser.parse(UntrackedLexer.tokenize("ab cd").getOrThrow)).message should
      startWith("""Unexpected WORD "cd". Expected""")
  }
