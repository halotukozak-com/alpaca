package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.lexer.Token
import halotukozak.alpaca.internal.parser.ParserExtractors.*

import scala.quoted.*
import scala.reflect.NameTransformer

// $COVERAGE-OFF$

/**
 * Strips a `TypedOrTest` wrapper (a `: SomeType` type-test in a pattern) down to
 * the pattern underneath, used during macro expansion of parser rule definitions.
 */
private[parser] object skipTypedOrTest:
  def unapply(using quotes: Quotes)(tree: quotes.reflect.Tree): Some[quotes.reflect.Tree] =
    import quotes.reflect.*
    tree match
      case TypedOrTest(inner, _) => Some(inner)
      case other => Some(other)

/**
 * Matches a `val` or `def` definition with its right-hand side, which is only there when the definition's tree was
 * retained (`-Yretain-trees`).
 */
private[parser] object DefinitionRhs:
  def unapply(using quotes: Quotes)(tree: quotes.reflect.Tree): Option[(String, quotes.reflect.Term)] =
    import quotes.reflect.*
    tree match
      case definition: ValOrDefDef => definition.rhs.map((definition.name, _))
      case _ => None

/** A production with where it is defined and the action it runs on reduction. */
private[parser] type ProductionWithAction[Ctx <: ParserCtx] = (
  production: Production,
  source: Source,
  action: Expr[Action[Ctx]],
)

/** A pattern's bound value and how the action sees it; generic in `B` since `Bind` is path-dependent on `Quotes`. */
private[parser] type Binding[B] = (bind: Option[B], adapt: Expr[Any] => Expr[Any])

/**
 * Analyzes a single pattern from a parser rule definition during macro expansion,
 * extracting the grammar symbol it matches (terminal or non-terminal) together
 * with any EBNF desugaring (`Option`, `List`, `SeparatedBy`) it requires.
 */
private[parser] def extractEBNFAndAction[Ctx <: ParserCtx: Type](using Quotes, Diagnostics): PartialFunction[
  quotes.reflect.Tree,
  (
    symbol: parser.Symbol.NonEmpty,
    binding: Binding[quotes.reflect.Bind],
    others: List[ProductionWithAction[Ctx]],
  ),
] = {
  import quotes.reflect.*

  def symbolFromType(separator: TypeTree): parser.Symbol.NonEmpty = separator.tpe.dealias.widen.asType match
    case '[type name <: ValidName; Token[name, ?, ?]] => Terminal(Printable(ValidName.from[name]))
    case '[Rule[?]] => NonTerminal(Printable(NameTransformer.decode(separator.tpe.termSymbol.name)))
    case _ =>
      errorAndAbort(
        show"SeparatedBy separator must be a Token or Rule type, but got: ${separator.tpe.show.showRaw}",
        separator.pos,
      )

  type SymbolExtractor = PartialFunction[Tree, (name: String, bind: Option[Bind], extractor: String | Null)]

  enum Extractor[T: Type] extends SymbolExtractor:
    case Terminal extends Extractor[Token[?, ?, ?]]
    case NonTerminal extends Extractor[Rule[?]]

    private val underlying: SymbolExtractor =
      case skipTypedOrTest(
            Unapply(
              Select(Extractor.WithoutScope(Extractor.Unpack(term, name, extractor)), Names.Unapply),
              _,
              List(Extractor.Bind(bind)),
            ),
          ) if term.tpe <:< TypeRepr.of[T] =>
        (NameTransformer.decode(name), bind, extractor)
    override def isDefinedAt(x: Tree): Boolean = underlying.isDefinedAt(x)
    override def apply(x: Tree): (name: String, bind: Option[Bind], extractor: String | Null) = underlying.apply(x)

  object Extractor:
    // `.List`, `.Option` and `.SeparatedBy` take the `ParserScope` as an argument
    val WithoutScope: PartialFunction[Tree, Tree] =
      case Apply(fn, List(scope)) if scope.tpe <:< TypeRepr.of[ParserScope] => fn
      case other => other

    private val Name: PartialFunction[Term, String] =
      case Select(_, name) => name
      case Ident(name) => name
      case Literal(StringConstant(name)) => name
      case TypeApply(
            Select(Apply(Extractor.Name(Names.SelectDynamic), List(Extractor.Name(name))), Names.AsInstanceOf),
            List(_),
          ) =>
        name

    private val Unpack: PartialFunction[Tree, (qualifier: Term, name: String, extractor: String | Null)] =
      case Select(q @ Extractor.Name(name), extractor) => (q, name, extractor)
      case Apply(q @ Extractor.Name(extractor), List(Extractor.Name(name))) => (q, name, extractor)
      case q @ Extractor.Name(name) => (q, name, null)

    val SeparatedBy: PartialFunction[Tree, (element: parser.Symbol.NonEmpty, separator: parser.Symbol.NonEmpty)] =
      case TypeApply(Select(q @ Extractor.Name(name), Names.SeparatedBy), List(separator)) =>
        val decoded = NameTransformer.decode(name)
        val element: parser.Symbol.NonEmpty =
          if q.tpe <:< TypeRepr.of[Token[?, ?, ?]] then parser.Terminal(Printable(decoded))
          else parser.NonTerminal(Printable(decoded))
        (element, symbolFromType(separator))

    val Bind: PartialFunction[Tree, Option[Bind]] =
      case bind: Bind => Some(bind)
      case Ident("_") => None

    val Symbol: PartialFunction[Tree, (symbol: parser.Symbol.NonEmpty, bind: Option[Bind], extractor: String | Null)] =
      case Extractor.Terminal(name, bind, extractor) => (parser.Terminal(Printable(name)), bind, extractor)
      case Extractor.NonTerminal(name, bind, extractor) => (parser.NonTerminal(Printable(name)), bind, extractor)

  // helper productions desugared from EBNF are sourced at the pattern they come from; repeated uses of an extractor
  // repeat them, and only the first is kept
  {
    case pattern @ skipTypedOrTest(
          Unapply(
            Select(Extractor.WithoutScope(Extractor.SeparatedBy(element, separator)), Names.Unapply),
            _,
            List(Extractor.Bind(bind)),
          ),
        ) =>
      val source = Source(pattern.pos)
      val fresh = NonTerminal.fresh(element, "SeparatedBy", Some(separator))
      val nonEmpty = NonTerminal.fresh(element, "SeparatedBy.nonEmpty", Some(separator))
      (
        symbol = fresh,
        binding = (bind = bind, adapt = identity),
        others = List(
          (
            production = Production.Empty(fresh),
            source = source,
            action = '{ emptyRepeatedAction },
          ),
          (
            production = Production.NonEmpty(fresh, NEL(nonEmpty)),
            source = source,
            action = '{ reverseAction },
          ),
          (
            production = Production.NonEmpty(nonEmpty, NEL(element)),
            source = source,
            action = '{ headAction },
          ),
          (
            production = Production.NonEmpty(nonEmpty, NEL(nonEmpty, separator, element)),
            source = source,
            action = '{ separatedByAction },
          ),
        ),
      )

    case Extractor.NonTerminal(name, bind, null) =>
      (symbol = NonTerminal(Printable(name)), binding = (bind = bind, adapt = identity), others = Nil)

    case Extractor.Terminal(name, bind, null) =>
      (symbol = Terminal(Printable(name)), binding = (bind = bind, adapt = identity), others = Nil)

    case pattern @ Extractor.Symbol(symbol, bind, Names.Option) =>
      val source = Source(pattern.pos)
      val fresh = NonTerminal.fresh(symbol, "Option")
      (
        symbol = fresh,
        binding = (bind = bind, adapt = identity),
        others = List(
          (production = Production.Empty(fresh), source = source, action = '{ noneAction }),
          (
            production = Production.NonEmpty(fresh, NEL(symbol)),
            source = source,
            action = '{ someAction },
          ),
        ),
      )

    case pattern @ Extractor.Symbol(symbol, bind, Names.List) =>
      val source = Source(pattern.pos)
      val fresh = NonTerminal.fresh(symbol, "List")
      (
        symbol = fresh,
        binding = (bind = bind, adapt = v => '{ $v.asInstanceOf[List[?]].reverse }),
        others = List(
          (production = Production.Empty(fresh), source = source, action = '{ emptyRepeatedAction }),
          (
            production = Production.NonEmpty(fresh, NEL(fresh, symbol)),
            source = source,
            action = '{ repeatedAction },
          ),
        ),
      )
  }
}

// $COVERAGE-ON$

private object ParserExtractors:
  private[parser] object Names:
    final val SelectDynamic = "selectDynamic"
    final val Unapply = "unapply"
    final val List = "List"
    final val Option = "Option"
    final val SeparatedBy = "SeparatedBy"
    final val AsInstanceOf = "$asInstanceOf$"

  val repeatedAction: Action[ParserCtx] = (_, args) =>
    val RevertedArray(newElem, currList: List[?]) = args.runtimeChecked
    newElem :: currList

  val headAction: Action[ParserCtx] = (_, args) => List(args.head)

  val reverseAction: Action[ParserCtx] = (_, args) =>
    val RevertedArray(reversed: List[?]) = args.runtimeChecked
    reversed.reverse

  val separatedByAction: Action[ParserCtx] = (_, args) =>
    val RevertedArray(newElem, separator, currList: List[?]) = args.runtimeChecked
    newElem :: separator :: currList

  val emptyRepeatedAction: Action[ParserCtx] = (_, _) => Nil

  val someAction: Action[ParserCtx] = (_, args) => Some(args.head)

  val noneAction: Action[ParserCtx] = (_, _) => None
