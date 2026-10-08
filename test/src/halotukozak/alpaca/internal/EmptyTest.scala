package halotukozak
package alpaca.internal

import halotukozak.alpaca.internal.Empty
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.typeCheckErrors

final class EmptyTest extends AnyFunSuite with Matchers with LoneElement:

  // Helpers and domain models
  case class Zero()

  case class WithDefaults(a: Int = 1, b: String = "x")

  case class Inner(x: Int = 42)

  case class Outer(inner: Inner = Inner(), tags: List[String] = Nil, opt: Option[Int] = None)

  case class Box[T](value: Option[T] = None)

  case class Mixed(a: Int, b: String = "b") // has a param without default -> should fail

  test("derived produces an instance using all default arguments for a simple case class") {
    Empty.derived[WithDefaults]() shouldEqual WithDefaults()
  }

  test("derived works for zero-arity case class") {
    Empty.derived[Zero]() shouldEqual Zero()
  }

  test("derived supports nested case classes and common containers when defaults are provided") {
    Empty.derived[Outer]() shouldEqual Outer(inner = Inner(), tags = Nil, opt = None)
  }

  test("derived supports generic case classes when defaults define a value (e.g., Option[T] = None)") {
    Empty.derived[Box[Inner]]() shouldEqual Box[Inner](None)
  }

  test("cannot derive Empty when any parameter lacks a default (compile-time)") {
    typeCheckErrors("Empty.derived[Mixed]").distinct.loneElement.message shouldBe
      "Field `a` of Mixed has no default value. Every field of a lexer or parser context needs one, so that the initial context can be built."
  }

  test("names the first parameter that lacks a default") {
    case class TwoMissing(a: Int, b: String, c: Int = 0)
    typeCheckErrors("Empty.derived[TwoMissing]").distinct.loneElement.message should startWith(
      "Field `a` of TwoMissing has no default value.",
    )
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

  test("cannot derive Empty for non-case classes (compile-time)") {
    """
      |class Regular(val x: Int)
      |Empty.derived[Regular]
      |""".stripMargin shouldNot compile
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
