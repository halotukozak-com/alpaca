package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.{rule, ErrorHandling, ParserCtx, ParserError, ProductionDefinition, Result, Rule}
import halotukozak.alpaca.internal.{fieldsTpeFrom, refinementTpeFrom, withDefault, RevertedArray, RuleOnly, ValidName, *}
import halotukozak.alpaca.internal.lexer.Lexeme
import halotukozak.alpaca.internal.parser.{Tables, *}

import scala.NamedTuple.AnyNamedTuple
import scala.annotation.{compileTimeOnly, publicInBinary, tailrec}
import scala.collection.mutable
import scala.quoted.*

/**
 * Base class for parsers.
 *
 * Users should extend this class and define their grammar rules as `Rule` instances.
 * The parser uses an LR parsing algorithm with automatic parse table generation.
 *
 * @tparam Ctx the parser context type; `Parser` without a type argument uses [[ParserCtx.Empty]]
 */
abstract class Parser[Ctx <: ParserCtx](
  using Ctx withDefault ParserCtx.Empty,
)(using
  tables: Tables[Ctx],
  errorHandling: ErrorHandling[Ctx, ParserError],
):

  /**
   * The root rule of the grammar.
   *
   * This is the starting point for parsing.
   */
  val root: Rule[?]

  protected final given ParserScope = ParserScope.refl

  /**
   * Provides access to the parser context within rule definitions.
   *
   * This is compile-time only and can only be used inside parser rule definitions.
   */
  @compileTimeOnly(RuleOnly)
  inline protected final def ctx: Ctx = null.asInstanceOf[Ctx]

  /**
   * Parses a list of lexemes using the defined grammar.
   *
   * This method builds the parse table at compile time and uses it to
   * parse the input lexemes using an LR parsing algorithm.
   *
   * @tparam R the result type
   * @tparam LexemeFields the lexer context's fields, which the lexemes and the returned errors carry
   * @param lexemes the list of lexemes to parse
   * @return the value the root rule produced, or the errors met on the way, with the context either way; when the
   *         context's [[ErrorHandling]] skipped past the errors, the value is the failure's `recovered`
   */
  @publicInBinary private[alpaca] def parseResult[R, LexemeFields <: AnyNamedTuple](
    lexemes: List[Lexeme[?, ?] withFields LexemeFields],
  ): Result[Ctx, R, ParserError withFields LexemeFields] = {
    enum Node:
      case Result(value: Any)
      case Token(lexeme: Lexeme[?, ?])

      def get: Any = this match
        case Node.Result(value) => value
        case Node.Token(lexeme) => lexeme

    val ctx = tables.initialCtx()

    val stateStack = mutable.ArrayDeque.empty[Int]
    val nodeStack = mutable.ArrayDeque.empty[Node]
    stateStack += 0
    nodeStack += Node.Result(null)
    val errors = mutable.ListBuffer.empty[ParserError withFields LexemeFields]

    // The accepted root node, or `None` when an error stopped the parser.
    @tailrec def loop(remaining: List[Lexeme[?, ?] withFields LexemeFields]): Option[Node] = {
      val (current, nextSymbol) = remaining match
        case Nil => (Lexeme.EOF, Symbol.EOF)
        case head :: _ => (head, Terminal(Printable(head.name)))
      val action = tables.parseTable.get(stateStack.last, nextSymbol)
      if action == null then {
        val last = if remaining.isEmpty then lexemes.lastOption else None
        val expected = tables.parseTable
          .expectedTerminals(stateStack)
          .map[String | ParserError.EndOfInput]:
            case Symbol.EOF => ParserError.EndOfInput
            case terminal => terminal.displayName.raw
        val error = ParserError(remaining.headOption, expected, last)
        errors += error
        // the end of the input cannot be skipped
        errorHandling(ctx, error) match
          case ErrorHandling.Strategy.SkipOne if remaining.nonEmpty => loop(remaining.tail)
          case ErrorHandling.Strategy.SkipToNextMatch if remaining.nonEmpty =>
            val state = stateStack.last
            loop(
              remaining.tail.dropWhile(lexeme => tables.parseTable.get(state, Terminal(Printable(lexeme.name))) == null),
            )
          case _ => None
      } else {
        action match {
          case ParseAction.Shift(gotoState) =>
            stateStack += gotoState
            nodeStack += Node.Token(current)
            loop(if remaining.isEmpty then Nil else remaining.tail)

          case ParseAction.Reduction(prod @ Production.NonEmpty(lhs, rhs, name)) =>
            val n = rhs.size
            val newStateIdx = stateStack(stateStack.size - 1 - n)

            if lhs == Symbol.Start && newStateIdx == 0 then Some(nodeStack.last)
            else {
              val top = nodeStack.size - 1
              val children = Array.better.tabulate(n)(i => nodeStack(top - i).get)
              stateStack.dropRightInPlace(n)
              nodeStack.dropRightInPlace(n)

              val ParseAction.Shift(gotoState) = tables.parseTable(newStateIdx, lhs).runtimeChecked
              val result = tables.actionTable(prod)(ctx, RevertedArray(children))
              stateStack += gotoState
              nodeStack += Node.Result(result)
              loop(remaining)
            }

          case ParseAction.Reduction(Production.Empty(Symbol.Start, name)) if stateStack.last == 0 =>
            Some(nodeStack.last)

          case ParseAction.Reduction(prod @ Production.Empty(lhs, name)) =>
            val ParseAction.Shift(gotoState) = tables.parseTable(stateStack.last, lhs).runtimeChecked
            val result = tables.actionTable(prod)(ctx, RevertedArray.empty)
            stateStack += gotoState
            nodeStack += Node.Result(result)
            loop(remaining)
        }
      }
    }

    val value = loop(lexemes).map:
      case Node.Result(value) => value.asInstanceOf[R]
      case Node.Token(_) => null.asInstanceOf[R]

    errors.toList match
      case Nil => Result.Success(ctx, value.get)
      case first :: rest => Result.Failure(ctx, value, ::(first, rest))
  }

// $COVERAGE-OFF$
@publicInBinary private[alpaca] def productionImpl[P <: Parser[?]: Type](using Quotes): Expr[ProductionSelector] = {
  import quotes.reflect.*
  given Diagnostics = Diagnostics()
  val rules = TypeRepr
    .of[P]
    .typeSymbol
    .declarations
    .iterator
    .map(_.tree)
    .collect:
      case rule: ValOrDefDef if rule.tpt.tpe <:< TypeRepr.of[Rule[?]] => rule

  val extractName: PartialFunction[Expr[Rule[?]], Seq[String]] =
    case '{ rule(${ Varargs(cases) }*)(using $_) } =>
      cases.flatMap:
        case '{ ($name: ValidName).apply($_ : ProductionDefinition[?])(using $_) } => name.value
        case _ => None

  val fields = rules
    .flatMap:
      case _: DefDef => Nil
      case DefinitionRhs(_, rhs) => extractName.applyOrElse(rhs.asExprOf[Rule[?]], _ => Nil)
      case _ =>
        error(show"Define resolutions as the last field of the parser.", Position.ofMacroExpansion)
        Nil
    .map(name => (name, TypeRepr.of[alpaca.Production]))
    .toList

  (refinementTpeFrom(fields).asType, fieldsTpeFrom(fields).asType).runtimeChecked match
    case ('[refinement], '[fields]) =>
      '{ null.asInstanceOf[ProductionSelector { type Fields = fields } & refinement] }
}
