package halotukozak
package alpaca
package internal
package parser

import org.scalatest.funsuite.AnyFunSuite
import halotukozak.alpaca.internal.parser.Production.NonEmpty as NEP
import halotukozak.alpaca.internal.parser.{FirstSet, NonTerminal, Production, Terminal}
import halotukozak.alpaca.internal.NEL

final class FirstSetTest extends AnyFunSuite:
  test("FirstSet should correctly identify first sets for simple grammar") {

    val productions: List[Production] = List(
      NEP(NonTerminal("S"), NEL(NonTerminal("L"), Terminal("="), NonTerminal("R")), source = TestSource),
      NEP(NonTerminal("S"), NEL(NonTerminal("R")), source = TestSource),
      NEP(NonTerminal("L"), NEL(Terminal("1"), NonTerminal("R")), source = TestSource),
      NEP(NonTerminal("L"), NEL(Terminal("2")), source = TestSource),
      NEP(NonTerminal("R"), NEL(Terminal("3"), NonTerminal("L")), source = TestSource),
    )

    val expected = Map(
      NonTerminal("S") -> Set(Terminal("1"), Terminal("2"), Terminal("3")),
      NonTerminal("L") -> Set(Terminal("1"), Terminal("2")),
      NonTerminal("R") -> Set(Terminal("3")),
    )

    assert(FirstSet(productions) == expected)
  }

  test("FirstSet should handle epsilon productions") {

    val productions: List[Production] = List(
      NEP(NonTerminal("E"), NEL(NonTerminal("T"), NonTerminal("E'")), source = TestSource),
      NEP(NonTerminal("E'"), NEL(Terminal("+"), NonTerminal("T"), NonTerminal("E'")), source = TestSource),
      Production.Empty(NonTerminal("E'"), source = TestSource),
      NEP(NonTerminal("T"), NEL(NonTerminal("F"), NonTerminal("T'")), source = TestSource),
      NEP(NonTerminal("T'"), NEL(Terminal("*"), NonTerminal("F"), NonTerminal("T'")), source = TestSource),
      Production.Empty(NonTerminal("T'"), source = TestSource),
      NEP(NonTerminal("F"), NEL(Terminal("("), NonTerminal("E"), Terminal(")")), source = TestSource),
      NEP(NonTerminal("F"), NEL(Terminal("id")), source = TestSource),
    )

    val expected = Map(
      NonTerminal("E") -> Set(Terminal("("), Terminal("id")),
      NonTerminal("E'") -> Set(Terminal("+"), internal.parser.Symbol.Empty),
      NonTerminal("T") -> Set(Terminal("("), Terminal("id")),
      NonTerminal("T'") -> Set(Terminal("*"), internal.parser.Symbol.Empty),
      NonTerminal("F") -> Set(Terminal("("), Terminal("id")),
    )

    assert(FirstSet(productions) == expected)
  }
