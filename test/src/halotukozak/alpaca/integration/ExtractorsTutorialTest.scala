package halotukozak
package alpaca
package integration

import halotukozak.alpaca.internal.lexer.Lexeme
import halotukozak.alpaca.internal.parser.Parser
import halotukozak.alpaca.{lexer, resolutions, rule, Resolutions, Rule, Token}
import org.scalatest.funsuite.AnyFunSuite

/** Validates the extractors tutorial patterns compile and run correctly. */
final class ExtractorsTutorialTest extends AnyFunSuite:
  // Lexer used across tests
  val MyLexer = lexer:
    case "\\s+" => Token.Ignored
    case "\\+" => Token["+"]
    case "-" => Token["-"]
    case "\\*" => Token["*"]
    case ":" => Token[":"]
    case "," => Token[","]
    case "\\(" => Token["("]
    case "\\)" => Token[")"]
    case keyword @ ("if" | "then" | "val") => Token[keyword.type]
    case x @ "\\d+" => Token["NUM"](x.toInt)
    case x @ "[a-zA-Z_][a-zA-Z0-9_]*" => Token["ID"](x)

  // Section 1: Matching terminals — basic token matching and value access
  test("basic token matching with _ and value access") {
    object TerminalMatchParser extends Parser:
      val root: Rule[Int] = rule:
        case Expr(v) => v

      val Expr: Rule[Int] = rule(
        // "To match a token without caring about its value, use _"
        "plus" { case (Expr(a), MyLexer.`+`(_), Expr(b)) => a + b },
        // "To access the value captured by the token, bind it to a variable"
        { case MyLexer.NUM(n) => n.value },
      )

    given Resolutions[TerminalMatchParser.type] = resolutions(
      production.plus.before(MyLexer.`+`),
    )

    val (_, lexemes) = MyLexer.tokenize("1 + 2 + 3")
    val (_, result) = TerminalMatchParser.parse(lexemes)
    assert(result == 6)
  }

  // Section 2: Matching non-terminals (rules as extractors)
  test("rules act as extractors") {
    object NonTerminalMatchParser extends Parser:
      val root: Rule[String] = rule:
        case Stmt(s) => s

      val Expr: Rule[Int] = rule(
        "plus" { case (Expr(a), MyLexer.`+`(_), Expr(b)) => a + b },
        { case MyLexer.NUM(n) => n.value },
      )

      val Stmt: Rule[String] = rule:
        case Expr(e) => s"Expression result: $e"

    given Resolutions[NonTerminalMatchParser.type] = resolutions(
      production.plus.before(MyLexer.`+`),
    )

    val (_, lexemes) = MyLexer.tokenize("1 + 2")
    val (_, result) = NonTerminalMatchParser.parse(lexemes)
    assert(result == "Expression result: 3")
  }

  // Section 3: EBNF extractors — .List (works on Rules, not tokens)
  test(".List extractor matches zero or more") {
    object ListExtractorParser extends Parser:
      val root: Rule[List[Int]] = rule:
        case Num.List(ns) => ns

      val Num: Rule[Int] = rule:
        case MyLexer.NUM(n) => n.value

    val (_, lexemes) = MyLexer.tokenize("1 2 3")
    val (_, result) = ListExtractorParser.parse(lexemes)
    assert(result == List(1, 2, 3))
  }

  // Section 3: EBNF extractors — .Option (works on Rules, not tokens)
  test(".Option extractor matches zero or one") {
    object OptionExtractorParser extends Parser:
      val root: Rule[(Int, Option[Int])] = rule:
        case (Num(a), MyLexer.`,`(_), Num.Option(b)) =>
          (a, b)

      val Num: Rule[Int] = rule:
        case MyLexer.NUM(n) => n.value

    val (_, lexemes1) = MyLexer.tokenize("1 , 2")
    val (_, result1) = OptionExtractorParser.parse(lexemes1)
    assert(result1 == (1, Some(2)))

    val (_, lexemes2) = MyLexer.tokenize("1 ,")
    val (_, result2) = OptionExtractorParser.parse(lexemes2)
    assert(result2 == (1, None))
  }

  // Section 3: EBNF extractors — .SeparatedBy
  test(".SeparatedBy extractor matches zero or more items with separators") {
    object P extends Parser:
      // Precise binding type: token separators yield Lexemes, not the token type.
      val root: Rule[List[Int | Lexeme[",", Unit]]] = rule:
        case Num.SeparatedBy[MyLexer.`,`](items) => items

      val Num: Rule[Int] = rule:
        case MyLexer.NUM(n) => n.value

    val (_, emptyLexemes) = MyLexer.tokenize("")
    val (_, emptyResult) = P.parse(emptyLexemes)
    assert(emptyResult == Nil)

    val (_, singletonLexemes) = MyLexer.tokenize("1")
    val (_, singletonResult) = P.parse(singletonLexemes)
    assert(singletonResult == List(1))

    val (_, repeatedLexemes) = MyLexer.tokenize("1,2,3")
    val (_, repeatedResult) = P.parse(repeatedLexemes)
    // Accessing .name only typechecks because separators are typed as Lexeme, not Token.
    val separatorNames = repeatedResult.nn.collect { case l: Lexeme[?, ?] =>
      l.name
    }
    assert(separatorNames == List(",", ","))
    assert(repeatedResult.nn match
      case List(1, _, 2, _, 3) => true
      case _ => false)
  }

  test(".SeparatedBy inside a tuple pattern") {
    object P extends Parser:
      val root: Rule[(Int, List[Int | Lexeme[",", Unit]])] = rule:
        case (MyLexer.`(`(_), Num.SeparatedBy[MyLexer.`,`](items), MyLexer.`)`(_)) =>
          (items.count(_.isInstanceOf[Int]), items)

      val Num: Rule[Int] = rule:
        case MyLexer.NUM(n) => n.value

    val (_, lexemes) = MyLexer.tokenize("(10,20,30)")
    val (_, result) = P.parse(lexemes)
    assert(result.nn._1 == 3)
    assert(result.nn._2 match
      case List(10, _, 20, _, 30) => true
      case _ => false)

    val (_, emptyLexemes) = MyLexer.tokenize("()")
    val (_, emptyResult) = P.parse(emptyLexemes)
    assert(emptyResult == (0, Nil))
  }

  test(".SeparatedBy with a Rule separator") {
    object P extends Parser:
      // Rule separator: SepValue[Sep.type] reduces to Sep's result type (String).
      val root: Rule[List[Int | String]] = rule:
        case Num.SeparatedBy[Sep.type](items) => items

      val Num: Rule[Int] = rule:
        case MyLexer.NUM(n) => n.value

      val Sep: Rule[String] = rule:
        case MyLexer.`,`(_) => ","

    val (_, lexemes) = MyLexer.tokenize("1,2,3")
    val (_, result) = P.parse(lexemes)
    assert(result == List[Int | String](1, ",", 2, ",", 3))
  }

  // Section 4: Tuple matching
  test("tuple matching for sequences") {
    object TupleMatchParser extends Parser:
      val root: Rule[Int] = rule:
        case Expr(v) => v

      val Expr: Rule[Int] = rule(
        { case (MyLexer.`(`(_), Expr(a), MyLexer.`)`(_)) => a },
        "times" { case (Expr(a), MyLexer.`*`(_), Expr(b)) => a * b },
        { case MyLexer.NUM(n) => n.value },
      )

    given Resolutions[TupleMatchParser.type] = resolutions(
      production.times.before(MyLexer.`*`),
    )

    val (_, lexemes) = MyLexer.tokenize("(2 * 3)")
    val (_, result) = TupleMatchParser.parse(lexemes)
    assert(result == 6)
  }
