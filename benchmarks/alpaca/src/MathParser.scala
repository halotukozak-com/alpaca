package halotukozak
package alpaca
package bench

import halotukozak.alpaca.internal.parser.Parser
import halotukozak.alpaca.{lexer, resolutions, rule, Resolutions, Rule, Token}

val MathLexer = lexer {
  case "\\s+" => Token.Ignored
  case x @ ("\\+" | "-" | "\\*" | "/" | "\\(" | "\\)") => Token[x.type]
  case num @ "\\d+" => Token["num"](num.toInt)
}

object MathParser extends Parser:
  val Expr: Rule[Int] = rule(
    "mul" { case (Expr(a), MathLexer.`\\*`(_), Expr(b)) => a * b },
    "div" { case (Expr(a), MathLexer.`/`(_), Expr(b)) => a / b },
    "plus" { case (Expr(a), MathLexer.`\\+`(_), Expr(b)) => a + b },
    "minus" { case (Expr(a), MathLexer.`-`(_), Expr(b)) => a - b },
    { case (MathLexer.`\\(`(_), Expr(a), MathLexer.`\\)`(_)) => a },
    { case MathLexer.num(n) => n.value },
  )

  val root: Rule[Int] = rule { case Expr(v) => v }

given Resolutions[MathParser.type] = resolutions(
  // multiplication and division are left associative (reduction before shift)
  production.mul.before(MathLexer.`\\*`, MathLexer.`/`),
  production.div.before(MathLexer.`\\*`, MathLexer.`/`),
  // addition and subtraction have lower precedence than multiplication and division
  production.plus.after(MathLexer.`\\*`, MathLexer.`/`),
  production.minus.after(MathLexer.`\\*`, MathLexer.`/`),
  // addition and subtraction are left associative (reduction before shift)
  production.plus.before(MathLexer.`\\+`, MathLexer.`-`),
  production.minus.before(MathLexer.`\\+`, MathLexer.`-`),
)

@main def mathParserMain(): Unit = {
  import java.nio.file.{Files, Paths}

  val filePathIterative = s"inputs/iterative_math_3.txt"
  val fileContentIterative = new String(
    Files.readAllBytes(Paths.get(filePathIterative)),
  )

  try
    val (_, tokens) = MathLexer.tokenize(fileContentIterative)
    val result = MathParser.parse(tokens).getOrThrow
    println(s"\nResult Iterative: $result")
  catch
    case e: Exception =>
      println(s"\nError Iterative: ${e.getMessage}")
      e.printStackTrace()

  val filePathRecursive = s"inputs/recursive_math_3.txt"
  val fileContentRecursive = new String(
    Files.readAllBytes(Paths.get(filePathRecursive)),
  )

  try
    val (_, tokens) = MathLexer.tokenize(fileContentRecursive)
    val result = MathParser.parse(tokens).getOrThrow
    println(s"\nResult Recursive: $result")
  catch
    case e: Exception =>
      println(s"\nError Recursive: ${e.getMessage}")
      e.printStackTrace()
}
