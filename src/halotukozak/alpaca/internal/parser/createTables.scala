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
opaque private[alpaca] type Tables[Ctx <: ParserCtx] <: (parseTable: ParseTable, actionTable: ActionTable[Ctx]) =
  (parseTable: ParseTable, actionTable: ActionTable[Ctx])

private[alpaca] object Tables:
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
  val parserSymbol = Symbol.spliceOwner.owner.owner
  val parserTpe = parserSymbol.typeRef

  parserTpe.asType match {
    case '[type p <: Parser[Ctx]; p] =>
      val ctxSymbol = parserSymbol.methodMember("ctx").head
      val parserName = declaredName(parserSymbol)
      val exportName = exportId(parserName)

      def extractEBNF(ruleName: String)
        : PartialFunction[Expr[Rule[?]], Seq[(production: Production, action: Expr[Action[Ctx]])]] = {
        case '{ rule(${ Varargs(cases) }*) } =>
          def createAction(binds: Seq[Option[Bind]], rhs: Term) = createLambda[Action[Ctx]]:
            case (methSym, (ctx: Term) :: (param: Term) :: Nil) =>
              val paramExpr = param.asExprOf[RevertedArray[Any]]
              val replacements = (find = ctxSymbol, replace = ctx) ::
                binds.iterator.zipWithIndex
                  .collect:
                    case (Some(bind), idx) => ((bind.symbol, bind.symbol.typeRef.asType), Expr(idx))
                  .flatMap:
                    case ((bind, '[t]), idx) =>
                      Some((find = bind, replace = '{ $paramExpr($idx).asInstanceOf[t] }.asTerm))
                    case other => raiseShouldNeverBeCalled(other)
                  .toList

              replaceRefs(replacements*).transformTerm(rhs)(methSym)

          val extractProductionName: Function[Expr[ProductionDefinition[?]], (Tree, ValidName | Null)] =
            case '{ ($name: ValidName).apply($production: ProductionDefinition[?]) } =>
              production.asTerm -> name.value.orNull
            case other =>
              other.asTerm -> null

          cases.iterator
            .map(extractProductionName)
            .map:
              case (Lambda(_, Match(_, List(caseDef))), name) => (caseDef, name)
              case (l @ Lambda(_, Match(_, _)), _) =>
                errorAndAbort(
                  """Each production must have exactly one case. Split multiple cases into separate productions:
                    |  rule(
                    |    { case (a(x)) => ... },
                    |    { case (b(y)) => ... }
                    |  )""".stripMargin,
                  l.pos,
                )
              case (other, _) =>
                errorAndAbort(show"Unexpected production definition: $other", other.pos)
            .flatMap:
              case (c @ CaseDef(_, Some(_), _), _) =>
                error("Guards are not supported yet", c.pos)
                None
              // Tuple1
              case (c @ CaseDef(skipTypedOrTest(pattern @ Unapply(_, _, List(_))), None, rhs), name) =>
                val (symbol, bind, others) = extractEBNFAndAction[Ctx](pattern)
                val source = Source(c.pos)
                val production = Production.NonEmpty(NonTerminal(ruleName), NEL(symbol), name, source)
                (production = production, action = createAction(List(bind), rhs)) :: others

              // TupleN, N > 1
              case (c @ CaseDef(skipTypedOrTest(Unapply(_, _, patterns)), None, rhs), name) =>
                val (symbols, binds, others) = patterns.map(extractEBNFAndAction[Ctx]).unzip3(using _.toTuple)
                val source = Source(c.pos)
                val production =
                  Production.NonEmpty(NonTerminal(ruleName), NEL(symbols.head, symbols.tail*), name, source)
                (production = production, action = createAction(binds, rhs)) :: others.flatten
              case other => raiseShouldNeverBeCalled(other)
            .toList
      }

      val rules = parserTpe.typeSymbol.declarations.iterator.collect:
        case decl if decl.typeRef <:< TypeRepr.of[Rule[?]] => decl.tree // todo: can we avoid .tree?

      val table = rules
        .flatMap:
          // todo: def rules, or error? https://github.com/halotukozak/alpaca/issues/230
          case DefinitionRhs(ruleName, rhs) => extractEBNF(ruleName)(rhs.asExprOf[Rule[?]])
          case other: ValOrDefDef =>
            errorAndAbort(
              show"Cannot read the definition of rule ${other.name}. Enable -Yretain-trees compiler flag",
              other.pos,
            )
          case other => raiseShouldNeverBeCalled(other)
        .toList
        .tap: table =>
          // csv may be not the best format for this due to the commas
          logger.toFile(show"$parserName/actionTable.dbg.csv", true)(table.toCsv)

      val productions = table
        .map(_.production)
        .tap: table =>
          logger.toFile(show"$parserName/productions.dbg", true)(table.mkShow("\n"))
        .tap(JsonExport.maybeWrite(exportName, "productions", _))

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
          val decodedName = NameTransformer.decode(name)
          productionsByName.getOrElse(
            decodedName,
            errorAndAbort(show"Production with name '$decodedName' not found", call.asTerm.pos),
          )

        case '{ alpaca.Production(${ Varargs(rhs) }*) } =>
          val args = rhs
            .map[parser.Symbol.NonEmpty]:
              case '{ type ruleType <: Rule[?]; $_ : ruleType } => NonTerminal(TypeRepr.of[ruleType].termSymbol.name)
              case '{ type name <: ValidName; $_ : Token[name, ?, ?] } => Terminal(ValidName.from[name])
              case other =>
                errorAndAbort("Arguments of `Production(...)` must be rules or tokens of this parser", other.asTerm.pos)
            .toList

          productionsByRhs.getOrElse(NEL.unsafe(args), Nil) match
            case production :: Nil => production
            case Nil => errorAndAbort(show"Production with RHS '${args.mkShow(" ")}' not found", call.asTerm.pos)
            case candidates =>
              errorAndAbort(
                show"""Production with RHS '${args.mkShow(" ")}' is ambiguous, it matches:
                      |${candidates.mkShow("  ", "\n  ", "")}
                      |Name the production you mean and refer to it with `production.<name>`""".stripMargin,
                call.asTerm.pos,
              )

        case definition =>
          errorAndAbort(
            "Refer to a production with `production.<name>` or `Production(<symbols>...)`",
            definition.asTerm.pos,
          )
      }

      val givenResolutions: Option[Term] = Implicits.search(TypeRepr.of[Resolutions[p]]) match
        case _: NoMatchingImplicits => None
        case failure: ImplicitSearchFailure => errorAndAbort(failure.explanation, Position.ofMacroExpansion)
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
                  |  given Resolutions[$parserName.type] = resolutions(...)""".stripMargin,
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
        case '{ $prod: Production } => ConflictKey(findProduction(prod))
        case '{ $_ : Token[name, ?, ?] } => ConflictKey(ValidName.from[name])

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
                "Each conflict resolution must be a direct `x.before(...)` or `x.after(...)` call",
                other.asTerm.pos,
              )
          .foldLeft(VectorMap.empty[ConflictKey, Map[ConflictKey, Source]]):
            case (table, (before, after, source)) =>
              table + (before -> (table.getOrElse(before, VectorMap.empty) + (after -> source))),
      ).tap: table =>
        logger.toFile(show"$parserName/conflictResolutions.dbg", true)(table)
        logger.toFile(show"$parserName/conflictResolutions.mmd", true)(table.toMermaid)
        table.verifyNoConflicts()

      val root = table
        .collectFirst:
          case (p @ Production.NonEmpty(NonTerminal("root"), _, _, _), _) => p
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
          logger.toFile(s"$parserName/parseTable.dbg.csv", true)(parseTable.toCsv)
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
