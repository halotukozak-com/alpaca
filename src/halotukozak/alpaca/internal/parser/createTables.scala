package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.Csv.toCsv
import halotukozak.alpaca.internal.lexer.Token

import scala.annotation.publicInBinary
import scala.collection.immutable.VectorMap
import scala.reflect.NameTransformer

/**
 * An opaque type containing the parse and action tables for the parser.
 *
 * The parse table is used to drive the LR parsing algorithm, while the
 * action table maps productions to their semantic actions. These tables
 * are generated at compile time by analyzing the grammar rules.
 *
 * @tparam Ctx the parser context type
 */
opaque type Tables[Ctx <: ParserCtx] = (parseTable: ParseTable, actionTable: ActionTable[Ctx])

object Tables:
  /**
   * Automatically generates parse and action tables from a parser definition.
   *
   * This given instance triggers compile-time macro expansion to analyze
   * the parser's grammar rules and generate the necessary tables.
   *
   * @tparam Ctx the parser context type
   * @return the generated parse and action tables
   */
  inline given [Ctx <: ParserCtx]: Tables[Ctx] = ${ createTablesImpl[Ctx] }

  extension [Ctx <: ParserCtx](tables: Tables[Ctx])
    private[alpaca] def parseTable: ParseTable = tables.parseTable
    private[alpaca] def actionTable: ActionTable[Ctx] = tables.actionTable

/**
 * Macro implementation that builds parse and action tables at compile time.
 *
 * This is a complex macro that:
 * 1. Extracts grammar rules from the parser definition
 * 2. Converts them to productions with semantic actions
 * 3. Constructs the LR parse table
 * 4. Generates debug output if enabled
 *
 * Note on collection choices (#466): `table`/`rules`/`productions` are deliberately
 * materialized to `List` rather than kept lazy (`View`/`Iterator`) because each is traversed
 * multiple times downstream (productions extraction, root lookup, parse/action table
 * construction) -- a lazy view would just re-run the macro-tree-walking computation behind it
 * on every one of those traversals instead of once, a pessimization rather than a speedup.
 * The actual compile-time cost found here wasn't the collection *type* but a collection being
 * rebuilt on every call: `findProduction`'s lookup maps were reconstructed from the full
 * production list on every `.after`/`.before` reference instead of once -- see that function.
 *
 * @tparam Ctx the parser context type
 * @param quotes the Quotes instance
 * @return an expression containing the parse and action tables
 */
// $COVERAGE-OFF$
@publicInBinary private[parser] def createTablesImpl[Ctx <: ParserCtx: Type](
  using quotes: Quotes,
): Expr[(parseTable: ParseTable, actionTable: ActionTable[Ctx])] = {
  import quotes.reflect.*
  given Diagnostics = Diagnostics()
  val parserSymbol = Symbol.spliceOwner.owner.owner
  val parserTpe = parserSymbol.typeRef

  parserTpe.asType match {
    case '[type p <: Parser[Ctx]; p] =>
      val ctxSymbol = parserSymbol.methodMember("ctx").head
      val parserName = Printable(declaredName(parserSymbol))
      val exportName = exportId(parserName.raw)

      val symbolExtractor = extractEBNFAndAction[Ctx]
      def symbolOf(pattern: Tree) = symbolExtractor
        .lift(pattern)
        .orElse:
          error(
            show"Each element of a production's pattern must be a token or rule extractor, as in `MyLexer.NUM(n)`, `MyLexer.PLUS(_)`, `Expr(e)` or `Expr.List(es)`",
            pattern.pos,
          )
          None

      def extractEBNF(ruleName: String)
        : PartialFunction[Expr[Rule[?]], Seq[(production: Production, action: Expr[Action[Ctx]])]] = {
        case '{ rule(${ Varargs(cases) }*) } =>
          def createAction(binds: Seq[Option[Bind]], rhs: Term) = createLambda[Action[Ctx]]:
            case (methSym, (ctx: Term) :: (param: Term) :: Nil) =>
              val paramExpr = param.asExprOf[RevertedArray[Any]]
              val replacements = (find = ctxSymbol, replace = ctx) ::
                binds.iterator.zipWithIndex
                  .collect:
                    case (Some(bind), idx) => ((bind.symbol, bind.symbol.termRef.widen.asType), Expr(idx))
                  .flatMap:
                    case ((bind, '[t]), idx) =>
                      Some((find = bind, replace = '{ $paramExpr($idx).asInstanceOf[t] }.asTerm))
                    case other => raiseShouldNeverBeCalled(other)
                  .toList

              replaceRefs(replacements*).transformTerm(rhs)(methSym)

          val extractProductionName: Function[Expr[ProductionDefinition[?]], (Tree, ValidName | Null)] =
            case '{ ($name: String).apply($production: ProductionDefinition[?]) } =>
              production.asTerm -> name.value.fold[ValidName | Null] {
                error(show"A production name must be a string literal, as in `\"plus\" { case ... }`", name.asTerm.pos)
                null
              }(_.asInstanceOf[ValidName])
            case other =>
              other.asTerm -> null

          cases.iterator
            .map(extractProductionName)
            .map:
              case (Lambda(_, Match(_, List(caseDef))), name) => (caseDef, name)
              case (l @ Lambda(_, Match(_, _)), _) =>
                errorAndAbort(
                  show"""Each production must have exactly one case. Split multiple cases into separate productions:
                    |  rule(
                    |    { case (a(x)) => ... },
                    |    { case (b(y)) => ... }
                    |  )""".trimMargin,
                  l.pos,
                )
              case (other, _) =>
                errorAndAbort(show"Unexpected production definition: $other", other.pos)
            .flatMap:
              case (c @ CaseDef(_, Some(_), _), _) =>
                error(show"Guards are not supported yet", c.pos)
                None
              // Tuple1
              case (c @ CaseDef(skipTypedOrTest(pattern @ Unapply(_, _, List(_))), None, rhs), name) =>
                symbolOf(pattern).toList.flatMap: (symbol, bind, others) =>
                  val source = Source(c.pos)
                  val production =
                    Production.NonEmpty(NonTerminal(Printable(ruleName)), NEL(symbol), Printable.nullable(name), source)
                  (production = production, action = createAction(List(bind), rhs)) :: others

              // TupleN, N > 1
              case (c @ CaseDef(skipTypedOrTest(Unapply(_, _, patterns)), None, rhs), name) =>
                val elements = patterns.map(symbolOf)
                if elements.contains(None) then Nil
                else
                  val (symbols, binds, others) = elements.flatten.unzip3(using _.toTuple)
                  val source = Source(c.pos)
                  val production =
                    Production.NonEmpty(
                      NonTerminal(Printable(ruleName)),
                      NEL(symbols.head, symbols.tail*),
                      Printable.nullable(name),
                      source,
                    )
                  (production = production, action = createAction(binds, rhs)) :: others.flatten
              case (c, _) =>
                error(
                  show"A production must match a token or rule extractor, or a tuple of them, as in `case (Expr(a), MyLexer.PLUS(_), Expr(b))`",
                  c.pattern.pos,
                )
                None
            .toList
      }

      val rules = parserTpe.typeSymbol.declarations.iterator.collect:
        case decl if decl.typeRef <:< TypeRepr.of[Rule[?]] => decl.tree // todo: can we avoid .tree?

      val table = rules
        .flatMap:
          // todo: def rules, or error? https://github.com/halotukozak/alpaca/issues/230
          case DefinitionRhs(ruleName, rhs) =>
            extractEBNF(ruleName).applyOrElse(
              rhs.asExprOf[Rule[?]],
              _ =>
                error(
                  show"Cannot read the productions of rule ${Printable(ruleName)}: define it with a `rule(...)` call.",
                  rhs.pos,
                )
                Nil,
            )
          case other: ValOrDefDef =>
            errorAndAbort(
              show"Cannot read the definition of rule ${Printable(other.name)}. Enable -Yretain-trees compiler flag",
              other.pos,
            )
          case other => raiseShouldNeverBeCalled(other)
        .toList
        .tap: _ =>
          // a rule reported as unreadable stops the expansion before the errors that would only follow from its
          // productions missing (e.g. "No root rule defined")
          abortOnErrors()
        .tap: table =>
          // csv may be not the best format for this due to the commas
          logger.toFile(s"${parserName.raw}/actionTable.dbg.csv", true)(table.toCsv)

      val productions = table
        .map(_.production)
        .tap: table =>
          logger.toFile(s"${parserName.raw}/productions.dbg", true)(table.mkShow("\n"))
        .tap(JsonExport.maybeWrite(exportName, "productions", _))

      // a name has to pick out a single production whether or not the resolutions refer to it
      for
        case first :: others <- productions.filter(_.name != null).groupBy(_.name).values.toList
        duplicate <- others
      do
        error(
          show"Production name '${duplicate.name.nn}' is already used by $first; give each production its own name",
          duplicate.source.toPosition.getOrElse(Position.ofMacroExpansion),
        )
      abortOnErrors()

      // Built once and reused by every findProduction call below, instead of once per call --
      // findProduction runs once per `.after`/`.before` reference in the grammar's conflict
      // resolutions, and rebuilding both maps from the full production list on every one of
      // those calls is wasted work that scales with resolutions × productions for no reason.
      val productionsByName = productions.iterator
        .collect:
          case p if p.name != null => (p.name, p)
        .toMap

      val productionsByRhs = productions.groupBy(_.rhs)

      def findProduction(call: Expr[Production]): Production = call match {
        case '{ ($_ : ProductionSelector).selectDynamic(${ Expr(name) }).$asInstanceOf$[i] } =>
          val decodedName = Printable(NameTransformer.decode(name))
          productionsByName.getOrElse(
            decodedName,
            errorAndAbort(show"Production with name '$decodedName' not found", call.asTerm.pos),
          )

        case '{ alpaca.Production(${ Varargs(rhs) }*) } =>
          val args = rhs
            .map[parser.Symbol.NonEmpty]:
              case '{ type ruleType <: Rule[?]; $_ : ruleType }
                  if TypeRepr.of[ruleType].termSymbol.maybeOwner == parserTpe.typeSymbol =>
                NonTerminal(Printable(TypeRepr.of[ruleType].termSymbol.name))
              case arg @ '{ type ruleType <: Rule[?]; $_ : ruleType } if TypeRepr.of[ruleType].termSymbol.exists =>
                val rule = TypeRepr.of[ruleType].termSymbol
                errorAndAbort(
                  show"Rule ${Printable(rule.name)} belongs to another parser, ${Printable(declaredName(rule.owner))}; `Production(...)` in the resolutions of $parserName can only refer to $parserName's rules",
                  arg.asTerm.pos,
                )
              case '{ type name <: ValidName; $_ : Token[name, ?, ?] } => Terminal(Printable(ValidName.from[name]))
              case other =>
                errorAndAbort(
                  show"Arguments of `Production(...)` must be rules or tokens of this parser",
                  other.asTerm.pos,
                )
            .toList

          productionsByRhs.getOrElse(NEL.unsafe(args), Nil) match
            case production :: Nil => production
            case Nil => errorAndAbort(show"Production with RHS '${args.mkShow(" ")}' not found", call.asTerm.pos)
            case candidates =>
              errorAndAbort(
                show"""Production with RHS '${args.mkShow(" ")}' is ambiguous, it matches:
                      |${candidates.mkShow("  ", "\n  ", "")}
                      |Name the production you mean and refer to it with `production.<name>`""".trimMargin,
                call.asTerm.pos,
              )

        case definition =>
          errorAndAbort(
            show"Refer to a production with `production.<name>` or `Production(<symbols>...)`",
            definition.asTerm.pos,
          )
      }

      val givenResolutions: Option[Term] = Implicits.search(TypeRepr.of[Resolutions[p]]) match
        case _: NoMatchingImplicits => None
        case failure: ImplicitSearchFailure => errorAndAbort(failure.explanation.showRaw, Position.ofMacroExpansion)
        case success: ImplicitSearchSuccess => Some(success.tree)

      // a missing given just means no resolutions; any other failure to read them is reported where they're defined,
      // since silently ignoring them would surface later as a seemingly unresolved conflict
      val resolutionExprs = givenResolutions match {
        case None => Nil
        case Some(givenRef) =>
          val givenSymbol = givenRef.symbol

          def unsupported(pos: Position): Nothing = errorAndAbort(
            show"""Cannot read the conflict resolutions of $parserName.
                  |Define them directly with a call to `resolutions`, e.g.:
                  |  given Resolutions[$parserName.type] = resolutions(...)""".trimMargin,
            pos,
          )

          val rhs = givenSymbol.tree match
            case DefinitionRhs(_, rhs) => rhs
            case definition => unsupported(definition.pos)

          rhs.asExprOf[Resolutions[p]] match
            case '{ resolutions[p & Parser[?]](${ Varargs(resolutionExprs) }*) } => resolutionExprs
            case _ => unsupported(rhs.pos)
      }

      def extractKey(expr: Expr[Production | Token[?, ?, ?]]): ConflictKey = expr match
        case '{ $prod: Production } => ConflictKey.Reduction(findProduction(prod))
        case '{ $_ : Token[name, ?, ?] } => ConflictKey.Shift(Printable(ValidName.from[name]))

      // each rule remembers the `.before(...)`/`.after(...)` argument it came from, so errors about it can point there;
      // kept in declaration order, so a cycle is searched from the first declared rule and reported at the one closing it
      val conflictResolutionTable = ConflictResolutionTable(
        resolutionExprs.iterator
          .flatMap:
            case '{ (ctx: ResolutionCtx[p]) ?=> ($after: Production | Token[?, ?, ?]).after(${ Varargs(befores) }*) } =>
              befores.map(before => (extractKey(before), extractKey(after), Source(before.asTerm.pos)))
            case '{ (ctx: ResolutionCtx[p]) ?=>
                  ($before: Production | Token[?, ?, ?]).before(${ Varargs(afters) }*)
                } =>
              afters.map(after => (extractKey(before), extractKey(after), Source(after.asTerm.pos)))
            case other =>
              errorAndAbort(
                show"Each conflict resolution must be a direct `x.before(...)` or `x.after(...)` call",
                other.asTerm.pos,
              )
          .foldLeft(VectorMap.empty[ConflictKey, Map[ConflictKey, Source]]):
            case (table, (before, after, source)) =>
              table + (before -> (table.getOrElse(before, VectorMap.empty) + (after -> source))),
      ).tap: table =>
        logger.toFile(s"${parserName.raw}/conflictResolutions.dbg", true)(table)
        logger.toFile(s"${parserName.raw}/conflictResolutions.mmd", true)(table.toMermaid.showRaw)
        table.verifyNoConflicts()

      val root = table
        .collectFirst:
          case (p @ Production.NonEmpty(lhs, _, _, _), _) if lhs == NonTerminal(Printable("root")) => p
        .getOrElse:
          errorAndAbort(
            show"No root rule defined in $parserName. Define a root rule: val root: Rule[Any] = rule { ... }",
            // the parser declaration itself, which is where the root rule is missing
            Position.ofMacroExpansion,
          )

      // the synthetic start production stands for the root rule
      val start = Production.NonEmpty(parser.Symbol.Start, NEL(root.lhs), source = root.source)

      val parseTable = Expr:
        ParseTable(
          start :: table.map(_.production),
          conflictResolutionTable,
        ).tap: parseTable =>
          logger.toFile(s"${parserName.raw}/parseTable.dbg.csv", true)(parseTable.toCsv)
        .tap(JsonExport.maybeWrite(exportName, "table", _))

      val actionTable = Expr.ofList:
        table.map:
          case (production, action) => Expr.ofTuple(Expr(production) -> action)

      // referenced only to avoid an unused-implicit warning; kept lazy and never forced,
      // since eagerly forcing it here (during Tables[Ctx] construction, i.e. during the
      // parser object's own <init>) would deadlock against `given Resolutions[P]` instances
      // that refer back to the parser object (e.g. via `Production(MyParser.SomeRule, ...)`)
      val referenceGivenResolutions: Expr[Unit] = givenResolutions match
        case Some(givenRef) => '{ lazy val _ = ${ givenRef.asExprOf[Resolutions[p]] } }
        case None => '{ () }

      '{
        $referenceGivenResolutions
        ($parseTable.asInstanceOf[ParseTable], ActionTable($actionTable.toMap))
      }
  }
}
// $COVERAGE-ON$
