package halotukozak
package alpaca.internal

import halotukozak.alpaca.*
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.typeCheckErrors

final case class ZeroCtx() extends LexerCtx

final case class Inner(x: Int = 42)

final case class WithDefaultsCtx(
  a: Int = 1,
  b: String = "x",
  inner: Inner = Inner(),
  tags: List[String] = Nil,
  opt: Option[Int] = None,
) extends LexerCtx

final case class BoxCtx[T](value: Option[T] = None) extends ParserCtx

val ZeroLexer = lexer[ZeroCtx]:
  case n @ "[0-9]+" => Token["NUM"](n.toInt)

val WithDefaultsLexer = lexer[WithDefaultsCtx]:
  case n @ "[0-9]+" => Token["NUM"](n.toInt)

object BoxParser extends Parser[BoxCtx[Inner]]:
  val root: Rule[Int] = rule:
    case WithDefaultsLexer.NUM(n) => n.value

final class FromDefaultsTest extends AnyFunSuite with Matchers with LoneElement:

  test("a lexer builds its initial context from the context's default arguments") {
    WithDefaultsLexer.tokenize("1").ctx shouldEqual WithDefaultsCtx()
  }

  test("a lexer with a zero-arity context builds it") {
    ZeroLexer.tokenize("1").ctx shouldEqual ZeroCtx()
  }

  test("a parser builds a generic initial context from its default arguments (e.g., Option[T] = None)") {
    BoxParser.parse(WithDefaultsLexer.tokenize("1").getOrThrow).ctx shouldEqual BoxCtx[Inner](None)
  }

  test("a context field without a default is a compile error") {
    typeCheckErrors("""
      import halotukozak.alpaca.*

      final case class Mixed(a: Int, b: String = "b") extends LexerCtx

      val L = lexer[Mixed]:
        case n @ "[0-9]+" => Token["NUM"](n.toInt)
    """).distinct.loneElement.message shouldBe
      "Field `a` of Mixed has no default value. Every field of a lexer or parser context needs one, so that the initial context can be built."
  }

  test("names the first parameter that lacks a default") {
    typeCheckErrors("""
      import halotukozak.alpaca.*

      final case class TwoMissing(a: Int, b: String, c: Int = 0) extends LexerCtx

      val L = lexer[TwoMissing]:
        case n @ "[0-9]+" => Token["NUM"](n.toInt)
    """).distinct.loneElement.message should startWith("Field `a` of TwoMissing has no default value.")
  }

  test("a lexer context field without a default is a compile error, not a runtime crash (#617)") {
    typeCheckErrors("""
      import halotukozak.alpaca.*
      import scala.collection.mutable.ListBuffer

      final case class Ctx(
        column: Column = Column.Start,
        line: Line = Line.Start,
        errors: ListBuffer[String],
      ) extends LexerCtx

      val L = lexer[Ctx]:
        case n @ "[0-9]+" => Token["NUM"](n.toInt)
    """).distinct.loneElement.message should startWith("Field `errors` of Ctx has no default value.")
  }

  test("a tracking field without a default is reported the same way (#617)") {
    typeCheckErrors("""
      import halotukozak.alpaca.*

      final case class Ctx(line: Line) extends LexerCtx

      val L = lexer[Ctx]:
        case n @ "[0-9]+" => Token["NUM"](n.toInt)
    """).distinct.loneElement.message should startWith("Field `line` of Ctx has no default value.")
  }

  test("a parser context field without a default is a compile error (#617)") {
    typeCheckErrors("""
      import halotukozak.alpaca.*

      val L = lexer:
        case n @ "[0-9]+" => Token["NUM"](n.toInt)

      final case class Ctx(depth: Int) extends ParserCtx

      object P extends Parser[Ctx]:
        val root: Rule[Int] = rule:
          case L.NUM(n) => n.value
    """).distinct.loneElement.message should startWith("Field `depth` of Ctx has no default value.")
  }

  test("a parser context that is a sum type is a compile error") {
    typeCheckErrors("""
      import halotukozak.alpaca.*

      val L = lexer:
        case n @ "[0-9]+" => Token["NUM"](n.toInt)

      sealed trait Ctx extends ParserCtx
      final case class A() extends Ctx

      object P extends Parser[Ctx]:
        val root: Rule[Int] = rule:
          case L.NUM(n) => n.value
    """).distinct.loneElement.message shouldBe "The context should be a case class."
  }

  test("a parser context that is not a case class is a compile error") {
    typeCheckErrors("""
      import halotukozak.alpaca.*

      val L = lexer:
        case n @ "[0-9]+" => Token["NUM"](n.toInt)

      final class Ctx extends ParserCtx

      object P extends Parser[Ctx]:
        val root: Rule[Int] = rule:
          case L.NUM(n) => n.value
    """).distinct.loneElement.message shouldBe "Ctx should be a case class."
  }
