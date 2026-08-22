# BrainFuck Interpreter

This guide assembles the complete BrainFuck> interpreter that has been built incrementally across the documentation. It combines every Alpaca feature: custom lexer context, value-bearing tokens, EBNF operators, parser context, and semantic actions.

**What you'll build:** a working interpreter for BrainFuck extended with repeat counts, named cells, and named functions.

## The BrainFuck> Language

Standard BrainFuck has eight single-character commands operating on an array of 256 bytes with a movable pointer:

| Command | Meaning |
|---------|---------|
| `>` | Move pointer right |
| `<` | Move pointer left |
| `+` | Increment current cell |
| `-` | Decrement current cell |
| `.` | Print current cell as ASCII |
| `,` | Read one byte into current cell |
| `[` | Jump forward past matching `]` if current cell is zero |
| `]` | Jump back to matching `[` if current cell is nonzero |

BrainFuck> extends this with:
- `N` (digits) -- repeat the next command N times (e.g., `3+` = `+++`)
- `$name` -- go to a named cell (auto-allocated on first use)
- `name(body)` -- define a function with the given name and body
- `name!` -- call a previously defined function

Everything else is a comment.

## The AST

```scala sc:nocompile
enum BrainAST:
  case Root(ops: List[BrainAST])
  case While(ops: List[BrainAST])
  case Repeat(count: Int, op: BrainAST)
  case GoToCell(name: String)
  case FunctionDef(name: String, ops: List[BrainAST])
  case FunctionCall(name: String)
  case Next, Prev, Inc, Dec, Print, Read
```

## The Lexer

The lexer tracks bracket depth in a custom context to catch mismatches at lex time:

```scala sc:nocompile
import alpaca.*

case class BrainLexContext(
  var brackets: Int = 0,
  var squareBrackets: Int = 0,
) extends LexerCtx

val BrainLexer = lexer[BrainLexContext]:
  case ">" => Token["next"]
  case "<" => Token["prev"]
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case "\\." => Token["print"]
  case "," => Token["read"]
  case "\\[" =>
    ctx.squareBrackets += 1
    Token["jumpForward"]
  case "\\]" =>
    require(ctx.squareBrackets > 0, "Mismatched brackets")
    ctx.squareBrackets -= 1
    Token["jumpBack"]
  case count @ "[0-9]+" => Token["repeat"](count.toInt)
  case cell @ "\\$[A-Za-z]+" => Token["cell"](cell.drop(1))
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "\\(" =>
    ctx.brackets += 1
    Token["functionOpen"]
  case "\\)" =>
    require(ctx.brackets > 0, "Mismatched brackets")
    ctx.brackets -= 1
    Token["functionClose"]
  case "!" => Token["functionCall"]
  case "." => Token.Ignored
  case "\n" => Token.Ignored
```

## The Parser

The parser uses `ParserCtx` to track defined function names and reject calls to undefined functions:

```scala sc:nocompile
import alpaca.*
import scala.collection.mutable

case class BrainParserCtx(
  functions: mutable.Set[String] = mutable.Set.empty,
) extends ParserCtx
object BrainParser extends Parser[BrainParserCtx]:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)

  val While: Rule[BrainAST] = rule:
    case (BrainLexer.jumpForward(_), Operation.List(stmts), BrainLexer.jumpBack(_)) =>
      BrainAST.While(stmts)

  val Operation: Rule[BrainAST] = rule(
    { case BrainLexer.next(_) => BrainAST.Next },
    { case BrainLexer.prev(_) => BrainAST.Prev },
    { case BrainLexer.inc(_) => BrainAST.Inc },
    { case BrainLexer.dec(_) => BrainAST.Dec },
    { case BrainLexer.print(_) => BrainAST.Print },
    { case BrainLexer.read(_) => BrainAST.Read },
    { case (BrainLexer.repeat(n), Operation(op)) => BrainAST.Repeat(n.value, op) },
    { case BrainLexer.cell(name) => BrainAST.GoToCell(name.value) },
    { case While(whl) => whl },
    { case FunctionDef(fdef) => fdef },
    { case FunctionCall(call) => call },
  )

  val FunctionDef: Rule[BrainAST] = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionOpen(_),
          Operation.List(ops), BrainLexer.functionClose(_)) =>
      require(ctx.functions.add(name.value), s"Function ${name.value} is already defined")
      BrainAST.FunctionDef(name.value, ops)

  val FunctionCall: Rule[BrainAST] = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionCall(_)) =>
      require(ctx.functions.contains(name.value), s"Function ${name.value} is not defined")
      BrainAST.FunctionCall(name.value)
```

## The Evaluator

```scala sc:nocompile
import scala.collection.mutable

class Memory(
  val cells: Array[Int] = new Array(256),
  var pointer: Int = 0,
  val functions: mutable.Map[String, List[BrainAST]] = mutable.Map.empty,
  val namedCells: mutable.Map[String, Int] = mutable.Map.empty,
)

extension (ast: BrainAST)
  def eval(mem: Memory): Unit = ast match
    case BrainAST.Root(ops)  => ops.foreach(_.eval(mem))
    case BrainAST.Next       => mem.pointer = (mem.pointer + 1) & 0xff
    case BrainAST.Prev       => mem.pointer = (mem.pointer - 1) & 0xff
    case BrainAST.Inc        => mem.cells(mem.pointer) = (mem.cells(mem.pointer) + 1) & 0xff
    case BrainAST.Dec        => mem.cells(mem.pointer) = (mem.cells(mem.pointer) - 1) & 0xff
    case BrainAST.Print      => print(mem.cells(mem.pointer).toChar)
    case BrainAST.Read       => mem.cells(mem.pointer) = scala.io.StdIn.readChar() & 0xff
    case BrainAST.Repeat(n, op) => (1 to n).foreach(_ => op.eval(mem))
    case BrainAST.GoToCell(name) =>
      val idx = mem.namedCells.getOrElseUpdate(name, mem.namedCells.size)
      require(idx < mem.cells.length, s"Too many named cells (max ${mem.cells.length})")
      mem.pointer = idx
    case BrainAST.While(ops) => while mem.cells(mem.pointer) != 0 do ops.foreach(_.eval(mem))
    case BrainAST.FunctionDef(name, ops) => mem.functions += (name -> ops)
    case BrainAST.FunctionCall(name) =>
      mem.functions.get(name) match
        case Some(ops) => ops.foreach(_.eval(mem))
        case _ => throw RuntimeException(s"Undefined function: $name")
```

## Running Programs

```scala sc:nocompile
import alpaca.*

@main def run(): Unit =
  val program = "++++++++[>++++[>++>+++>+++>+<<<<-]>+>+>->>+[<]<-]>>.>---.+++++++..+++.>>.<-.<.+++.------.--------.>>+.>++."
  val (ctx, lexemes) = BrainLexer.tokenize(program)
  require(ctx.squareBrackets == 0 && ctx.brackets == 0, "Mismatched brackets")
  val (_, ast) = BrainParser.parse(lexemes)
  ast.nn.eval(Memory())
  // prints: Hello World!
```

With repeat counts and named cells:

```scala sc:nocompile
val program = "$a 3+ $b 5+ $a ."
val (ctx, lexemes) = BrainLexer.tokenize(program)
require(ctx.squareBrackets == 0 && ctx.brackets == 0, "Mismatched brackets")
val (_, ast) = BrainParser.parse(lexemes)
val mem = Memory()
ast.nn.eval(mem)
// cell 'a' (index 0) = 3, cell 'b' (index 1) = 5, pointer back to 'a', prints char 3
```

With functions:

```scala sc:nocompile
val program = "$a foo(3+)foo!foo!."
val (ctx, lexemes) = BrainLexer.tokenize(program)
require(ctx.squareBrackets == 0 && ctx.brackets == 0, "Mismatched brackets")
val (_, ast) = BrainParser.parse(lexemes)
val mem = Memory()
ast.nn.eval(mem)
// cell 'a' = 6 (two calls to foo, each adding 3), then prints char 6
```

## Testing

Key assertions from the test suite:

```scala sc:nocompile
// Lexer
val (_, tokens) = BrainLexer.tokenize("><+-.,")
assert(tokens.map(_.name) == List("next", "prev", "inc", "dec", "print", "read"))

// Parser
val (_, ast) = BrainParser.parse(BrainLexer.tokenize("[>+<-]")._2)
assert(ast == BrainAST.Root(List(
  BrainAST.While(List(BrainAST.Next, BrainAST.Inc, BrainAST.Prev, BrainAST.Dec))
)))

// Repeat count
val (_, ast2) = BrainParser.parse(BrainLexer.tokenize("3+")._2)
assert(ast2 == BrainAST.Root(List(BrainAST.Repeat(3, BrainAST.Inc))))

// Named cells
val mem = Memory()
BrainParser.parse(BrainLexer.tokenize("$a 3+ $b 5+")._2)._2.nn.eval(mem)
assert(mem.cells(0) == 3 && mem.cells(1) == 5)  // auto-allocated indices

// Evaluator
val mem2 = Memory()
BrainParser.parse(BrainLexer.tokenize("+++++[-]")._2)._2.nn.eval(mem2)
assert(mem2.cells(0) == 0)  // cell cleared by loop
```

## Extensions

Ideas for extending the interpreter further:

- **Error recovery** -- use `ErrorHandling.Strategy.IgnoreChar` instead of a catch-all pattern
- **Source positions** -- add `PositionTracking` and `LineTracking` to the lexer context for better error messages
- **String literals** -- add a `"..."` token for inline string output
