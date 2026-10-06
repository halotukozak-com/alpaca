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
      NEP(
        NonTerminal(Printable("S")),
        NEL[Symbol.NonEmpty](NonTerminal(Printable("L")), Terminal(Printable("=")), NonTerminal(Printable("R"))),
      ),
      NEP(NonTerminal(Printable("S")), NEL(NonTerminal(Printable("R")))),
      NEP(NonTerminal(Printable("L")), NEL[Symbol.NonEmpty](Terminal(Printable("1")), NonTerminal(Printable("R")))),
      NEP(NonTerminal(Printable("L")), NEL(Terminal(Printable("2")))),
      NEP(NonTerminal(Printable("R")), NEL[Symbol.NonEmpty](Terminal(Printable("3")), NonTerminal(Printable("L")))),
    )

    val expected = Map(
      NonTerminal(Printable("S")) -> Set(Terminal(Printable("1")), Terminal(Printable("2")), Terminal(Printable("3"))),
      NonTerminal(Printable("L")) -> Set(Terminal(Printable("1")), Terminal(Printable("2"))),
      NonTerminal(Printable("R")) -> Set(Terminal(Printable("3"))),
    )

    assert(FirstSet(productions) == expected)
  }

  test("FirstSet should handle epsilon productions") {

    val productions: List[Production] = List(
      NEP(NonTerminal(Printable("E")), NEL(NonTerminal(Printable("T")), NonTerminal(Printable("E'")))),
      NEP(
        NonTerminal(Printable("E'")),
        NEL[Symbol.NonEmpty](Terminal(Printable("+")), NonTerminal(Printable("T")), NonTerminal(Printable("E'"))),
      ),
      Production.Empty(NonTerminal(Printable("E'"))),
      NEP(NonTerminal(Printable("T")), NEL(NonTerminal(Printable("F")), NonTerminal(Printable("T'")))),
      NEP(
        NonTerminal(Printable("T'")),
        NEL[Symbol.NonEmpty](Terminal(Printable("*")), NonTerminal(Printable("F")), NonTerminal(Printable("T'"))),
      ),
      Production.Empty(NonTerminal(Printable("T'"))),
      NEP(
        NonTerminal(Printable("F")),
        NEL[Symbol.NonEmpty](Terminal(Printable("(")), NonTerminal(Printable("E")), Terminal(Printable(")"))),
      ),
      NEP(NonTerminal(Printable("F")), NEL(Terminal(Printable("id")))),
    )

    val expected = Map(
      NonTerminal(Printable("E")) -> Set(Terminal(Printable("(")), Terminal(Printable("id"))),
      NonTerminal(Printable("E'")) -> Set(Terminal(Printable("+")), internal.parser.Symbol.Empty),
      NonTerminal(Printable("T")) -> Set(Terminal(Printable("(")), Terminal(Printable("id"))),
      NonTerminal(Printable("T'")) -> Set(Terminal(Printable("*")), internal.parser.Symbol.Empty),
      NonTerminal(Printable("F")) -> Set(Terminal(Printable("(")), Terminal(Printable("id"))),
    )

    assert(FirstSet(productions) == expected)
  }
