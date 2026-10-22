package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final case class SymbolsCtx(defined: Set[String] = Set.empty, uses: Int = 0, var reductions: Int = 0) extends ParserCtx

val SymbolsLexer = lexer:
  case "def" => Token["def"]
  case "use" => Token["use"]
  case name @ "[a-z]+" => Token["Name"](name)
  case "\\s+" => Token.Ignored

object SymbolsParser extends Parser[SymbolsCtx]:
  val Stmt: Rule[String] = rule(
    { case (SymbolsLexer.`def`(_), SymbolsLexer.Name(name)) =>
      require(!ctx.defined(name.value), s"${name.value} is already defined")
      ctx.defined += name.value
      ctx.reductions += 1
      s"def ${name.value}"
    },
    { case (SymbolsLexer.use(_), SymbolsLexer.Name(name)) =>
      require(ctx.defined(name.value), s"${name.value} is not defined")
      ctx.uses = ctx.uses + 1
      // reads see the assignments before them
      s"use ${name.value} (${ctx.uses})"
    },
  )
  val root: Rule[List[String]] = rule:
    case Stmt.List(stmts) => stmts

final class ParserCtxUpdateTest extends AnyFunSuite with Matchers:

  test("an action assigns immutable context fields that later actions see") {
    val result = SymbolsParser.parse(SymbolsLexer.tokenize("def a use a def b use b use a").getOrThrow)
    result.ctx shouldBe SymbolsCtx(defined = Set("a", "b"), uses = 3, reductions = 2)
    result.getOrThrow shouldBe List("def a", "use a (1)", "def b", "use b (2)", "use a (3)")
  }

  test("each parse starts from the initial context") {
    val lexemes = SymbolsLexer.tokenize("def a").getOrThrow
    SymbolsParser.parse(lexemes).ctx shouldBe SymbolsCtx(defined = Set("a"), reductions = 1)
    SymbolsParser.parse(lexemes).ctx shouldBe SymbolsCtx(defined = Set("a"), reductions = 1)
  }

  test("an action that fails leaves the exception to the caller") {
    val exception = intercept[IllegalArgumentException]:
      SymbolsParser.parse(SymbolsLexer.tokenize("use a").getOrThrow)
    exception.getMessage shouldBe "requirement failed: a is not defined"
  }
