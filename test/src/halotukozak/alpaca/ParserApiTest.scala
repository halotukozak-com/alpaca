package halotukozak
package alpaca

import halotukozak.alpaca.internal.lexer.Lexeme
import halotukozak.alpaca.internal.parser.Parser
import halotukozak.alpaca.{ctx, lexer, resolutions, rule, ParserCtx, Production, Resolutions, Rule, Token}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

final class ParserApiTest extends AnyFunSuite with Matchers:
  type R = Unit | Int | List[Int] | (String, Option[List[Int]])

  val CalcLexer = lexer:
    case " " => Token.Ignored
    case "\\t" => Token.Ignored
    case id @ "[a-zA-Z_][a-zA-Z0-9_]*" => Token["ID"](id)
    case "\\+" => Token["PLUS"]
    case "-" => Token["MINUS"]
    case "\\*" => Token["TIMES"]
    case "/" => Token["DIVIDE"]
    case "=" => Token["ASSIGN"]
    case "," => Token["COMMA"]
    case parenthesis @ ("\\(" | "\\)") => Token[parenthesis.type]
    case number @ "\\d+" => Token["NUMBER"](number.toInt)
    case "#.*" => Token.Ignored
    case newline @ "\n+" =>
      ctx.line = Line(ctx.line + newline.count(_ == '\n'))
      Token.Ignored

  case class CalcContext(
    names: mutable.Map[String, Int] = mutable.Map.empty,
    errors: mutable.ListBuffer[(tpe: String, value: Any, line: Int)] = mutable.ListBuffer.empty,
  ) extends ParserCtx

  object CalcApiParser extends Parser[CalcContext]:
    val Expr: Rule[Int] = rule(
      "plus" { case (Expr(expr1), CalcLexer.PLUS(_), Expr(expr2)) => expr1 + expr2 },
      "minus" { case (Expr(expr1), CalcLexer.MINUS(_), Expr(expr2)) => expr1 - expr2 },
      { case (Expr(expr1), CalcLexer.TIMES(_), Expr(expr2)) => expr1 * expr2 },
      { case (Expr(expr1), CalcLexer.DIVIDE(_), Expr(expr2)) => expr1 / expr2 },
      { case (CalcLexer.MINUS(_), Expr(expr)) => -expr },
      { case (CalcLexer.`\\(`(_), Expr(expr), CalcLexer.`\\)`(_)) => expr },
      { case CalcLexer.NUMBER(expr) => expr.value },
      { case CalcLexer.ID(id) =>
        ctx.names.getOrElse(
          id.value, {
            ctx.errors.append(("undefined", id, id.line));
            0
          },
        )
      },
    )
    val ArgList: Rule[List[Int]] = rule(
      { case (Expr(expr), CalcLexer.COMMA(_), ArgList(exprs)) => expr :: exprs },
      { case Expr(expr) => expr :: Nil },
    )
    val Statement: Rule[R] = rule(
      { case (CalcLexer.ID(id), CalcLexer.ASSIGN(_), Expr(expr)) => ctx.names(id.value) = expr },
      { case (CalcLexer.ID(id), CalcLexer.`\\(`(_), ArgList.Option(argList), CalcLexer.`\\)`(_)) =>
        (id.value, argList)
      },
      { case Expr(expr) => expr },
    )
    val root = rule:
      case Statement(stmt) => stmt

  given Resolutions[CalcApiParser.type] = resolutions(
    Production(CalcLexer.MINUS, CalcApiParser.Expr)
      .before(CalcLexer.DIVIDE, CalcLexer.TIMES, CalcLexer.PLUS, CalcLexer.MINUS),
    Production(CalcApiParser.Expr, CalcLexer.DIVIDE, CalcApiParser.Expr)
      .before(CalcLexer.DIVIDE, CalcLexer.TIMES, CalcLexer.PLUS, CalcLexer.MINUS),
    Production(CalcApiParser.Expr, CalcLexer.TIMES, CalcApiParser.Expr)
      .before(CalcLexer.DIVIDE, CalcLexer.TIMES, CalcLexer.PLUS, CalcLexer.MINUS),
    production.plus.before(CalcLexer.PLUS, CalcLexer.MINUS),
    production.plus.after(CalcLexer.TIMES, CalcLexer.DIVIDE),
    production.minus.before(CalcLexer.PLUS, CalcLexer.MINUS),
    production.minus.after(CalcLexer.TIMES, CalcLexer.DIVIDE),
  )

  test("basic recognition of various tokens and literals") {
    CalcApiParser.parse(CalcLexer.tokenize("a = 3 + 4 * (5 + 6)").getOrThrow) should matchPattern:
      case Result.Success(ctx: CalcContext, _) if ctx.names("a") == 47 =>

    CalcApiParser.parse(CalcLexer.tokenize("3 + 4 * (5 + 6)").getOrThrow) should matchPattern:
      case Result.Success(_, 47) =>
  }

  test("ebnf") {
    CalcApiParser.parse(CalcLexer.tokenize("a()").getOrThrow) should matchPattern:
      case Result.Success(_, ("a", None)) =>

    CalcApiParser.parse(CalcLexer.tokenize("a(2+3)").getOrThrow) should matchPattern:
      case Result.Success(_, ("a", Some(Seq(5)))) =>

    CalcApiParser.parse(CalcLexer.tokenize("a(2+3,4+5)").getOrThrow) should matchPattern:
      case Result.Success(_, ("a", Some(Seq(5, 9)))) =>
  }

  test("ebnf Option on a token") {
    object TokenOptionParser extends Parser[CalcContext]:
      val root = rule:
        case (CalcLexer.NUMBER.Option(number), CalcLexer.ID(id)) => (id.value, number.map(_.value))

    TokenOptionParser.parse(CalcLexer.tokenize("a").getOrThrow) should matchPattern:
      case Result.Success(_, ("a", None)) =>

    TokenOptionParser.parse(CalcLexer.tokenize("1 a").getOrThrow) should matchPattern:
      case Result.Success(_, ("a", Some(1))) =>
  }

  test("ebnf Option on a token as the whole pattern") {
    object LoneTokenOptionParser extends Parser[CalcContext]:
      // returns values rather than lexemes: a rule typed by a lexeme (`Rule[CalcLexer.NUMBER.LexemeTpe]`)
      // crashes the compiler's -Wsafe-init checker, independently of EBNF
      val root = rule:
        case CalcLexer.NUMBER.Option(number) => number.map(_.value)

    LoneTokenOptionParser.parse(CalcLexer.tokenize("").getOrThrow) should matchPattern:
      case Result.Success(_, None) =>

    LoneTokenOptionParser.parse(CalcLexer.tokenize("7").getOrThrow) should matchPattern:
      case Result.Success(_, Some(7)) =>
  }

  test("ebnf List on a token") {
    object TokenListParser extends Parser[CalcContext]:
      val root = rule:
        case (CalcLexer.NUMBER.List(numbers), CalcLexer.ID(id)) => (id.value, numbers.map(_.value))

    TokenListParser.parse(CalcLexer.tokenize("a").getOrThrow) should matchPattern:
      case Result.Success(_, ("a", Nil)) =>

    TokenListParser.parse(CalcLexer.tokenize("1 2 3 a").getOrThrow) should matchPattern:
      case Result.Success(_, ("a", List(1, 2, 3))) =>
  }

  test("ebnf SeparatedBy on a token") {
    object TokenSeparatedByParser extends Parser[CalcContext]:
      val root = rule:
        case (CalcLexer.`\\(`(_), CalcLexer.NUMBER.SeparatedBy[CalcLexer.COMMA](items), CalcLexer.`\\)`(_)) =>
          items.collect[Any] { case lexeme: Lexeme[?, ?] if lexeme.name == "NUMBER" => lexeme.value }

    TokenSeparatedByParser.parse(CalcLexer.tokenize("()").getOrThrow) should matchPattern:
      case Result.Success(_, Nil) =>

    TokenSeparatedByParser.parse(CalcLexer.tokenize("(1, 2, 3)").getOrThrow) should matchPattern:
      case Result.Success(_, List(1, 2, 3)) =>
  }

  test("api") {
    object ApiParser extends Parser[CalcContext]:
      val Num = rule:
        case CalcLexer.NUMBER(n) => n.value

      val root = rule:
        case (Num(n), CalcLexer.COMMA(_), Num.Option(numOpt), CalcLexer.COMMA(_), Num.List(numList)) =>
          (n, numOpt, numList)

    ApiParser.parse(CalcLexer.tokenize("1,,").getOrThrow) should matchPattern:
      case Result.Success(_, (1, None, Nil)) =>

    ApiParser.parse(CalcLexer.tokenize("1,2,").getOrThrow) should matchPattern:
      case Result.Success(_, (1, Some(2), Nil)) =>

    ApiParser.parse(CalcLexer.tokenize("1,2,1 2 3").getOrThrow) should matchPattern:
      case Result.Success(_, (1, Some(2), List(1, 2, 3))) =>

    ApiParser.parse(CalcLexer.tokenize("1,,3").getOrThrow) should matchPattern:
      case Result.Success(_, (1, None, List(3))) =>
  }

  test("a rule can return a lexeme") {
    // a parser local to a method used to crash the compiler's -Wsafe-init checker on the lexeme's type (#605)
    object LexemeParser extends Parser[CalcContext]:
      val root = rule:
        case CalcLexer.NUMBER(n) => n

    LexemeParser.parse(CalcLexer.tokenize("42").getOrThrow).getOrThrow should matchPattern:
      case lexeme: Lexeme[?, ?] if lexeme.value == 42 && lexeme.text == "42" =>
  }

  test("parse error") {
    CalcApiParser.parse(CalcLexer.tokenize("a 123 4 + 5").getOrThrow) match
      case Result.Failure(_, None, ParserError(unexpected, expected) :: Nil) =>
        (unexpected.name, unexpected.value) shouldBe ("NUMBER", 123)
        expected shouldBe List("$", "ASSIGN", "DIVIDE", "MINUS", "PLUS", "TIMES", "\\(")
      case _ => fail("expected a single parse error")
  }
