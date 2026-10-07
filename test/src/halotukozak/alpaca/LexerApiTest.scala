package halotukozak
package alpaca

import alpaca.internal.lexer.Token
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class LexerApiTest extends AnyFunSuite with Matchers {
  val Lexer = lexer:
    case "#.*" => Token.Ignored
    case "\\.\\+" => Token["dotAdd"]
    case "\\.\\-" => Token["dotSub"]
    case "\\.\\*" => Token["dotMul"]
    case "\\.\\/" => Token["dotDiv"]
    case "<=" => Token["lessEqual"]
    case ">=" => Token["greaterEqual"]
    case "!=" => Token["notEqual"]
    case "==" => Token["equal"]
    case x @ "(d+(\\.\\d*)|\\.\\d+)([eE][+-]?\\d+)?" => Token["float"](x.toDouble)
    case x @ "[0-9]+" => Token["int"](x.toInt)
    case x @ "\"[^\"]*\"" => Token["string"](x)
    case keyword @ ("if" | "else" | "for" | "while" | "break" | "continue" | "return" | "eye" | "zeros" | "ones" |
        "print") =>
      Token[keyword.type]
    case x @ "[a-zA-Z_][a-zA-Z0-9_]*" => Token["id"](x)
    case literal @ ("<" | ">" | "=" | "\\+" | "-" | "\\*" | "/" | "\\(" | "\\)" | "\\[" | "\\]" | "\\{" | "\\}" | ":" |
        "'" | "," | ";") =>
      Token[literal.type]

  test("Lexer recognizes basic tokens") {
    Lexer.tokens.map(_.info.pattern.raw) shouldBe List(
    //format: off
      "#.*",
      raw"\.\+",
      raw"\.\-",
      raw"\.\*",
      raw"\.\/",
      "<=",
      ">=",
      "!=",
      "==",
      raw"(d+(\.\d*)|\.\d+)([eE][+-]?\d+)?",
      "[0-9]+",
      "\"[^\"]*\"",
      "if", "else", "for", "while", "break", "continue", "return", "eye", "zeros", "ones", "print",
      "[a-zA-Z_][a-zA-Z0-9_]*",
      "<", ">", "=", "\\+", "-", "\\*", "/", "\\(", "\\)", "\\[", "\\]", "\\{", "\\}", ":", "'", ",", ";",
    )
    //format: on

    // we check if compiles and not crashes
    val _: Token["<", LexerCtx.Default, Unit] = Lexer.<
    val _: Token[">", LexerCtx.Default, Unit] = Lexer.>
    val _: Token["=", LexerCtx.Default, Unit] = Lexer.`=`
    val _: Token["\\+", LexerCtx.Default, Unit] = Lexer.`\\+`
    val _: Token["-", LexerCtx.Default, Unit] = Lexer.-
    val _: Token["\\*", LexerCtx.Default, Unit] = Lexer.`\\*`
    val _: Token["/", LexerCtx.Default, Unit] = Lexer.`/`
    val _: Token["\\(", LexerCtx.Default, Unit] = Lexer.`\\(`
    val _: Token["\\)", LexerCtx.Default, Unit] = Lexer.`\\)`
    val _: Token["\\[", LexerCtx.Default, Unit] = Lexer.`\\[`
    val _: Token["\\]", LexerCtx.Default, Unit] = Lexer.`\\]`
    val _: Token["\\{", LexerCtx.Default, Unit] = Lexer.`\\{`
    val _: Token["\\}", LexerCtx.Default, Unit] = Lexer.`\\}`
    val _: Token[":", LexerCtx.Default, Unit] = Lexer.`:`
    val _: Token["'", LexerCtx.Default, Unit] = Lexer.`'`
    val _: Token[",", LexerCtx.Default, Unit] = Lexer.`,`
    val _: Token[";", LexerCtx.Default, Unit] = Lexer.`;`
    val _: Token["dotAdd", LexerCtx.Default, Unit] = Lexer.dotAdd
    val _: Token["dotSub", LexerCtx.Default, Unit] = Lexer.dotSub
    val _: Token["dotMul", LexerCtx.Default, Unit] = Lexer.dotMul
    val _: Token["dotDiv", LexerCtx.Default, Unit] = Lexer.dotDiv
    val _: Token["lessEqual", LexerCtx.Default, Unit] = Lexer.lessEqual
    val _: Token["greaterEqual", LexerCtx.Default, Unit] = Lexer.greaterEqual
    val _: Token["notEqual", LexerCtx.Default, Unit] = Lexer.notEqual
    val _: Token["equal", LexerCtx.Default, Unit] = Lexer.equal
    val _: Token["float", LexerCtx.Default, Double] = Lexer.float
    val _: Token["int", LexerCtx.Default, Int] = Lexer.int
    val _: Token["string", LexerCtx.Default, String] = Lexer.string
    val _: Token["if", LexerCtx.Default, Unit] = Lexer.`if`
    val _: Token["else", LexerCtx.Default, Unit] = Lexer.`else`
    val _: Token["for", LexerCtx.Default, Unit] = Lexer.`for`
    val _: Token["while", LexerCtx.Default, Unit] = Lexer.`while`
    val _: Token["break", LexerCtx.Default, Unit] = Lexer.break
    val _: Token["continue", LexerCtx.Default, Unit] = Lexer.continue
    val _: Token["return", LexerCtx.Default, Unit] = Lexer.`return`
    val _: Token["eye", LexerCtx.Default, Unit] = Lexer.eye
    val _: Token["zeros", LexerCtx.Default, Unit] = Lexer.zeros
    val _: Token["ones", LexerCtx.Default, Unit] = Lexer.ones
    val _: Token["print", LexerCtx.Default, Unit] = Lexer.print
    val _: Token["id", LexerCtx.Default, String] = Lexer.id
  }

  test("Lexer manipulates context") {
    case class StateCtx(count: Int = 0) extends LexerCtx

    val Lexer = lexer[StateCtx]:
      case "inc" =>
        ctx.count += 1
        Token["inc"](ctx.count)
      case "check" =>
        Token["check"](ctx.count)
      case " " => Token.Ignored

    val lexemes = Lexer.tokenize("inc check inc inc check").getOrThrow
    lexemes.map(_.value) shouldBe List(1, 1, 2, 3, 3)
  }

  test("custom Tracking fragment composes without inheritance") {
    val Lexer = lexer[NestDepth.Ctx]:
      case brace @ ("\\{" | "\\}") => Token[brace.type]
      case x @ "[a-z]+" => Token["word"](x)

    val finalCtx = Lexer.tokenize("a{b{c}{d").ctx
    finalCtx.depth shouldBe 2
  }

  test("Line and Column track independently when both are tracked on the same context") {
    val Lexer = lexer[LexerCtx.Default]:
      case "\n" => Token.Ignored
      case x @ "[a-z]+" => Token["word"](x)

    val finalCtx = Lexer.tokenize("ab\ncde").ctx
    finalCtx.line shouldBe 2
    finalCtx.column shouldBe 4
  }
}

object NestDepth:
  opaque type Depth <: Int = Int
  object Depth:
    val Start: Depth = 0
    given Tracking[Depth] = (matched, d) => if matched == "{" then d + 1 else if matched == "}" then d - 1 else d

  final case class Ctx(depth: Depth = Depth.Start) extends LexerCtx
