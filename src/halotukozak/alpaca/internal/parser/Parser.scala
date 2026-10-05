package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.{rule, ParserCtx, ParserError, ProductionDefinition, Result, Rule}
import halotukozak.alpaca.internal.{fieldsTpeFrom, refinementTpeFrom, withDefault, Empty, RevertedArray, RuleOnly, ValidName, *}
import halotukozak.alpaca.internal.lexer.Lexeme
import halotukozak.alpaca.internal.parser.{Tables, *}

import scala.annotation.{compileTimeOnly, publicInBinary, tailrec}
import scala.collection.mutable

/**
 * A trait that provides compile-time access to named productions for use in conflict resolution definitions.
 *
 * It is typically used when specifying conflict resolutions, enabling you to refer to productions
 * in a type-safe and compile-time-checked manner.
 *
 * @note This is a compile-time only feature and should be used within parser definitions.
 */
transparent private[alpaca] trait ProductionSelector extends Selectable:
  def selectDynamic(name: String): Any

/**
 * Base class for parsers.
 *
 * Users should extend this class and define their grammar rules as `Rule` instances.
 * The parser uses an LR parsing algorithm with automatic parse table generation.
 *
 * @tparam Ctx the global context type, defaults to EmptyGlobalCtx
 */
abstract class Parser[Ctx <: ParserCtx](
  using Ctx withDefault ParserCtx.Empty,
)(using
  empty: Empty[Ctx],
  tables: Tables[Ctx],
):

  /**
   * The root rule of the grammar.
   *
   * This is the starting point for parsing.
   */
  val root: Rule[?]

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
   * @param lexemes the list of lexemes to parse
   * @return the value the root rule produced, or the errors that stopped the parser, with the context either way
   */
  @publicInBinary private[alpaca] def parseResult[R](lexemes: List[Lexeme[?, ?]]): Result[Ctx, R, ParserError] = {
    enum Node:
      case Result(value: Any)
      case Token(lexeme: Lexeme[?, ?])

      def get: Any = this match
        case Node.Result(value) => value
        case Node.Token(lexeme) => lexeme

    val ctx = empty()

    val stateStack = mutable.ArrayDeque.empty[Int]
    val nodeStack = mutable.ArrayDeque.empty[Node]
    stateStack += 0
    nodeStack += Node.Result(null)

    @tailrec def loop(remaining: List[Lexeme[?, ?]]): Node | ParserError = {
      val current = if remaining.isEmpty then Lexeme.EOF else remaining.head
      val nextSymbol = Terminal(current.name)
      val action = tables.parseTable.get(stateStack.last, nextSymbol)
      if action == null then ParserError(current, tables.parseTable.expectedTerminals(stateStack.last))
      else {
        action match {
          case ParseAction.Shift(gotoState) =>
            stateStack += gotoState
            nodeStack += Node.Token(current)
            loop(if remaining.isEmpty then Nil else remaining.tail)

          case ParseAction.Reduction(prod @ Production.NonEmpty(lhs, rhs, name, _)) =>
            val n = rhs.size
            val newStateIdx = stateStack(stateStack.size - 1 - n)

            if lhs == Symbol.Start && newStateIdx == 0 then nodeStack.last
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

          case ParseAction.Reduction(Production.Empty(Symbol.Start, name, _)) if stateStack.last == 0 =>
            nodeStack.last

          case ParseAction.Reduction(prod @ Production.Empty(lhs, name, _)) =>
            val ParseAction.Shift(gotoState) = tables.parseTable(stateStack.last, lhs).runtimeChecked
            val result = tables.actionTable(prod)(ctx, RevertedArray.empty)
            stateStack += gotoState
            nodeStack += Node.Result(result)
            loop(remaining)
        }
      }
    }

    loop(lexemes) match
      case error: ParserError => Result.Failure(ctx, None, ::(error, Nil))
      case node: Node @unchecked =>
        val value = node match
          case Node.Result(value) => value
          case Node.Token(_) => null
        Result.Success(ctx, value.asInstanceOf[R])
  }

private val cachedProductions: mutable.Map[Type[? <: AnyKind], (Type[? <: AnyKind], Type[? <: AnyKind])] =
  mutable.Map.empty

// $COVERAGE-OFF$
def productionImpl[P <: Parser[?]: Type](using quotes: Quotes): Expr[ProductionSelector] = {
  import quotes.reflect.*
  cachedProductions
    .getOrElseUpdate(
      Type.of[P], {
        val rules = TypeRepr
          .of[P]
          .typeSymbol
          .declarations
          .iterator
          .collect:
            case decl if decl.typeRef <:< TypeRepr.of[Rule[?]] => decl.tree

        val extractName: PartialFunction[Expr[Rule[?]], Seq[String]] =
          case '{ rule(${ Varargs(cases) }*) } =>
            cases.flatMap:
              case '{ ($name: ValidName).apply($_ : ProductionDefinition[?]) } => name.value
              case _ => None

        val fields = rules
          .flatMap:
            // todo: def rules, or error? https://github.com/halotukozak/alpaca/issues/230
            case DefinitionRhs(_, rhs) => extractName(rhs.asExprOf[Rule[?]])
            case _ =>
              error("Define resolutions as the last field of the parser.", Position.ofMacroExpansion)
              Nil
          .map(name => (name, TypeRepr.of[Production]))
          .toList

        (refinementTpeFrom(fields).asType, fieldsTpeFrom(fields).asType)
      },
    )
    .runtimeChecked match
    case ('[refinement], '[fields]) =>
      '{ DummyProductionSelector.asInstanceOf[ProductionSelector { type Fields = fields } & refinement] }
}

/**
 * A real (non-null) placeholder instance of [[ProductionSelector]].
 *
 * `production.someName` is meant to be intercepted and rewritten entirely at compile
 * time, but the underlying `resolutions(...)` call is an ordinary runtime function, so
 * this placeholder still gets evaluated and `.selectDynamic` still gets called on it.
 * It must be a real object rather than `null.asInstanceOf[...]`, otherwise that call
 * NPEs instead of reaching the `.after`/`.before` extension methods, which are inline
 * and discard their receiver/arguments entirely.
 */
@publicInBinary private[parser] object DummyProductionSelector extends ProductionSelector:
  override def selectDynamic(name: String): Any = null
