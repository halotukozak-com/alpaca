package halotukozak
package alpaca
package internal
package parser

import Symbol.SyntheticInfix
import halotukozak.alpaca.internal.Showable
import halotukozak.mcodec.MCodec

import scala.annotation.publicInBinary
import scala.util.Random

/**
 * Represents a grammar symbol (either terminal or non-terminal).
 *
 * In formal grammar theory, symbols are the basic building blocks of
 * productions. Terminals represent tokens from the lexer, while
 * non-terminals represent grammatical constructs.
 */
private[parser] trait Symbol extends Any:
  type IsEmpty <: Boolean
  def name: String

/**
 * Represents a non-terminal symbol in the grammar.
 *
 * Non-terminals are symbols that can be expanded into other symbols
 * according to the grammar rules. For example, in a typical expression
 * grammar, "expr" and "term" would be non-terminals.
 *
 * @param name the name of the non-terminal
 */
sealed case class NonTerminal(name: String) extends AnyVal, Symbol

object NonTerminal:

  /**
   * Creates a fresh non-terminal symbol with a unique name.
   *
   * This is used internally to create temporary non-terminals for
   * EBNF operators like optional and repeated patterns.
   *
   * @param name the base name for the non-terminal
   * @return a non-terminal with a unique name based on the input
   */
  def fresh(name: String): NonTerminal & Symbol.NonEmpty =
    NonTerminal(s"${name}_${SyntheticInfix}_${Random.alphanumeric.take(8).mkString}")

  /**
   * Creates a non-terminal symbol from a name.
   *
   * @param name the name of the non-terminal
   * @return a non-empty non-terminal symbol
   */
  inline def apply(inline name: String): NonTerminal & Symbol.NonEmpty =
    new NonTerminal(name).asInstanceOf[NonTerminal & Symbol.NonEmpty]

/**
 * Represents a terminal symbol in the grammar.
 *
 * Terminals are the basic tokens that come from the lexer and cannot
 * be expanded further. For example, numbers, identifiers, and operators
 * are typically terminals.
 *
 * @param name the name of the terminal (token name)
 */
sealed case class Terminal(name: String) extends AnyVal, Symbol

object Terminal:
  /**
   * Creates a terminal symbol from a name.
   *
   * @param name the name of the terminal (token name)
   * @return a non-empty terminal symbol
   */
  inline def apply(inline name: String): Terminal & Symbol.NonEmpty =
    new Terminal(name).asInstanceOf[Terminal & Symbol.NonEmpty]

@publicInBinary private[parser] object Symbol:
  final val SyntheticInfix = "$$synthetic$$"

  type NonEmpty = Symbol { type IsEmpty = false }

  /** The augmented start symbol used internally by the parser. */
  val Start: NonTerminal { type IsEmpty = false } = NonTerminal("S'")

  /** The end-of-file terminal symbol. */
  val EOF: Terminal { type IsEmpty = false } = Terminal("$")

  /** The empty terminal symbol (epsilon). */
  val Empty: Terminal { type IsEmpty = true } = Terminal("ε").asInstanceOf[Terminal { type IsEmpty = true }]

  /**
   * Placeholder lookahead used only while propagating LALR(1) lookaheads (#504, see
   * [[Lookaheads]]). Never appears in a real reduce action or parse table entry -- within a
   * per-kernel-item closure seeded with this symbol, any item that still carries it means "this
   * item's real lookahead is whatever the seed's turns out to be" (propagation), while any other
   * terminal it closure-generates is a lookahead the target state gets regardless of the seed
   * (spontaneous generation).
   */
  val Dummy: Terminal { type IsEmpty = false } = Terminal("#")

  /**
   * Symbols are shown as the user wrote them: token names unencoded (`+`, not `$plus`), and the non-terminals the
   * EBNF extractors synthesize by the extractor they stand for (`Operation.List`), without the uniqueness suffix.
   */
  given Showable[Symbol] = symbol =>
    symbol.name.indexOf(s"_${SyntheticInfix}_") match
      case -1 => symbol.name
      case end => symbol.name.substring(0, end)

  // $COVERAGE-OFF$
  given [S <: Symbol] => ToExpr[S]:
    def apply(x: S)(using Quotes): Expr[S] =
      x.match
        case x: NonTerminal => '{ NonTerminal(${ Expr(x.name) }) }
        case x: Terminal => '{ Terminal(${ Expr(x.name) }) }
      .asInstanceOf[Expr[S]]

  given MCodec[Symbol] =
    MCodec
      .derived[(kind: String, name: String)]
      .transform(
        onWrite = {
          case s: NonTerminal => (kind = "nonterminal", name = s.name)
          case s: Terminal => (kind = "terminal", name = s.name)
        },
        onRead = _ => throw UnsupportedOperationException("Symbol's export codec is write-only"),
      )
// $COVERAGE-ON$
