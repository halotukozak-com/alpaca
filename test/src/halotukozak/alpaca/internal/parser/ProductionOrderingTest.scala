package halotukozak
package alpaca
package internal
package parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.SortedSet

final class ProductionOrderingTest extends AnyFunSuite with Matchers:
  private def production(token: String): Production =
    Production.NonEmpty(NonTerminal(Printable("root")), NEL(Terminal(Printable(token))))

  private val first = production("t800000")
  private val second = production("t5051766")

  test("distinct productions with equal hashCodes compare as non-equal") {
    first.hashCode shouldBe second.hashCode
    first should not be second
    Ordering[Production].compare(first, second) should not be 0
    Ordering[Core].compare(Core(first), Core(second)) should not be 0
    SortedSet(first, second) should have size 2
  }

  test("alternatives with colliding production hashCodes both parse") {
    val CollidingLexer = alpaca.lexer:
      case "x" => Token["t800000"]
      case "y" => Token["t5051766"]

    object CollidingParser extends Parser:
      val root: Rule[Int] = rule(
        { case CollidingLexer.t800000(_) => 1 },
        { case CollidingLexer.t5051766(_) => 2 },
      )

    CollidingParser.parse(CollidingLexer.tokenize("x").getOrThrow) should matchPattern:
      case Result.Success(_, 1) =>

    CollidingParser.parse(CollidingLexer.tokenize("y").getOrThrow) should matchPattern:
      case Result.Success(_, 2) =>
  }
