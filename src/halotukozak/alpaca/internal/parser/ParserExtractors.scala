package halotukozak
package alpaca
package internal
package parser

import alpaca.internal.lexer.Token
import alpaca.internal.parser.ParserExtractors.*

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
 * Analyzes a single pattern from a parser rule definition during macro expansion,
 * extracting the grammar symbol it matches (terminal or non-terminal) together
 * with any EBNF desugaring (`Option`, `List`, `SeparatedBy`) it requires.
 */
private[parser] def extractEBNFAndAction[Ctx <: ParserCtx: Type](using quotes: Quotes): PartialFunction[
  quotes.reflect.Tree,
  (
    symbol: parser.Symbol.NonEmpty,
    bind: Option[quotes.reflect.Bind],
    others: List[(production: Production, action: Expr[Action[Ctx]])],
  ),
] =
  import quotes.reflect.*

  def symbolFromType(tpe: TypeRepr): parser.Symbol.NonEmpty = tpe.dealias.widen.asType match
    case '[type name <: ValidName; Token[name, ?, ?]] => Terminal(ValidName.from[name])
    case '[Rule[?]] => NonTerminal(NameTransformer.decode(tpe.termSymbol.name))
    case _ => report.errorAndAbort(show"SeparatedBy separator must be a Token or Rule type, but got: ${tpe.show}")

  type SymbolExtractor = PartialFunction[Tree, (name: String, bind: Option[Bind], extractor: String | Null)]

  enum Extractor[T: Type] extends SymbolExtractor:
    case Terminal extends Extractor[Token[?, ?, ?]]
    case NonTerminal extends Extractor[Rule[?]]

    private val underlying: SymbolExtractor =
      case skipTypedOrTest(
            Unapply(Select(Extractor.Unpack(term, name, extractor), Names.Unapply), Nil, List(Extractor.Bind(bind))),
          ) if term.tpe <:< TypeRepr.of[T] =>
        (NameTransformer.decode(name), bind, extractor)
    override def isDefinedAt(x: Tree): Boolean = underlying.isDefinedAt(x)
    override def apply(x: Tree): (name: String, bind: Option[Bind], extractor: String | Null) = underlying.apply(x)

  object Extractor:
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

    val SeparatedBy: PartialFunction[Tree, (qualifier: Term, name: String, separator: parser.Symbol.NonEmpty)] =
      case TypeApply(Select(q @ Extractor.Name(name), Names.SeparatedBy), List(separator)) =>
        (q, name, symbolFromType(separator.tpe))

    val Bind: PartialFunction[Tree, Option[Bind]] =
      case bind: Bind => Some(bind)
      case Ident("_") => None

    val Symbol: SymbolExtractor =
      case Extractor.Terminal(name, bind, extractor) => (name, bind, extractor)
      case Extractor.NonTerminal(name, bind, extractor) => (name, bind, extractor)

  {
    case skipTypedOrTest(
          Unapply(Select(Extractor.SeparatedBy(_, name, separator), Names.Unapply), Nil, List(Extractor.Bind(bind))),
        ) =>
      val fresh = NonTerminal.fresh(name)
      val nonEmpty = NonTerminal.fresh(show"${name}_nonEmpty")
      (
        symbol = fresh,
        bind = bind,
        others = List(
          (
            production = Production.Empty(fresh),
            action = '{ emptyRepeatedAction },
          ),
          (
            production = Production.NonEmpty(fresh, NEL(nonEmpty)),
            action = '{ identityAction },
          ),
          (
            production = Production.NonEmpty(nonEmpty, NEL(NonTerminal(name))),
            action = '{ headAction },
          ),
          (
            production = Production.NonEmpty(nonEmpty, NEL(nonEmpty, separator, NonTerminal(name))),
            action = '{ separatedByAction },
          ),
        ),
      )

    case Extractor.NonTerminal(name, bind, null) =>
      (symbol = NonTerminal(name), bind = bind, others = Nil)

    case Extractor.Terminal(name, bind, null) =>
      (symbol = Terminal(name), bind = bind, others = Nil)

    case Extractor.Symbol(name, bind, Names.Option) =>
      val fresh = NonTerminal.fresh(name)
      (
        symbol = fresh,
        bind = bind,
        others = List(
          (production = Production.Empty(fresh), action = '{ noneAction }),
          (
            production = Production.NonEmpty(fresh, NEL(NonTerminal(name))),
            action = '{ someAction },
          ),
        ),
      )

    case Extractor.Symbol(name, bind, Names.List) =>
      val fresh = NonTerminal.fresh(name)
      (
        symbol = fresh,
        bind = bind,
        others = List(
          (production = Production.Empty(fresh), action = '{ emptyRepeatedAction }),
          (
            production = Production.NonEmpty(fresh, NEL(fresh, NonTerminal(name))),
            action = '{ repeatedAction },
          ),
        ),
      )
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
    currList.appended(newElem)

  val headAction: Action[ParserCtx] = (_, args) => List(args.head)

  val identityAction: Action[ParserCtx] = (_, args) => args.head

  val separatedByAction: Action[ParserCtx] = (_, args) =>
    val RevertedArray(newElem, separator, currList: List[?]) = args.runtimeChecked
    currList.appendedAll(List(separator, newElem))

  val emptyRepeatedAction: Action[ParserCtx] = (_, _) => Nil

  val someAction: Action[ParserCtx] = (_, args) => Some(args.head)

  val noneAction: Action[ParserCtx] = (_, _) => None
