package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.typeCheckErrors

final case class NotesCtx(
  line: Line = Line.Start,
  notes: List[String] = Nil,
) extends LexerCtx

given ErrorHandling[NotesCtx] = ErrorHandling.forLexer: error =>
  ctx.notes :+= s"${error.unexpected} on line ${error.line}"
  if ctx.peek(1) == "#" then ErrorHandling.Strategy.SkipToNextMatch else ErrorHandling.Strategy.SkipOne

val NotesLexer = lexer[NotesCtx]:
  case "\n" => Token.Ignored
  case "[a-z]+" => Token["WORD"]

final case class SkipCountCtx(var skips: Int = 0) extends LexerCtx

given ErrorHandling[SkipCountCtx] = ErrorHandling.forLexer: _ =>
  ctx.skips += 1
  ErrorHandling.Strategy.SkipToNextMatch

val SkipCountLexer = lexer[SkipCountCtx]:
  case "[a-z]+" => Token["WORD"]

final case class SkipBudgetCtx(skipsLeft: Int = 1, seen: List[String] = Nil) extends ParserCtx

given ErrorHandling[SkipBudgetCtx] = ErrorHandling.forParser: error =>
  ctx.seen :+= error.unexpected.fold("end")(_.text)
  if ctx.skipsLeft == 0 then ErrorHandling.Strategy.Stop
  else
    ctx.skipsLeft -= 1
    ErrorHandling.Strategy.SkipOne

val SumLexer = lexer:
  case "\\+" => Token["+"]
  case value @ "[0-9]+" => Token["Num"](value.toInt)

object SkipBudgetParser extends Parser[SkipBudgetCtx]:
  val Sum: Rule[Int] = rule(
    { case (Sum(sum), SumLexer.`+`(_), SumLexer.Num(lexeme)) => sum + lexeme.value },
    { case SumLexer.Num(lexeme) => lexeme.value },
  )
  val root: Rule[Int] = rule:
    case Sum(result) => result

final class ErrorHandlingCtxTest extends AnyFunSuite with Matchers with LoneElement:

  test("ErrorHandling.forLexer assigns immutable context fields the lexer goes on with") {
    val Result.Failure(ctx, recovered, errors) = NotesLexer.tokenize("ab?c\nd!e").runtimeChecked
    ctx.notes shouldBe List("? on line 1", "! on line 2")
    recovered.map(_.map(lexeme => (lexeme.text, lexeme.notes))) shouldBe Some(
      List(("ab", Nil), ("c", List("? on line 1")), ("d", List("? on line 1")), ("e", List("? on line 1", "! on line 2"))),
    )
    // an error keeps the context from before its handler ran
    errors.map(error => (error.unexpected, error.notes)) shouldBe List(("?", Nil), ("!", List("? on line 1")))
  }

  test("ErrorHandling.forLexer reads the input still to be tokenized") {
    val Result.Failure(ctx, _, errors) = NotesLexer.tokenize("a##b").runtimeChecked
    ctx.notes shouldBe List("# on line 1")
    errors.map(error => (error.unexpected, error.notes)) shouldBe List(("##", Nil))
  }

  test("a SkipToNextMatch error keeps the var fields from before its handler ran") {
    val Result.Failure(ctx, _, errors) = SkipCountLexer.tokenize("a??b!c").runtimeChecked
    ctx.skips shouldBe 2
    errors.map(error => (error.unexpected, error.skips)) shouldBe List(("??", 0), ("!", 1))
  }

  test("ErrorHandling.forParser assigns immutable context fields the parser goes on with") {
    val result = SkipBudgetParser.parse(SumLexer.tokenize("1++2++3").getOrThrow)
    result.ctx shouldBe SkipBudgetCtx(skipsLeft = 0, seen = List("+", "+"))
  }

  test("a lexer rule and a handler assign the local vars they capture") {
    var words = 0
    var skipped = 0
    given ErrorHandling[LexerCtx.Default] = ErrorHandling.forLexer: _ =>
      skipped += 1
      ErrorHandling.Strategy.SkipOne
    val CountingLexer = lexer:
      case "[a-z]+" =>
        words += 1
        Token["WORD"]
      case " " => Token.Ignored
    val _ = CountingLexer.tokenize("ab ? cd !")
    (words, skipped) shouldBe (2, 2)
  }

  test("a handler for a context that is both a lexer and a parser context is reported") {
    val error = typeCheckErrors("""
      case class BothCtx() extends LexerCtx, ParserCtx
      ErrorHandling.forLexer[BothCtx](_ => ErrorHandling.Strategy.Stop)
    """).loneElement
    error.message shouldBe
      "`BothCtx` is both a `LexerCtx` and a `ParserCtx`; give the lexer and the parser a context each"
    error.lineContent.trim shouldBe "ErrorHandling.forLexer[BothCtx](_ => ErrorHandling.Strategy.Stop)"
  }

  test("an ErrorHandling.forLexer handler must be a lambda") {
    val error = typeCheckErrors("""
      val strategy: LexerScope.Of[NotesCtx] ?=> LexerError.Of[NotesCtx] => ErrorHandling.Strategy =
        _ => ErrorHandling.Strategy.Stop
      ErrorHandling.forLexer[NotesCtx](strategy)
    """).loneElement
    error.message shouldBe
      "An `ErrorHandling.forLexer` handler must be a lambda written directly in its block, as in `error => ErrorHandling.Strategy.SkipOne`"
    error.lineContent.trim shouldBe "ErrorHandling.forLexer[NotesCtx](strategy)"
  }
