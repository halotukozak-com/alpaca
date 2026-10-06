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
 * @return a list of TokenInfo expressions, each paired with its already-parsed [[Regex]], or `None` if it is invalid
 */
private[lexer] type CompiledPattern = (Type[? <: ValidName], TokenInfo, Option[Regex])

private[lexer] def compileNameAndPattern[T: Type](
  using quotes: Quotes,
)(
  pattern: quotes.reflect.Tree,
): List[CompiledPattern] = {
  import quotes.reflect.*
  // T is Nothing exactly when compiling a `Token.Ignored` case (see the two Nothing-guarded
  // branches below); every other call site passes the token's own name as T.
  val ignored = TypeRepr.of[T] =:= TypeRepr.of[Nothing]

  @tailrec def loop(tpe: TypeRepr, pattern: Tree): List[CompiledPattern] = (tpe, pattern) match {
    // case x @ "regex" => Token[x.type]
    case (TermRef(_, name), Bind(bind, Literal(StringConstant(regex)))) if name == bind =>
      TokenInfo(regex, regex :: Nil, ignored, pattern.pos) :: Nil
    // case x @ ("regex" | "regex2") => Token[x.type]
    case (TermRef(_, name), Bind(bind, Alternatives(alternatives))) if name == bind =>
      alternatives.map:
        case Literal(StringConstant(str)) => TokenInfo(str, str :: Nil, ignored, pattern.pos)
        case other => raiseShouldNeverBeCalled(other)
    // case x @ <?> => Token[<?>]
    case (tpe, Bind(_, tree)) =>
      loop(tpe, tree)
    // case x : "regex" => Token.Ignored
    case (tpe, Literal(StringConstant(str))) if tpe =:= TypeRepr.of[Nothing] =>
      TokenInfo(str, str :: Nil, ignored, pattern.pos) :: Nil
    // case x : ("regex" | "regex2") => Token.Ignored
    case (tpe, Alternatives(alternatives)) if tpe =:= TypeRepr.of[Nothing] =>
      alternatives.map:
        case Literal(StringConstant(str)) => TokenInfo(str, str :: Nil, ignored, pattern.pos)
        case other => raiseShouldNeverBeCalled(other)
    // case x : "regex" => Token["name"]
    case (ConstantType(StringConstant(name)), Literal(StringConstant(regex))) =>
      TokenInfo(name, regex :: Nil, ignored, pattern.pos) :: Nil
    // case x : ("regex" | "regex2") => Token["name"]
    case (ConstantType(StringConstant(str)), Alternatives(alternatives)) =>
      val patterns = alternatives.map:
        case Literal(StringConstant(str)) => str
        case other => raiseShouldNeverBeCalled[String](other)
      // Alternatives are merged into a single regex and matched via longest-match, not
      // priority order, so unlike cross-case shadowing (Lexer.scala) there's no "earlier wins"
      // relationship to check here: one alternative being a prefix of another (e.g. ">" and ">=")
      // is normal and both remain reachable.
      TokenInfo(str, patterns, ignored, pattern.pos) :: Nil
    case x => raiseShouldNeverBeCalled[List[CompiledPattern]](x.toString)
  }

  loop(TypeRepr.of[T], pattern)
}
// $COVERAGE-ON$
