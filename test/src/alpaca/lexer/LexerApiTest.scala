package alpaca.lexer

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import alpaca.lexer.context.default.DefaultGlobalCtx
import scala.annotation.nowarn

@nowarn("msg=A pure expression")
final class LexerApiTest extends AnyFunSuite with Matchers {
  val Lexer = lexer {
    case literal @ ('<' | '>' | '=' | '+' | '-' | '*' | '/' | '(' | ')' | '[' | ']' | '{' | '}' | ':') =>
      Token[literal.type]
    case literal @ ("'" | "," | ";") =>
      Token[literal.type]
    case "\\.\\+" => Token["dotAdd"]
    case "\\.\\-" => Token["dotSub"]
    case "\\.\\*" => Token["dotMul"]
    case "\\.\\/" => Token["dotDiv"]
    case "<=" => Token["lessEqual"]
    case comment @ "#.*" => Token.Ignored
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
  }

  test("Lexer recognizes basic tokens") {
    Lexer.tokens.map(_.info.pattern) shouldBe List(
    //format: off
      "#.*",
      "<", ">", "=", "\\+", "-", "\\*", "/", "\\(", "\\)", "\\[", "\\]", "{", "}", ":", "'", ",", ";",
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
    )
    //format: on

    // we check if compiles and not crashes
    Lexer.< : Token["<", DefaultGlobalCtx, Unit]
    Lexer.> : Token[">", DefaultGlobalCtx, Unit]
    Lexer.`=`: Token["=", DefaultGlobalCtx, Unit]
    Lexer.`\\+`: Token["\\+", DefaultGlobalCtx, Unit]
    Lexer.- : Token["-", DefaultGlobalCtx, Unit]
    Lexer.`\\*`: Token["\\*", DefaultGlobalCtx, Unit]
    Lexer.`/`: Token["/", DefaultGlobalCtx, Unit]
    Lexer.`\\(`: Token["\\(", DefaultGlobalCtx, Unit]
    Lexer.`\\)`: Token["\\)", DefaultGlobalCtx, Unit]
    Lexer.`\\[`: Token["\\[", DefaultGlobalCtx, Unit]
    Lexer.`\\]`: Token["\\]", DefaultGlobalCtx, Unit]
    Lexer.`{`: Token["{", DefaultGlobalCtx, Unit]
    Lexer.`}`: Token["}", DefaultGlobalCtx, Unit]
    Lexer.`:`: Token[":", DefaultGlobalCtx, Unit]
    Lexer.`'`: Token["'", DefaultGlobalCtx, Unit]
    Lexer.`,`: Token[",", DefaultGlobalCtx, Unit]
    Lexer.`;`: Token[";", DefaultGlobalCtx, Unit]
    Lexer.dotAdd: Token["dotAdd", DefaultGlobalCtx, Unit]
    Lexer.dotSub: Token["dotSub", DefaultGlobalCtx, Unit]
    Lexer.dotMul: Token["dotMul", DefaultGlobalCtx, Unit]
    Lexer.dotDiv: Token["dotDiv", DefaultGlobalCtx, Unit]
    Lexer.lessEqual: Token["lessEqual", DefaultGlobalCtx, Unit]
    Lexer.greaterEqual: Token["greaterEqual", DefaultGlobalCtx, Unit]
    Lexer.notEqual: Token["notEqual", DefaultGlobalCtx, Unit]
    Lexer.equal: Token["equal", DefaultGlobalCtx, Unit]
    Lexer.float: Token["float", DefaultGlobalCtx, Double]
    Lexer.int: Token["int", DefaultGlobalCtx, Int]
    Lexer.string: Token["string", DefaultGlobalCtx, String]
    Lexer.`if`: Token["if", DefaultGlobalCtx, Unit]
    Lexer.`else`: Token["else", DefaultGlobalCtx, Unit]
    Lexer.`for`: Token["for", DefaultGlobalCtx, Unit]
    Lexer.`while`: Token["while", DefaultGlobalCtx, Unit]
    Lexer.break: Token["break", DefaultGlobalCtx, Unit]
    Lexer.continue: Token["continue", DefaultGlobalCtx, Unit]
    Lexer.`return`: Token["return", DefaultGlobalCtx, Unit]
    Lexer.eye: Token["eye", DefaultGlobalCtx, Unit]
    Lexer.zeros: Token["zeros", DefaultGlobalCtx, Unit]
    Lexer.ones: Token["ones", DefaultGlobalCtx, Unit]
    Lexer.print: Token["print", DefaultGlobalCtx, Unit]
    Lexer.id: Token["id", DefaultGlobalCtx, String]
  }
}
