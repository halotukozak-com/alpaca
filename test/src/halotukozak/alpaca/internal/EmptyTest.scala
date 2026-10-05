package halotukozak
package alpaca.internal

import halotukozak.alpaca.internal.Empty
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class EmptyTest extends AnyFunSuite with Matchers:

  inline def empty[T]: Empty[T] = compiletime.summonInline[Empty[T]]

  // Helpers and domain models
  case class Zero() derives Empty

  case class WithDefaults(a: Int = 1, b: String = "x") derives Empty

  case class Inner(x: Int = 42) derives Empty

  case class Outer(inner: Inner = Inner(), tags: List[String] = Nil, opt: Option[Int] = None) derives Empty

  case class Box[T](value: Option[T] = None)

  case class Mixed(a: Int, b: String = "b") // has a param without default -> should fail

  case object Singleton

  test("derived produces an instance using all default arguments for a simple case class") {
    empty[WithDefaults]() shouldEqual WithDefaults()
  }

  test("derived works for zero-arity case class") {
    empty[Zero]() shouldEqual Zero()
  }

  test("derived returns the object itself for a case object") {
    (empty[Singleton.type]() should be).theSameInstanceAs(Singleton)
  }

  test("derived supports nested case classes and common containers when defaults are provided") {
    empty[Outer]() shouldEqual Outer(inner = Inner(), tags = Nil, opt = None)
  }

  test("derived supports generic case classes when defaults define a value (e.g., Option[T] = None)") {
    empty[Box[Inner]]() shouldEqual Box[Inner](None)
  }

  test("cannot derive Empty when any parameter lacks a default (compile-time)") {
    """
      |import alpaca.core.Empty
      |case class Mixed(a: Int, b: String = "b") derives Empty
      |""".stripMargin shouldNot compile
  }

  test("cannot derive Empty for non-case classes (compile-time)") {
    """
      |import alpaca.core.Empty
      |class Regular(val x: Int) derives Empty
      |""".stripMargin shouldNot compile
  }
