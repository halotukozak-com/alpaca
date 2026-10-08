package halotukozak
package alpaca
package internal
package parser

import Symbol.SyntheticInfix
import halotukozak.alpaca.internal.{Printable, Showable}
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
  def name: Printable

/**
 * Represents a non-terminal symbol in the grammar.
 *
 * Non-terminals are symbols that can be expanded into other symbols
 * according to the grammar rules. For example, in a typical expression
 * grammar, "expr" and "term" would be non-terminals.
 *
 * @param name the name of the non-terminal
 */
sealed case class NonTerminal(name: Printable) extends AnyVal, Symbol

object NonTerminal:

  /**
   * Creates a fresh non-terminal symbol with a unique name.
   *
   * This is used internally to create temporary non-terminals for
   * EBNF operators like optional and repeated patterns.
   *
   * @param base      the symbol the extractor is applied to
   * @param extractor the extractor the non-terminal stands for, e.g. `List`
   * @return a non-terminal shown as `base.extractor`, with a unique name
   */
  def fresh(base: Symbol, extractor: String): NonTerminal & Symbol.NonEmpty =
    NonTerminal(Printable(s"${base.name.raw}.${extractor}_${SyntheticInfix}_${Random.alphanumeric.take(8).mkString}"))

  /**
   * Creates a non-terminal symbol from a name.
   *
   * @param name the name of the non-terminal
   * @return a non-empty non-terminal symbol
   */
  inline def apply(inline name: Printable): NonTerminal & Symbol.NonEmpty =
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
sealed case class Terminal(name: Printable) extends AnyVal, Symbol

object Terminal:
  /**
   * Creates a terminal symbol from a name.
   *
   * @param name the name of the terminal (token name)
   * @return a non-empty terminal symbol
   */
  inline def apply(inline name: Printable): Terminal & Symbol.NonEmpty =
    new Terminal(name).asInstanceOf[Terminal & Symbol.NonEmpty]

@publicInBinary private[parser] object Symbol:
  final val SyntheticInfix = "$$synthetic$$"

  type NonEmpty = Symbol { type IsEmpty = false }

  /** The augmented start symbol used internally by the parser, shown as `S'`; synthetic like [[EOF]]. */
  val Start: NonTerminal { type IsEmpty = false } = NonTerminal(Printable("S'" + SyntheticInfix))

  /** The end-of-file terminal symbol, shown as `$`; synthetic so that a token named `$` stays separate. */
  val EOF: Terminal { type IsEmpty = false } = Terminal(Printable("$" + SyntheticInfix))

  /** The empty terminal symbol (epsilon), shown as `ε`; synthetic like [[EOF]]. */
  val Empty: Terminal { type IsEmpty = true } =
    Terminal(Printable("ε" + SyntheticInfix)).asInstanceOf[Terminal { type IsEmpty = true }]

  /**
   * Placeholder lookahead used only while propagating LALR(1) lookaheads (#504, see
   * [[Lookaheads]]). Never appears in a real reduce action or parse table entry -- within a
   * per-kernel-item closure seeded with this symbol, any item that still carries it means "this
   * item's real lookahead is whatever the seed's turns out to be" (propagation), while any other
   * terminal it closure-generates is a lookahead the target state gets regardless of the seed
   * (spontaneous generation).
   */
  val Dummy: Terminal { type IsEmpty = false } = Terminal(Printable("#" + SyntheticInfix))

  extension (symbol: Symbol)
    /**
     * The name as the user wrote it: unencoded (`+`, not `$plus`), without the synthetic suffix (`$`, `ε`, `S'`),
     * and EBNF-synthesized non-terminals without their uniqueness suffix (`Operation.List`).
     */
    private[parser] def displayName: Printable =
      val name = symbol.name.raw
      if name.endsWith(SyntheticInfix) then Printable(name.dropRight(SyntheticInfix.length))
      else
        name.indexOf(s"_${SyntheticInfix}_") match
          case -1 => symbol.name
          case end => Printable(name.substring(0, end))

  /**
   * Symbols are shown by their [[displayName]], with characters that would not show up in a message escaped (see
   * [[Printable]]).
   */
  given Showable[Symbol] = _.displayName.show

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
          case s: NonTerminal => (kind = "nonterminal", name = s.name.raw)
          case s: Terminal => (kind = "terminal", name = s.name.raw)
        },
        onRead = _ => throw UnsupportedOperationException("Symbol's export codec is write-only"),
      )
// $COVERAGE-ON$
