package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.Token as TokenDef
import halotukozak.regex.{Regex, Subset, TokenMatcher}

import scala.NamedTuple.{AnyNamedTuple, NamedTuple}
import scala.annotation.{publicInBinary, switch}
import scala.reflect.NameTransformer

// $COVERAGE-OFF$
def lexerImpl[Ctx <: LexerCtx: Type, lexemeFields <: AnyNamedTuple: Type](
  rules: Expr[Ctx ?=> LexerDefinition[Ctx]],
  onTokenMatch: Expr[(Token[?, Ctx, ?], String, Ctx) => Ctx],
  errorHandling: Expr[ErrorHandling[Ctx, LexerError]],
  empty: Expr[Empty[Ctx]],
)(using quotes: Quotes,
): Expr[Tokenization[Ctx] { type LexemeFields = lexemeFields }] = {
  import quotes.reflect.*

  val Lambda(oldCtx :: Nil, Lambda(_, Match(_, cases: List[CaseDef]))) = rules.asTerm.underlying.runtimeChecked

  if cases.isEmpty then errorAndAbort("Lexer definition must contain at least one case", rules.asTerm.pos)

  val tokens = cases.foldLeft(
    List.empty[(info: TokenInfo, expr: Expr[lexer.Token[?, Ctx, ?]], pos: Position, regex: Regex)],
  ):
    case (acc, CaseDef(tree, None, body)) =>
      def replaceWithNewCtx(newCtx: Term) = replaceRefs(
        (find = oldCtx.symbol, replace = newCtx),
        (find = tree.symbol, replace = '{ ${ newCtx.asExprOf[Ctx] }.lastRawMatched }.asTerm),
      )

      def extractSimple(ctxManipulation: Expr[CtxManipulation[Ctx]]): PartialFunction[
        Expr[TokenDef[ValidName, Ctx, Any]],
        List[(info: TokenInfo, expr: Expr[lexer.Token[?, Ctx, ?]], regex: Regex)],
      ] = {
        case '{ Token.Ignored(using $_) } =>
          compileNameAndPattern[Nothing](tree).map:
            case ('[type name <: ValidName; name], tokenInfo, regex) =>
              (
                info = tokenInfo,
                expr = '{ IgnoredToken[name, Ctx](${ Expr(tokenInfo) }, $ctxManipulation) },
                regex = regex,
              )
            case other =>
              raiseShouldNeverBeCalled(other)

        case '{ type name <: ValidName; Token[name](using $_) } =>
          compileNameAndPattern[name](tree).map:
            case ('[type name <: ValidName; name], tokenInfo, regex) =>
              (
                info = tokenInfo,
                expr = '{
                  DefinedToken[name, Ctx, Unit, Lexeme[name, Unit] withFields lexemeFields](
                    ${ Expr(tokenInfo) },
                    $ctxManipulation,
                    _ => (),
                  )
                },
                regex = regex,
              )
            case other =>
              raiseShouldNeverBeCalled(other)

        case '{ type name <: ValidName; Token[name]($value: String)(using $_) } if value.asTerm.symbol == tree.symbol =>
          compileNameAndPattern[name](tree).map:
            case ('[type name <: ValidName; name], tokenInfo, regex) =>
              (
                info = tokenInfo,
                expr = '{
                  DefinedToken[name, Ctx, String, Lexeme[name, String] withFields lexemeFields](
                    ${ Expr(tokenInfo) },
                    $ctxManipulation,
                    _.lastRawMatched,
                  )
                },
                regex = regex,
              )
            case other =>
              raiseShouldNeverBeCalled(other)

        case '{ type name <: ValidName; Token[name]($value: value)(using $_) } =>
          compileNameAndPattern[name](tree).map:
            case ('[type name <: ValidName; name], tokenInfo, regex) =>
              // we need to widen here to avoid weird types
              TypeRepr.of[value].widen.asType match
                case '[result] =>
                  val remapping = createLambda[Ctx => result]:
                    case (methSym, (newCtx: Term) :: Nil) =>
                      val withNewCtx = replaceWithNewCtx(newCtx).transformTerm(value.asTerm)(methSym)
                      rewriteCtxMutations(newCtx.symbol)(withNewCtx)(methSym)
                  (
                    info = tokenInfo,
                    expr = '{
                      DefinedToken[name, Ctx, result, Lexeme[name, result] withFields lexemeFields](
                        ${ Expr(tokenInfo) },
                        $ctxManipulation,
                        $remapping,
                      )
                    },
                    regex = regex,
                  )
            case (_, tokenInfo, _) =>
              raiseShouldNeverBeCalled[(info: TokenInfo, expr: Expr[lexer.Token[?, Ctx, ?]], regex: Regex)](tokenInfo)
      }

      val pairs = extractSimple('{ (c: Ctx) => c })
        .lift(body.asExprOf[TokenDef[ValidName, Ctx, Any]])
        .orElse:
          body match {
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
          }
        .getOrElse:
          raiseShouldNeverBeCalled[List[(info: TokenInfo, expr: Expr[lexer.Token[?, Ctx, ?]], regex: Regex)]](body)

      acc ::: pairs.map((info, expr, regex) => (info = info, expr = expr, pos = tree.pos, regex = regex))

    case (_, CaseDef(_, Some(guard), _)) => errorAndAbort("Guards are not supported yet", guard.pos)

  // A token name in quotes, readable even when it holds a tab or another non-printable character.
  def quote(name: String): String = "\"" + printable(name) + "\""

  // A token's regex as the Scala string literal the user would write in a `case`.
  def literal(token: (info: TokenInfo, expr: Expr[lexer.Token[?, Ctx, ?]], pos: Position, regex: Regex)): String =
    quote(token.info.pattern.replace("\\", "\\\\").replace("\"", "\\\""))

  tokens
    .groupBy(_.info.name)
    .iterator
    .filter(_._2.sizeIs > 1)
    .foreach: (name, duplicates) =>
      val alternatives = duplicates.map(literal).mkString(" | ")
      errorAndAbort(
        show"Token name ${quote(name)} is defined ${duplicates.size.toString} times. Combine the patterns into a single case using alternatives: case $alternatives => ...",
        duplicates(1).pos,
      )

  SubsetChecker
    .checkRegexes(tokens.map(token => (name = token.info.name, subset = Subset.of(token.regex))))
    .foreach: (first, second) =>
      val byName = tokens.map(token => token.info.name -> token).toMap
      val shadowed = byName(first)
      val quoted = second.map(quote)
      val covering = quoted.mkString(" or ")
      val (which, wins) =
        if second.sizeIs == 1 then ("which is", "it always wins") else ("which are", "one of them always wins")
      val advice = second match
        case List(only) if Subset.of(byName(only).regex).subset(Subset.of(shadowed.regex)) =>
          s"""${quote(only)} and ${quote(first)} match exactly the same inputs; remove one of them."""
        case List(only) =>
          s"""Declare ${quote(first)} (${literal(shadowed)}) before ${quote(only)} (${literal(byName(only))})."""
        case _ =>
          s"""${quote(first)} is redundant: remove it, or narrow ${quoted.mkString(
              " and ",
            )} so they no longer cover it."""
      errorAndAbort(
        s"""Token ${quote(first)} can never match: every input it matches is also matched by $covering,
           |$which defined earlier, so $wins.
           |$advice""".stripMargin,
        shadowed.pos,
      )

  // Symbol.spliceOwner is a synthetic "macro" method dotty introduces to host the transparent
  // inline def's expansion; the val this `lexer{...}` call is actually bound to is one owner hop
  // further up.
  JsonExport.maybeWrite(exportId(declaredName(Symbol.spliceOwner.owner)), "tokens", tokens.map(_.info))

  val fields = tokens.map(t => (t.info.name, t.expr.asTerm.tpe))
  val types = fields.foldLeft(TypeRepr.of[Any]):
    case (acc, (name, tpe)) =>
      val alias = tpe.asType match
        case '[t] =>
          TypeRepr.of[Any { type Alias = t }].runtimeChecked match
            case Refinement(_, _, alias) => alias
      Refinement(acc, name, alias)

  def selectDynamicImpl(fieldName: Expr[String])(using Quotes) = Match(
    '{ $fieldName: @switch }.asTerm,
    tokens.map: t =>
      CaseDef(Literal(StringConstant(NameTransformer.encode(t.info.name))), None, t.expr.asTerm),
  ).asExprOf[lexer.Token[?, Ctx, ?]]

  (refinementTpeFrom(fields).asType, fieldsTpeFrom(fields).asType, types.asType).runtimeChecked match {
    case ('[refinedTpe], '[fields], '[types]) =>
      val tokensExpr = Expr.ofList(tokens.map(_.expr))
      val matcherExpr = '{ TokenMatcher.fromRegexes(${ Varargs(tokens.map(t => Expr(t.regex))) }*) }

      '{
        {
          new Tokenization[Ctx]($onTokenMatch)(using $errorHandling, $empty):
            @publicInBinary
            override private[alpaca] val tokens: List[lexer.Token[?, Ctx, ?]] = $tokensExpr

            override def selectDynamic(name: String): lexer.Token[?, Ctx, ?] = ${ selectDynamicImpl('{ name }) }

            override protected val matcher: TokenMatcher = $matcherExpr
        }.asInstanceOf[Tokenization[Ctx] { type LexemeFields = lexemeFields; type Fields = fields } & refinedTpe & types]
      }
  }
}
// $COVERAGE-ON$
