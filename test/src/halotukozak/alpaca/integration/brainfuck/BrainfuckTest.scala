package halotukozak
package alpaca
package integration.brainfuck

import org.scalatest.funsuite.AnyFunSuite

final class BrainfuckTest extends AnyFunSuite:

  // --- Lexer Tests ---

  private def tokenize(input: String) =
    val (ctx, tokens) = BrainLexer.tokenize(input)
    require(ctx.brackets == 0 && ctx.squareBrackets == 0, "Mismatched brackets")
    (ctx, tokens)

  test("tokenize basic operators") {
    val (_, tokens) = tokenize("><+-.,")
    assert(tokens.map(_.name) == List("next", "prev", "inc", "dec", "print", "read"))
  }

  test("tokenize brackets") {
    val (ctx, tokens) = tokenize("[>+<-]")
    assert(ctx.squareBrackets == 0)
    assert(tokens.map(_.name) == List("jumpForward", "next", "inc", "prev", "dec", "jumpBack"))
  }

  test("mismatched closing bracket") {
    intercept[IllegalArgumentException] {
      tokenize("]")
    }
  }

  test("tokenize function syntax") {
    val (ctx, tokens) = tokenize("foo(++)")
    assert(ctx.brackets == 0)
    assert(tokens.map(_.name) == List("functionName", "functionOpen", "inc", "inc", "functionClose"))
  }

  test("tokenize function call") {
    val (_, tokens) = tokenize("foo!")
    assert(tokens.map(_.name) == List("functionName", "functionCall"))
  }

  test("ignore whitespace and unknown chars") {
    val (_, tokens) = tokenize("+ +\n+")
    assert(tokens.map(_.name) == List("inc", "inc", "inc"))
  }

  // --- Parser Tests ---

  private def parse(input: String): BrainAST =
    val (_, tokens) = BrainLexer.tokenize(input)
    val ast = BrainParser.parse(tokens).getOrThrow
    ast

  test("parse basic operations") {
    val ast = parse("><+-.,")
    assert(
      ast == BrainAST.Root(List(BrainAST.Next, BrainAST.Prev, BrainAST.Inc, BrainAST.Dec, BrainAST.Print, BrainAST.Read)),
    )
  }

  test("parse while loop") {
    val ast = parse("[>+<-]")
    assert(ast == BrainAST.Root(List(BrainAST.While(List(BrainAST.Next, BrainAST.Inc, BrainAST.Prev, BrainAST.Dec)))))
  }

  test("parse nested loops") {
    val ast = parse("[>[+]-]")
    assert(ast == BrainAST.Root(List(BrainAST.While(List(BrainAST.Next, BrainAST.While(List(BrainAST.Inc)), BrainAST.Dec)))))
  }

  test("parse empty program") {
    val ast = parse("")
    assert(ast == BrainAST.Root(List.empty))
  }

  test("parse function definition") {
    val ast = parse("foo(++)")
    assert(ast == BrainAST.Root(List(BrainAST.FunctionDef("foo", List(BrainAST.Inc, BrainAST.Inc)))))
  }

  test("parse function definition and call") {
    val ast = parse("foo(++)foo!")
    assert(
      ast ==
        BrainAST.Root(List(BrainAST.FunctionDef("foo", List(BrainAST.Inc, BrainAST.Inc)), BrainAST.FunctionCall("foo"))),
    )
  }

  // --- Eval Tests ---

  private def run(input: String): Memory =
    val (_, tokens) = BrainLexer.tokenize(input)
    val ast = BrainParser.parse(tokens).getOrThrow
    val mem = new Memory()
    ast.eval(mem)
    mem

  private def runAndCapture(input: String): (Memory, String) =
    val (_, tokens) = BrainLexer.tokenize(input)
    val ast = BrainParser.parse(tokens).getOrThrow
    val mem = new Memory()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      ast.eval(mem)
    }
    (mem, out.toString)

  test("increment") {
    val mem = run("+++")
    assert(mem.underlying(0).toInt == 3)
  }

  test("decrement") {
    val mem = run("+++-")
    assert(mem.underlying(0).toInt == 2)
  }

  test("move pointer") {
    val mem = run("+++>++>+")
    assert(mem.underlying(0).toInt == 3)
    assert(mem.underlying(1).toInt == 2)
    assert(mem.underlying(2).toInt == 1)
    assert(mem.pointer.toInt == 2)
  }

  test("move pointer back") {
    val mem = run(">+++<++")
    assert(mem.underlying(0).toInt == 2)
    assert(mem.underlying(1).toInt == 3)
    assert(mem.pointer.toInt == 0)
  }

  test("simple loop - clear cell") {
    val mem = run("+++++[-]")
    assert(mem.underlying(0).toInt == 0)
  }

  test("loop - multiply") {
    val mem = run("+++++[>+++<-]")
    assert(mem.underlying(0).toInt == 0)
    assert(mem.underlying(1).toInt == 15)
  }

  test("nested loops") {
    val mem = run("++[>++[>+++<-]<-]")
    assert(mem.underlying(2).toInt == 12)
  }

  test("print character") {
    val (_, output) = runAndCapture("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++.")
    assert(output == "H")
  }

  test("hello world") {
    val (_, output) = runAndCapture(
      "++++++++[>++++[>++>+++>+++>+<<<<-]>+>+>->>+[<]<-]>>.>---.+++++++..+++.>>.<-.<.+++.------.--------.>>+.>++.",
    )
    assert(output == "Hello World!\n")
  }

  test("function definition registers in memory") {
    val mem = run("foo(+++)")
    assert(mem.functions.contains("foo"))
  }

  test("function call executes body") {
    val mem = run("foo(+++)foo!")
    assert(mem.underlying(0).toInt == 3)
  }

  test("function call multiple times") {
    val mem = run("foo(+++)foo!foo!")
    assert(mem.underlying(0).toInt == 6)
  }

  test("function with pointer movement") {
    val mem = run("foo(>+++)foo!foo!")
    assert(mem.underlying(1).toInt == 3)
    assert(mem.underlying(2).toInt == 3)
    assert(mem.pointer.toInt == 2)
  }

  test("undefined function call fails") {
    intercept[RuntimeException] {
      run("bar!")
    }
  }
