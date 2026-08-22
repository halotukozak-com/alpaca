package alpaca
package lexer

import alpaca.core.*
import alpaca.lexer.RegexChecker
import alpaca.lexer.context.default.DefaultGlobalCtx
import alpaca.lexer.context.{AnyGlobalCtx, GlobalCtx, Lexem}

import scala.util.chaining.scalaUtilChainingOps
import scala.NamedTuple.NamedTuple
import scala.quoted.*
import scala.util.matching.Regex
import CompileNameAndPattern.Result
import java.util.regex.Pattern

type LexerDefinition[Ctx <: AnyGlobalCtx] = PartialFunction[String | Char, Token[?, Ctx, ?]]

transparent inline def lexer[Ctx <: AnyGlobalCtx & Product](
  using Ctx WithDefault DefaultGlobalCtx,
)(
  inline rules: Ctx ?=> LexerDefinition[Ctx],
)(using
  copy: Copyable[Ctx],
  betweenStages: BetweenStages[Ctx],
): Tokenization[Ctx] =
  ${ lexerImpl[Ctx]('{ rules }, '{ summon }, '{ summon }) }

private def lexerImpl[Ctx <: AnyGlobalCtx: Type](
  rules: Expr[Ctx ?=> LexerDefinition[Ctx]],
  copy: Expr[Copyable[Ctx]],
  betweenStages: Expr[BetweenStages[Ctx]],
)(using quotes: Quotes,
): Expr[Tokenization[Ctx]] = {
  import quotes.reflect.*

  type ThisToken = Token[?, Ctx, ?]

  def nameToString[Name <: ValidName: Type]: ValidName =
    TypeRepr.of[Name] match
      case ConstantType(StringConstant(str)) => str
      case x => raiseShouldNeverBeCalled(x.show)

  val compileNameAndPattern = new CompileNameAndPattern[quotes.type]
  val createLambda = new CreateLambda[quotes.type]

  val Lambda(oldCtx :: Nil, Lambda(_, Match(_, cases: List[CaseDef]))) = rules.asTerm.underlying.runtimeChecked
  val (tokens, infos) = cases.foldLeft((tokens = List.empty[Expr[ThisToken]], infos = List.empty[TokenInfo[?]])):
    case ((tokens, infos), CaseDef(tree, None, body)) =>
      def replaceWithNewCtx(newCtx: Term) = new ReplaceRefs[quotes.type].apply(
        (find = oldCtx.symbol, replace = newCtx),
        (find = tree.symbol, replace = Select.unique(newCtx, "lastRawMatched")),
      )

      def extractSimple(ctxManipulation: Expr[CtxManipulation[Ctx]])
        : PartialFunction[Expr[ThisToken], List[(Expr[ThisToken], Expr[TokenInfo[?]])]] =
        case '{ Token.Ignored(using $ctx) } =>
          compileNameAndPattern[Nothing](tree).map { case '{ $tokenInfo: TokenInfo[name] } =>
            '{ IgnoredToken[name, Ctx]($tokenInfo, $ctxManipulation) } -> tokenInfo
          }

        case '{ type t <: ValidName; Token.apply[t](using $ctx) } =>
          compileNameAndPattern[t](tree).map { case '{ $tokenInfo: TokenInfo[name] } =>
            '{ DefinedToken[name, Ctx, Unit]($tokenInfo, $ctxManipulation, _ => ()) } -> tokenInfo
          }

        case '{ type t <: ValidName; Token.apply[t]($value: String)(using $ctx) }
            if value.asTerm.symbol == tree.symbol =>

          compileNameAndPattern[t](tree).map { case '{ $tokenInfo: TokenInfo[name] } =>
            '{ DefinedToken[name, Ctx, String]($tokenInfo, $ctxManipulation, _.lastRawMatched) } -> tokenInfo
          }

        case '{ type t <: ValidName; Token.apply[t]($value: v)(using $ctx) } =>
          compileNameAndPattern[t](tree).map { case '{ $tokenInfo: TokenInfo[name] } =>
            // we need to widen here to avoid weird types
            TypeRepr.of[v].widen.asType match
              case '[result] =>
                val remapping = createLambda[Ctx => result] { case (methSym, (newCtx: Term) :: Nil) =>
                  replaceWithNewCtx(newCtx).transformTerm(value.asTerm)(methSym)
                }
                '{ DefinedToken[name, Ctx, result]($tokenInfo, $ctxManipulation, $remapping) } -> tokenInfo
          }

      val (newTokens, newInfos) = extractSimple('{ identity })
        .lift(body.asExprOf[ThisToken])
        .orElse {
          body match
            case Block(statements, expr) =>
              val ctxManipulation = createLambda[CtxManipulation[Ctx]] { case (methSym, (newCtx: Term) :: Nil) =>
                replaceWithNewCtx(newCtx).transformTerm(
                  Block(statements.map(_.changeOwner(methSym)), Literal(UnitConstant())),
                )(methSym)
              }

              extractSimple(ctxManipulation).lift(expr.asExprOf[ThisToken])
        }
        .getOrElse(raiseShouldNeverBeCalled(body.show))
        .unzip

      (tokens ::: newTokens, infos ::: newInfos.map(_.valueOrAbort))

    case (tokens, CaseDef(tree, Some(guard), body)) => report.errorAndAbort("Guards are not supported yet")

  RegexChecker.checkPatterns(infos.map(_.pattern)).foreach(report.errorAndAbort)

  val (definedTokens, ignoredTokens) = tokens.partition(_.isExprOf[DefinedToken[?, Ctx, ?]])

  def decls(cls: Symbol): List[Symbol] = {
    val tokenDecls = definedTokens.map { case '{ $token: DefinedToken[name, Ctx, value] } =>
      Symbol.newVal(
        parent = cls,
        name = nameToString[name],
        tpe = token.asTerm.tpe,
        flags = Flags.Synthetic,
        privateWithin = Symbol.noSymbol,
      )
    }

    val fieldTpe = definedTokens
      .foldLeft[(Type[? <: Tuple], Type[? <: Tuple])]((Type.of[EmptyTuple], Type.of[EmptyTuple])) {
        case (
              ('[type names <: Tuple; names], '[type types <: Tuple; types]),
              '{ $token: DefinedToken[name, Ctx, value] },
            ) =>
          (Type.of[name *: names], Type.of[Token[name, Ctx, value] *: types])
        case _ => raiseShouldNeverBeCalled()
      }
      .runtimeChecked match
      case ('[type names <: Tuple; names], '[type types <: Tuple; types]) => TypeRepr.of[NamedTuple[names, types]]

    val fieldsDecls = Symbol.newTypeAlias(
      parent = cls,
      name = "Fields",
      tpe = fieldTpe,
      flags = Flags.Synthetic,
      privateWithin = Symbol.noSymbol,
    )

    val compiled = Symbol.newVal(
      parent = cls,
      name = "compiled",
      tpe = TypeRepr.of[Regex],
      flags = Flags.Protected | Flags.Synthetic,
      privateWithin = Symbol.noSymbol,
    )

    val allTokens = Symbol.newVal(
      parent = cls,
      name = "tokens",
      tpe = TypeRepr.of[List[ThisToken]],
      flags = Flags.Synthetic | Flags.Override,
      privateWithin = Symbol.noSymbol,
    )

    val byName = Symbol.newVal(
      parent = cls,
      name = "byName",
      tpe = TypeRepr.of[Map[String, DefinedToken[?, Ctx, ?]]],
      flags = Flags.Synthetic | Flags.Lazy, // todo: reconsider lazy
      privateWithin = Symbol.noSymbol,
    )

    tokenDecls.toList ++ List(fieldsDecls, allTokens, byName)
  }

  val cls = Symbol.newClass(
    Symbol.spliceOwner,
    Symbol.freshName("$anon"),
    List(TypeRepr.of[Tokenization[Ctx]]),
    decls,
    None,
  )

  val body = {
    val tokenVals = definedTokens.collect { case '{ $token: DefinedToken[name, Ctx, value] } =>
      ValDef(cls.fieldMember(nameToString[name]), Some(token.asTerm.changeOwner(cls.fieldMember(nameToString[name]))))
    }

    tokenVals ++ Vector(
      TypeDef(cls.typeMember("Fields")),
      // ValDef(
      //   cls.fieldMember("compiled"),
      //   Some {
      //     val pattern = Expr {
      //       infos
      //         .map { case TokenInfo(_, regexGroupName, pattern) => s"(?<$regexGroupName>$pattern)" }
      //         .mkString("|")
      //         .tap(Pattern.compile(_)) // compile-time check for regex validity
      //     }

      //     '{ new Regex($pattern) }.asTerm.changeOwner(cls.fieldMember("compiled"))
      //   },
      // ),
      ValDef(
        cls.fieldMember("tokens"),
        Some {
          val declaredTokens = definedTokens.map { case '{ $token: DefinedToken[name, Ctx, ?] } =>
            This(cls).select(cls.fieldMember(nameToString[name])).asExprOf[ThisToken]
          }

          Expr.ofList(ignoredTokens ++ declaredTokens).asTerm.changeOwner(cls.fieldMember("tokens"))
        },
      ),
      ValDef(
        cls.fieldMember("byName"),
        Some {
          val all = Expr.ofSeq {
            tokenVals.map(valDef => Expr.ofTuple((Expr(valDef.name), Ref(valDef.symbol).asExprOf[DefinedToken[?, Ctx, ?]])))
          }

          '{ Map($all*) }.asTerm
        },
      ),
    )
  }.toList

  val tokenizationConstructor = TypeRepr.of[Tokenization[Ctx]].typeSymbol.primaryConstructor

  val parents =
    New(TypeTree.of[Tokenization[Ctx]])
      .select(tokenizationConstructor)
      .appliedToType(TypeRepr.of[Ctx])
      .appliedToArgs(List(copy.asTerm, betweenStages.asTerm)) :: Nil

  val clsDef = ClassDef(cls, parents, body)

  definedTokens
    .foldLeft(TypeRepr.of[Tokenization[Ctx]]) {
      case (tpe, '{ $token: DefinedToken[name, Ctx, value] }) =>
        Refinement(tpe, nameToString[name], token.asTerm.tpe)
      case _ => raiseShouldNeverBeCalled()
    }
    .asType match
    case '[refinedTpe] =>
      val newCls = Typed(New(TypeIdent(cls)).select(cls.primaryConstructor).appliedToNone, TypeTree.of[refinedTpe])

      Block(clsDef :: Nil, newCls).asExprOf[Tokenization[Ctx] & refinedTpe]
}
