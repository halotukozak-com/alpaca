package halotukozak
package alpaca
package internal
package lexer

import alpaca.Token as TokenDef
import halotukozak.regex.{Regex, RegexParser, Subset, TokenMatcher}

import scala.NamedTuple.{AnyNamedTuple, NamedTuple}
import scala.annotation.{publicInBinary, switch}
import scala.reflect.NameTransformer

// $COVERAGE-OFF$
def lexerImpl[Ctx <: LexerCtx: Type, lexemeFields <: AnyNamedTuple: Type](
  rules: Expr[Ctx ?=> LexerDefinition[Ctx]],
  onTokenMatch: Expr[(Token[?, Ctx, ?], String, Ctx) => Ctx],
  errorHandling: Expr[ErrorHandling[Ctx]],
  empty: Expr[Empty[Ctx]],
)(using quotes: Quotes,
): Expr[Tokenization[Ctx] { type LexemeFields = lexemeFields }] =
  import quotes.reflect.*

  type TokenRefn = lexer.Token[?, Ctx, ?] { type LexemeTpe = Lexeme[?, ?] withFields lexemeFields }

  val Lambda(oldCtx :: Nil, Lambda(_, Match(_, cases: List[CaseDef]))) = rules.asTerm.underlying.runtimeChecked

  if cases.isEmpty then report.errorAndAbort("Lexer definition must contain at least one case")

  val tokens = cases.foldLeft(
    List.empty[(info: TokenInfo, expr: Expr[lexer.Token[?, Ctx, ?] & TokenRefn])],
  ):
    case (acc, CaseDef(tree, None, body)) =>
      def replaceWithNewCtx(newCtx: Term) = replaceRefs(
        (find = oldCtx.symbol, replace = newCtx),
        (find = tree.symbol, replace = '{ ${ newCtx.asExprOf[Ctx] }.lastRawMatched }.asTerm),
      )

      def extractSimple(ctxManipulation: Expr[CtxManipulation[Ctx]]): PartialFunction[
        Expr[TokenDef[ValidName, Ctx, Any]],
        List[(info: TokenInfo, expr: Expr[lexer.Token[?, Ctx, ?]])],
      ] =
        case '{ Token.Ignored(using $_) } =>
          compileNameAndPattern[Nothing](tree).map:
            case ('[type name <: ValidName; name], tokenInfo) =>
              (info = tokenInfo, expr = '{ IgnoredToken[name, Ctx](${ Expr(tokenInfo) }, $ctxManipulation) })
            case other =>
              raiseShouldNeverBeCalled(other)

        case '{ type name <: ValidName; Token[name](using $_) } =>
          compileNameAndPattern[name](tree).map:
            case ('[type name <: ValidName; name], tokenInfo) =>
              (
                info = tokenInfo,
                expr = '{ DefinedToken[name, Ctx, Unit](${ Expr(tokenInfo) }, $ctxManipulation, _ => ()) },
              )
            case other =>
              raiseShouldNeverBeCalled(other)

        case '{ type name <: ValidName; Token[name]($value: String)(using $_) } if value.asTerm.symbol == tree.symbol =>
          compileNameAndPattern[name](tree).map:
            case ('[type name <: ValidName; name], tokenInfo) =>
              (
                info = tokenInfo,
                expr = '{ DefinedToken[name, Ctx, String](${ Expr(tokenInfo) }, $ctxManipulation, _.lastRawMatched) },
              )
            case other =>
              raiseShouldNeverBeCalled(other)

        case '{ type name <: ValidName; Token[name]($value: value)(using $_) } =>
          compileNameAndPattern[name](tree).map:
            case ('[type name <: ValidName; name], tokenInfo) =>
              // we need to widen here to avoid weird types
              TypeRepr.of[value].widen.asType match
                case '[result] =>
                  val remapping = createLambda[Ctx => result]:
                    case (methSym, (newCtx: Term) :: Nil) =>
                      val withNewCtx = replaceWithNewCtx(newCtx).transformTerm(value.asTerm)(methSym)
                      rewriteCtxMutations(newCtx.symbol)(withNewCtx)(methSym)
                  (
                    info = tokenInfo,
                    expr = '{ DefinedToken[name, Ctx, result](${ Expr(tokenInfo) }, $ctxManipulation, $remapping) },
                  )
            case (_, tokenInfo) =>
              raiseShouldNeverBeCalled[(info: TokenInfo, expr: Expr[lexer.Token[?, Ctx, ?]])](tokenInfo)

      val pairs = extractSimple('{ (c: Ctx) => c })
        .lift(body.asExprOf[TokenDef[ValidName, Ctx, Any]])
        .orElse:
          body match
            case Block(statements, expr) =>
              val ctxManipulation = createLambda[CtxManipulation[Ctx]]:
                case (methSym, (newCtx: Term) :: Nil) =>
                  val ctxVar = Symbol.newVal(methSym, "$ctx", TypeRepr.of[Ctx], Flags.Mutable, Symbol.noSymbol)
                  val withNewCtx = replaceWithNewCtx(Ref(ctxVar)).transformTerm(
                    Block(statements.map(_.changeOwner(methSym)), Literal(UnitConstant())),
                  )(methSym)
                  val rewritten = rewriteCtxMutations(ctxVar)(withNewCtx)(methSym)
                  Block(List(ValDef(ctxVar, Some(newCtx))), Block(List(rewritten), Ref(ctxVar)))

              extractSimple(ctxManipulation).lift(expr.asExprOf[TokenDef[ValidName, Ctx, Any]])
        .getOrElse:
          raiseShouldNeverBeCalled[List[(info: TokenInfo, expr: Expr[lexer.Token[?, Ctx, ?]])]](body)

      acc ::: pairs.map:
        case (info, expr) =>
          expr match
            case '{ type tokenTpe <: lexer.Token[?, Ctx, ?]; $token: tokenTpe } =>
              (info = info, expr = '{ $token.asInstanceOf[tokenTpe & TokenRefn] })

    case (_, CaseDef(_, Some(_), body)) => report.errorAndAbort("Guards are not supported yet")

  tokens
    .map(_.info)
    .groupBy(_.name)
    .iterator
    .filter(_._2.sizeIs > 1)
    .foreach: (name, duplicates) =>
      report.errorAndAbort(
        show"Token name \"$name\" is defined ${duplicates.size.toString} times. Combine the patterns into a single case using alternatives, e.g.: case x @ (\"pattern1\" | \"pattern2\") => Token[x]",
      )

  val parsedRegexes = tokens.map: token =>
    RegexParser.parse(token.info.pattern) match
      case Right(regex) => regex
      case Left(err) => report.errorAndAbort(err.toString)

  SubsetChecker.checkRegexes(
    for (token, regex) <- tokens.zip(parsedRegexes)
    yield (token.info.pattern, Subset.of(regex).withAnySuffix),
  )

  // Symbol.spliceOwner is a synthetic "macro" method dotty introduces to host the transparent
  // inline def's expansion; the val this `lexer{...}` call is actually bound to is one owner hop
  // further up.
  JsonExport.maybeWrite(exportId(declaredName(Symbol.spliceOwner.owner)), "tokens", tokens.map(_.info))

  val fields = tokens.map(t => (t.info.name, t.expr.asTerm.tpe))
  val types = Refined(
    TypeTree.of[Any],
    fields.map: (name, tpe) =>
      TypeDef(Symbol.newTypeAlias(Symbol.spliceOwner, name, Flags.EmptyFlags, tpe, Symbol.noSymbol)),
    defn.AnyClass,
  ).tpe

  def selectDynamicImpl(fieldName: Expr[String])(using Quotes) = Match(
    '{ $fieldName: @switch }.asTerm,
    tokens.map: t =>
      CaseDef(Literal(StringConstant(NameTransformer.encode(t.info.name))), None, t.expr.asTerm),
  ).asExprOf[lexer.Token[?, Ctx, ?]]

  (refinementTpeFrom(fields).asType, fieldsTpeFrom(fields).asType, types.asType).runtimeChecked match
    case ('[refinedTpe], '[fields], '[types]) =>
      val tokensExpr = Expr.ofList(tokens.map(_.expr))
      val matcherExpr = '{ TokenMatcher.fromRegexes(${ Varargs(parsedRegexes.map(Expr(_))) }*) }

      '{
        {
          new Tokenization[Ctx]($onTokenMatch)(using $errorHandling, $empty):
            @publicInBinary
            override private[alpaca] val tokens: List[lexer.Token[?, Ctx, ?]] = $tokensExpr

            override def selectDynamic(name: String): lexer.Token[?, Ctx, ?] = ${ selectDynamicImpl('{ name }) }

            override protected val matcher: TokenMatcher = $matcherExpr
        }.asInstanceOf[Tokenization[Ctx] { type LexemeFields = lexemeFields; type Fields = fields } & refinedTpe & types]
      }
// $COVERAGE-ON$
