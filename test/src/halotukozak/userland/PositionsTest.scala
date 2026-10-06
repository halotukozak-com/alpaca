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

val UntrackedLexer = lexer[LexerCtx.Empty]:
  case w @ "[a-z]+" => Token["WORD"](w)
  case "\\+" => Token["PLUS"]
  case "\\s+" => Token.Ignored

object UntrackedParser extends Parser:
  val Word: Rule[String] = rule { case UntrackedLexer.WORD(w) => w.value }
  val root: Rule[String] = rule { case (Word(a), UntrackedLexer.PLUS(_), Word(b)) => a + b }

final class PositionsTest extends AnyFunSuite with Matchers with LoneElement:

  private def parserError(result: Result[?, ?, ParserError]): ParserError = result match
    case Result.Failure(_, _, errors) => errors.loneElement
    case Result.Success(_, _) => fail("expected a parse failure")

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
    result.toEither.left.map(_.map(_.column)) shouldBe Left(List(Some(1), Some(6), Some(7)))
    result match
      case Result.Failure(ctx, recovered, _) =>
        recovered.map(_.map(l => (l.text, l.column))) shouldBe Some(List(("ab", 3), ("cd", 8)))
        ctx.column shouldBe 10
      case Result.Success(_, _) => fail("expected the emoji to be reported")
  }

  test("fields other than Line and Column are snapshotted after the rule body") {
    RenamedLexer.tokenize("ab + cd").getOrThrow.map(l => (l.text, l.ln, l.col, l.words)) shouldBe
      List(("ab", 1, 1, 1), ("+", 1, 4, 1), ("cd", 1, 6, 2))
  }

  test("a parser error is positioned at the start of the unexpected lexeme") {
    val error = parserError(PlusParser.parse(PositionLexer.tokenize("ab\n  cd + e").getOrThrow))
    error.unexpected.text shouldBe "cd"
    (error.line, error.column) shouldBe (Some(2), Some(3))
    error.message shouldBe """Unexpected WORD "cd" at line 2, column 3. Expected one of: end of input, PLUS"""
  }

  test("at the end of the input, a parser error is positioned right after the last lexeme") {
    val error = parserError(PlusParser.parse(PositionLexer.tokenize("ab +\n").getOrThrow))
    (error.line, error.column) shouldBe (Some(1), Some(5))
    error.message shouldBe "Unexpected end of input at line 1, column 5. Expected one of: WORD"
  }

  test("a parser error with no lexemes at all has no position") {
    val error = parserError(PlusParser.parse(Nil))
    (error.line, error.column) shouldBe (None, None)
    error.message shouldBe "Unexpected end of input. Expected one of: WORD"
  }

  test("a parser error finds Line and Column fields by type, whatever their names") {
    val error = parserError(RenamedParser.parse(RenamedLexer.tokenize("ab\ncd").getOrThrow))
    (error.line, error.column) shouldBe (Some(2), Some(1))
  }

  test("a parser error has no position when the lexer context does not track one") {
    val error = parserError(UntrackedParser.parse(UntrackedLexer.tokenize("ab cd").getOrThrow))
    (error.line, error.column) shouldBe (None, None)
    error.message should startWith("""Unexpected WORD "cd". Expected""")
  }
