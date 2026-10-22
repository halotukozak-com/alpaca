package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.Token as TokenDef
import halotukozak.regex.{Regex, Subset, TokenMatcher}

import scala.annotation.{publicInBinary, switch}
import scala.deriving.Mirror
import scala.quoted.*
import scala.quoted.QuotedFactoryGivens.given
import scala.reflect.NameTransformer

// $COVERAGE-OFF$
@publicInBinary private[alpaca] def createLexerImpl[Ctx <: LexerCtx: Type](
  rules: Expr[LexerScope.Of[Ctx] ?=> LexerDefinition[Ctx]],
  errorHandling: Expr[ErrorHandling[Ctx]],
)(using quotes: Quotes,
): Expr[Lexer[Ctx]] = {
  import quotes.reflect.*
  given diagnostics: Diagnostics = Diagnostics()
  val initialCtx = fromDefaults[Ctx]
  val mirror = Expr
    .summon[Mirror.ProductOf[Ctx]]
    .getOrElse(
      errorAndAbort(
        show"${Printable(TypeRepr.of[Ctx].typeSymbol.name)} should be a case class.",
        Position.ofMacroExpansion,
      ),
    )

  // a context field is read by name on lexemes and lexer errors, so a public member with its name, declared or
  // inherited from `Any`/`AnyRef` (`getClass`, `eq`, `synchronized`...), would shadow it
  val shadowingMembers = List(TypeRepr.of[Lexeme[?, ?]].typeSymbol, TypeRepr.of[alpaca.LexerError].typeSymbol)
    .flatMap: owner =>
      (owner.fieldMembers ++ owner.methodMembers)
        .filterNot(member =>
          member.flags.is(Flags.Private) || member.flags.is(Flags.Protected) || member.privateWithin.isDefined ||
            member.flags.is(Flags.Synthetic) || member.isClassConstructor,
        )
        .map(member => member.name -> show"${owner.name.showRaw}.${member.name.showRaw}")
    .distinctBy(_._1)
    .toMap

  for
    field <- TypeRepr.of[Ctx].typeSymbol.caseFields
    member <- shadowingMembers.get(field.name)
  do
    error(
      show"Context field `${Printable(field.name)}` clashes with `$member`; rename it",
      field.pos.getOrElse(Position.ofMacroExpansion),
    )

  val (oldScope, cases) = rules.asTerm match
    case ScopedLambda(oldScope, Lambda(_, Match(_, cases))) => (oldScope, cases)
    case other =>
      val definition = other match
        case ScopedLambda(_, body) => body
        case _ => other
      errorAndAbort(
        show"Lexer rules must be a list of `case`s written directly in the `lexer` block, as in `case \"[0-9]+\" => Token[\"NUM\"]`",
        definition.pos,
      )

  // A token compiled from one case, with the case's position for error reporting.
  type CompiledRule = (info: TokenInfo, expr: Expr[lexer.Token[?, Ctx, ?]], regex: Option[Regex], pos: Position)

  val tokens = cases.foldLeft(List.empty[CompiledRule]):
    case (acc, CaseDef(tree, None, body)) =>
      // the bound match is the text the rule matched
      def boundMatch(newCtx: Term): PartialFunction[Symbol, Term] =
        case bound if !tree.symbol.isNoSymbol && bound == tree.symbol =>
          '{ ${ newCtx.asExprOf[Ctx] }.lastRawMatched }.asTerm

      def replaceWithNewCtx(newCtx: Term) = replaceScope[Ctx](oldScope.symbol, newCtx)(boundMatch(newCtx))

      // whether `term` refers to the context or to the bound match, i.e. whether its remapping needs the new context
      def readsCtx(term: Term): Boolean =
        val syms = Set(oldScope.symbol, tree.symbol).filterNot(_.isNoSymbol)
        new TreeAccumulator[Boolean]:
          def foldTree(found: Boolean, t: Tree)(owner: Symbol): Boolean =
            found || syms.contains(t.symbol) || foldOverTree(found, t)(owner)
        .foldTree(false, term)(Symbol.spliceOwner)

      def extractSimple(ctxManipulation: Expr[CtxManipulation[Ctx]]): PartialFunction[
        Expr[TokenDef[ValidName, Ctx, Any]],
        List[CompiledRule],
      ] = {
        case '{ Token.Ignored(using $_) } =>
          compileNameAndPattern[Nothing](tree).map:
            case ('[type name <: ValidName; name], tokenInfo, regex) =>
              (
                info = tokenInfo,
                expr = '{ IgnoredToken[name, Ctx](${ Expr(tokenInfo) }, $ctxManipulation) },
                regex = regex,
                pos = tree.pos,
              )
            case other =>
              raiseShouldNeverBeCalled(other.toTuple)

        case '{ type name <: ValidName; Token[name](using $_) } =>
          compileNameAndPattern[name](tree).map:
            case ('[type name <: ValidName; name], tokenInfo, regex) =>
              (
                info = tokenInfo,
                expr = '{
                  DefinedToken[name, Ctx, Unit, Lexeme[name, Unit] withFields NamedTuple.From[Ctx]](
                    ${ Expr(tokenInfo) },
                    $ctxManipulation,
                    discard,
                  )
                },
                regex = regex,
                pos = tree.pos,
              )
            case other =>
              raiseShouldNeverBeCalled(other.toTuple)

        case '{ type name <: ValidName; Token[name]($value: String)(using $_) } if value.asTerm.symbol == tree.symbol =>
          compileNameAndPattern[name](tree).map:
            case ('[type name <: ValidName; name], tokenInfo, regex) =>
              (
                info = tokenInfo,
                expr = '{
                  DefinedToken[name, Ctx, String, Lexeme[name, String] withFields NamedTuple.From[Ctx]](
                    ${ Expr(tokenInfo) },
                    $ctxManipulation,
                    rawMatched,
                  )
                },
                regex = regex,
                pos = tree.pos,
              )
            case other =>
              raiseShouldNeverBeCalled(other.toTuple)

        case '{ type name <: ValidName; Token[name]($value: value)(using $_) } =>
          compileNameAndPattern[name](tree).map:
            case ('[type name <: ValidName; name], tokenInfo, regex) =>
              // we need to widen here to avoid weird types
              TypeRepr.of[value].widen.asType match
                case '[result] =>
                  val remapping =
                    if readsCtx(value.asTerm) then
                      createLambda[Ctx => result]:
                        case (methSym, (newCtx: Term) :: Nil) =>
                          val withNewCtx = replaceWithNewCtx(newCtx).transformTerm(value.asTerm)(methSym)
                          rewriteCtxMutations(newCtx.symbol)(withNewCtx)(methSym)
                    // a value that ignores the context, e.g. `Token["Null"](null)`, gets a wildcard parameter,
                    // otherwise -Wunused:explicits reports the unused one at the user's call site
                    else '{ (_: Ctx) => ${ value.asTerm.changeOwner(Symbol.spliceOwner).asExprOf[result] } }
                  (
                    info = tokenInfo,
                    expr = '{
                      DefinedToken[name, Ctx, result, Lexeme[name, result] withFields NamedTuple.From[Ctx]](
                        ${ Expr(tokenInfo) },
                        $ctxManipulation,
                        $remapping,
                      )
                    },
                    regex = regex,
                    pos = tree.pos,
                  )
            case (_, tokenInfo, _) =>
              raiseShouldNeverBeCalled[CompiledRule](tokenInfo)
      }

      val pairs: List[CompiledRule] = extractSimple('{ unchangedCtx[Ctx] })
        .lift(body.asExprOf[TokenDef[ValidName, Ctx, Any]])
        .orElse:
          body match {
            case Block(statements, expr) =>
              val ctxManipulation = createLambda[CtxManipulation[Ctx]]:
                case (methSym, (newCtx: Term) :: Nil) =>
                  threadCtx[Ctx](
                    methSym,
                    newCtx,
                    oldScope.symbol,
                    Block(statements, Literal(UnitConstant())),
                    boundMatch(newCtx),
                  ): (body, ctx) =>
                    Block(List(body), ctx.getOrElse(Ref(newCtx.symbol)))

              extractSimple(ctxManipulation).lift(expr.asExprOf[TokenDef[ValidName, Ctx, Any]])
            case _ => None
          }
        .getOrElse:
          error(
            show"A lexer rule must end with `Token[\"NAME\"]`, `Token[\"NAME\"](value)` or `Token.Ignored`, written directly as its last expression",
            body.pos,
          )

      acc ::: pairs

    case (acc, CaseDef(_, Some(guard), _)) =>
      error(show"Guards are not supported yet", guard.pos)
      acc

  // A token name in quotes, readable even when it holds a tab or another non-printable character.
  def quote(name: Printable): Shown = show"\"$name\""

  // A token's regex as the Scala string literal the user would write in a `case`.
  def literal(info: TokenInfo): Shown =
    quote(Printable(info.pattern.raw.replace("\\", "\\\\").replace("\"", "\\\"")))

  // every duplicated name, in the order of its first definition
  val duplicated = tokens.duplicatesBy(_.info.name)
  duplicated.foreach: duplicates =>
    val alternatives = duplicates.map(token => literal(token.info)).mkShow(" | ")
    error(
      show"Token name ${quote(duplicates.head.info.name)} is defined ${duplicates.size} times. Combine the patterns into a single case using alternatives: case $alternatives => ...",
      duplicates(1).pos,
    )
  if duplicated.nonEmpty then abortOnErrors()

  val parsed = tokens.flatMap(token => token.regex.map(regex => (info = token.info, pos = token.pos, regex = regex)))

  val shadowing = SubsetChecker.checkRegexes(parsed.map(p => (name = p.info.name, subset = Subset.of(p.regex))))
  val byName = parsed.map(p => p.info.name -> p).toMap
  shadowing.foreach: (first, second) =>
    val shadowed = byName(first)
    val quoted = second.map(quote)
    val covering = quoted.mkShow(" or ")
    val (which, wins) =
      if second.sizeIs == 1 then (show"which is", show"it always wins")
      else (show"which are", show"one of them always wins")
    val advice = second match
      case List(only) if Subset.of(byName(only).regex).subset(Subset.of(shadowed.regex)) =>
        show"""${quote(only)} and ${quote(first)} match exactly the same inputs; remove one of them."""
      case List(only) =>
        val earlier = byName(only).info
        show"""Declare ${quote(first)} (${literal(shadowed.info)}) before ${quote(only)} (${literal(earlier)})."""
      case _ =>
        val coveringAll = quoted.mkShow(" and ")
        show"""${quote(first)} is redundant: remove it, or narrow $coveringAll so they no longer cover it."""
    error(
      show"""Token ${quote(first)} can never match: every input it matches is also matched by $covering,
           |$which defined earlier, so $wins.
           |$advice""".trimMargin,
      shadowed.pos,
    )

  // Symbol.spliceOwner is a synthetic "macro" method dotty introduces to host the transparent
  // inline def's expansion; the val this `lexer{...}` call is actually bound to is one owner hop
  // further up. Errors above are reported without aborting, so the lexer stays typed from all of its cases
  // and doesn't cascade; they only keep a partial grammar from being exported.
  if !diagnostics.hasErrors then
    JsonExport.maybeWrite(
      exportId(declaredName(Symbol.spliceOwner.owner)),
      "tokens",
      tokens.map(t => (info = t.info, source = Source(t.pos))),
    )

  val fields = tokens.map(t => (t.info.name.raw, t.expr.asTerm.tpe))
  val types = fields.foldLeft(TypeRepr.of[Any]):
    case (acc, (name, tpe)) =>
      val alias = tpe.asType match
        case '[t] =>
          TypeRepr.of[Any { type Alias = t }].runtimeChecked match
            case Refinement(_, _, alias) => alias
      Refinement(acc, name, alias)

  // Maps a token name to its index in `tokens`, so `selectDynamic` returns the token built once there.
  def tokenIndexImpl(fieldName: Expr[String])(using Quotes) = Match(
    '{ $fieldName: @switch }.asTerm,
    tokens.zipWithIndex.map((token, index) =>
      CaseDef(Literal(StringConstant(NameTransformer.encode(token.info.name.raw))), None, Literal(IntConstant(index))),
    ) :+ CaseDef(Wildcard(), None, '{ throw new NoSuchElementException(s"No token named \"${$fieldName}\"") }.asTerm),
  ).asExprOf[Int]

  (refinementTpeFrom(fields).asType, fieldsTpeFrom(fields).asType, types.asType).runtimeChecked match {
    case ('[refinedTpe], '[fields], '[types]) =>
      val tokensExpr = '{ IArray[lexer.Token[?, Ctx, ?]](${ Varargs(tokens.map(_.expr)) }*) }
      val matcherExpr = '{ TokenMatcher.fromRegexes(${ Varargs(parsed.map(p => Expr(p.regex))) }*) }

      '{
        {
          // built before the lexer, as a constructor argument can't refer to the lexer being built
          val tokenMatch = Tracking.materialize[Ctx](using $mirror)
          new Lexer[Ctx](tokenMatch, $initialCtx)(using $errorHandling):
            @publicInBinary
            override private[alpaca] val tokens: IArray[lexer.Token[?, Ctx, ?]] = $tokensExpr

            override def selectDynamic(name: String): lexer.Token[?, Ctx, ?] =
              tokens(${ tokenIndexImpl('{ name }) })

            @publicInBinary
            override private[alpaca] val matcher: TokenMatcher = $matcherExpr
        }.asInstanceOf[Lexer[Ctx] { type Fields = fields } & refinedTpe & types]
      }
  }
}
// $COVERAGE-ON$
