package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.internal.ValidName
import halotukozak.regex.Regex

import scala.annotation.tailrec

// $COVERAGE-OFF$

/**
 * Compiles a pattern tree into token information during macro expansion.
 *
 * Extracts the token name and regex pattern from various forms of
 * pattern matching trees in lexer definitions, handling simple patterns,
 * alternatives, and bindings.
 *
 * @tparam T the type of the pattern
 * @param pattern the pattern tree to compile
 * @return a list of TokenInfo expressions, each paired with its already-parsed [[Regex]], or `None` if it is invalid,
 *         and where it is defined
 */
private[lexer] type CompiledPattern = (Type[? <: ValidName], TokenInfo, Option[Regex], Source)

private[lexer] def compileNameAndPattern[T: Type](
  using Quotes,
  Diagnostics,
)(
  pattern: quotes.reflect.Tree,
): List[CompiledPattern] = {
  import quotes.reflect.*
  // T is Nothing exactly when compiling a `Token.Ignored` case (see the two Nothing-guarded
  // branches below); every other call site passes the token's own name as T.
  val ignored = TypeRepr.of[T] =:= TypeRepr.of[Nothing]

  // reported without aborting, like an invalid regex, so the lexer stays typed from its other cases;
  // a rule with any rejected alternative contributes no patterns at all
  def literals(alternatives: List[Tree]): List[String] =
    alternatives.partitionMap {
      case Literal(StringConstant(str)) => Right(str)
      case other => Left(other)
    } match
      case (Nil, literals) => literals
      case (nonLiterals, _) =>
        nonLiterals.foreach: alternative =>
          error(show"Each alternative of a lexer rule must be a regex string literal", alternative.pos)
        Nil

  @tailrec def loop(tpe: TypeRepr, pattern: Tree): List[CompiledPattern] = (tpe, pattern) match {
    // case x @ "regex" => Token[x.type]
    case (TermRef(_, name), Bind(bind, Literal(StringConstant(regex)))) if name == bind =>
      TokenInfo(regex, regex :: Nil, ignored, pattern.pos) :: Nil
    // case x @ ("regex" | "regex2") => Token[x.type]
    case (TermRef(_, name), Bind(bind, Alternatives(alternatives))) if name == bind =>
      literals(alternatives).map(str => TokenInfo(str, str :: Nil, ignored, pattern.pos))
    // case x @ <?> => Token[<?>]
    case (tpe, Bind(_, tree)) =>
      loop(tpe, tree)
    // case x : "regex" => Token.Ignored
    case (tpe, Literal(StringConstant(str))) if tpe =:= TypeRepr.of[Nothing] =>
      TokenInfo(str, str :: Nil, ignored, pattern.pos) :: Nil
    // case x : ("regex" | "regex2") => Token.Ignored
    case (tpe, Alternatives(alternatives)) if tpe =:= TypeRepr.of[Nothing] =>
      literals(alternatives).map(str => TokenInfo(str, str :: Nil, ignored, pattern.pos))
    // case x : "regex" => Token["name"]
    case (ConstantType(StringConstant(name)), Literal(StringConstant(regex))) =>
      TokenInfo(name, regex :: Nil, ignored, pattern.pos) :: Nil
    // case x : ("regex" | "regex2") => Token["name"]
    case (ConstantType(StringConstant(str)), Alternatives(alternatives)) =>
      val patterns = literals(alternatives)
      // Alternatives are merged into a single regex and matched via longest-match, not
      // priority order, so unlike cross-case shadowing (Lexer.scala) there's no "earlier wins"
      // relationship to check here: one alternative being a prefix of another (e.g. ">" and ">=")
      // is normal and both remain reachable.
      if patterns.isEmpty then Nil else TokenInfo(str, patterns, ignored, pattern.pos) :: Nil
    case (_, Literal(StringConstant(_)) | Alternatives(_)) =>
      error(
        show"""A token name must be a string literal, as in `Token["NAME"]`, or the type of the bound match, as in `case x @ "regex" => Token[x.type]`""",
        pattern.pos,
      )
      Nil
    case _ =>
      error(
        show"""A lexer rule must match a regex string literal or alternatives of them, as in `case "[0-9]+"` or `case "a" | "b"`""",
        pattern.pos,
      )
      Nil
  }

  loop(TypeRepr.of[T], pattern)
}
// $COVERAGE-ON$
