# Getting Started

This guide walks you through building an interpreter for an extended BrainFuck dialect with Alpaca. By the end, you will have a working lexer, parser, and evaluator — roughly 80 lines of code.

BrainFuck is a minimal language, but we extend it with repeat counts, named cells, and functions. That makes it an ideal first project: the grammar is small enough to fit on screen, but rich enough to exercise value-bearing tokens, variable binding, context tracking, and AST construction.

## Prerequisites

- JDK 21 or later
- Mill 1.1.10+ (or SBT — see [Installation](index.md#installation) for SBT/Scala CLI setup)
- Scala 3.9.0 or later

## Project Setup

Create a Mill project with Alpaca as a dependency:

```mill
//| mill-version: 1.1.10
//| mill-jvm-version: 21

import mill._
import mill.scalalib._

object brainfuck extends ScalaModule {
  def scalaVersion = "3.9.0"
  def scalacOptions = Seq("-Yretain-trees")
  def mvnDeps = Seq(
    mvn"com.halotukozak::alpaca::1.0.0-RC1"
  )
}
```

The `-Yretain-trees` flag is required. Alpaca's macros inspect the AST of your lexer and parser definitions at compile time, and this flag tells the compiler to preserve that information. Alpaca 0.3.2 and earlier also need the `-experimental` flag; later versions do not.

## Step 1: The Lexer

BrainFuck> has the eight standard BrainFuck commands plus repeat counts, named cells, function definitions, and function calls. Everything else is a comment.

| Command | Meaning |
| :---: | :--- |
| `>` | Move the data pointer to the next cell |
| `<` | Move the data pointer to the previous cell |
| `+` | Increment the byte at the data pointer |
| `-` | Decrement the byte at the data pointer |
| `.` | Output the byte at the data pointer as a character |
| `,` | Read one byte of input into the current cell |
| `[` | Jump forward past the matching `]` if the current cell is zero |
| `]` | Jump back to the matching `[` if the current cell is non-zero |
| `N` (digits) | Repeat the next command N times (e.g., `3+` = `+++`) |
| `$name` | Go to named cell (auto-allocated on first use) |
| `name(body)` | Define a function |
| `name!` | Call a function |

```scala sc-name:gs-lexer
import halotukozak.alpaca.*

case class BrainLexContext(
  brackets: Int = 0,
  squareBrackets: Int = 0,
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

Each `case` maps a regex pattern to a token. `Token["next"]` creates a named token. `Token.Ignored` matches but produces no output.

Three tokens carry values via the `@` binding:
- `count @ "[0-9]+"` captures the matched digits, then `Token["repeat"](count.toInt)` converts them to an `Int`.
- `cell @ "\\$[A-Za-z]+"` captures the full match (including `$`), then `Token["cell"](cell.drop(1))` strips the prefix.
- `name @ "[A-Za-z]+"` captures the function name as-is.

The custom context `BrainLexContext` tracks bracket depth. Inside rule bodies, `ctx` gives access to the context — the lexer increments and decrements counters and uses `require` to catch mismatched brackets at lex time.

Pattern order matters: `"\\."` (literal dot — the print command) must appear before `"."` (any character — the catch-all). Both match one character, so the earlier one wins; in the other order the print command could never match, and the lexer reports that as a compile error (see [How a Token Is Chosen](lexer.md#how-a-token-is-chosen)).

Try it:

```scala sc-compile-with:gs-lexer
val lexed = BrainLexer.tokenize("foo(++)")
val lexemes = lexed.getOrThrow
require(lexed.ctx.brackets == 0 && lexed.ctx.squareBrackets == 0, "Mismatched brackets")
println(lexemes.map(_.name))
// List(functionName, functionOpen, inc, inc, functionClose)
```

## Step 2: The AST

Before writing the parser, define the tree structure it will produce. BrainFuck> programs are sequences of instructions — some contain nested lists (loops, function bodies):

```scala sc-name:gs-ast
enum BrainAST:
  case Root(ops: List[BrainAST])
  case While(ops: List[BrainAST])
  case Repeat(count: Int, op: BrainAST)
  case GoToCell(name: String)
  case FunctionDef(name: String, ops: List[BrainAST])
  case FunctionCall(name: String)
  case Next, Prev, Inc, Dec, Print, Read
```

`Root` wraps the top-level program. `While` represents a `[...]` loop. `Repeat` holds a count and a single operation to repeat. `GoToCell` moves the pointer to a named cell. `FunctionDef` and `FunctionCall` handle named functions.

## Step 3: The Parser

The parser turns a flat list of lexemes into a nested `BrainAST`:

```scala sc-name:gs-parser sc-compile-with:gs-lexer,gs-ast
import halotukozak.alpaca.*
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

Things to note:

- **`root`** is required. It defines the grammar's start symbol. Here it matches zero or more `Operation`s via `.List`.
- **`Operation.List(stmts)`** is an EBNF operator. It matches zero or more occurrences and returns a `List[BrainAST]`.
- **`BrainLexer.next(_)`** matches a lexeme whose token name is `"next"`. The `_` discards the lexeme — we only care that the token appeared.
- **`name.value`** accesses the value from a `Lexeme`. After `BrainLexer.functionName(name)`, `name` is a `Lexeme` and `name.value` is the matched `String`.
- **`n.value`** on `repeat` is an `Int`, not a `String` — the lexer already converted it with `count.toInt`. The type of `.value` depends on what the lexer put into the token.
- **`(BrainLexer.repeat(n), Operation(op))`** is a two-symbol production matching a terminal followed by a non-terminal. Nesting works naturally: `3 2 +` means repeat 3 times the operation "repeat 2 times increment".
- **`Parser[BrainParserCtx]`** carries state through reductions. `ctx.functions` tracks which functions have been defined, so `FunctionCall` can reject undefined names.

## Step 4: The Evaluator

BrainFuck operates on an array of 256 bytes with a movable pointer. Functions are stored by name and called by looking them up:

```scala sc-name:gs-eval sc-compile-with:gs-parser
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

## Step 5: Run It

Wire the three stages together:

```scala sc-compile-with:gs-eval
import halotukozak.alpaca.*

@main def run(): Unit =
  // Standard BrainFuck: Hello World
  val hello = "++++++++[>++++[>++>+++>+++>+<<<<-]>+>+>->>+[<]<-]>>.>---.+++++++..+++.>>.<-.<.+++.------.--------.>>+.>++."
  val lexed1 = BrainLexer.tokenize(hello)
  val lexemes1 = lexed1.getOrThrow
  require(lexed1.ctx.squareBrackets == 0, "Mismatched brackets")
  val ast1 = BrainParser.parse(lexemes1).getOrThrow
  ast1.eval(Memory())
  // prints: Hello World!

  // Repeat counts and named cells
  val extended = "$a 3+ $b 5+ $a ."
  val lexed2 = BrainLexer.tokenize(extended)
  val lexemes2 = lexed2.getOrThrow
  require(lexed2.ctx.brackets == 0 && lexed2.ctx.squareBrackets == 0, "Mismatched brackets")
  val ast2 = BrainParser.parse(lexemes2).getOrThrow
  val mem = Memory()
  ast2.eval(mem)
  // cell 'a' (index 0) = 3, cell 'b' (index 1) = 5, pointer back to 'a', prints char 3

  // Functions: define once, call twice
  val withFunctions = "$a foo(3+)foo!foo!."
  val lexed3 = BrainLexer.tokenize(withFunctions)
  val lexemes3 = lexed3.getOrThrow
  require(lexed3.ctx.brackets == 0 && lexed3.ctx.squareBrackets == 0, "Mismatched brackets")
  val ast3 = BrainParser.parse(lexemes3).getOrThrow
  val mem2 = Memory()
  ast3.eval(mem2)
  // cell 'a' = 6 (two calls to foo, each adding 3), then prints char 6
```

The pipeline is always the same: `tokenize` produces lexemes, `parse` produces a `Result` holding the AST (`getOrThrow` takes the `BrainAST` out of it; input that does not match the grammar comes back as a `Result.Failure` listing the `ParserError`s, see [Parsing Input](parser.md#parsing-input)), and you evaluate the result however you want.

## Step 6: Test It

Each stage can be checked on its own:

```scala sc-compile-with:gs-eval
import halotukozak.alpaca.*

// Lexer
val tokens = BrainLexer.tokenize("><+-.,").getOrThrow
assert(tokens.map(_.name) == List("next", "prev", "inc", "dec", "print", "read"))

// Parser
val loop = BrainParser.parse(BrainLexer.tokenize("[>+<-]").getOrThrow).getOrThrow
assert(loop == BrainAST.Root(List(
  BrainAST.While(List(BrainAST.Next, BrainAST.Inc, BrainAST.Prev, BrainAST.Dec))
)))
val repeat = BrainParser.parse(BrainLexer.tokenize("3+").getOrThrow).getOrThrow
assert(repeat == BrainAST.Root(List(BrainAST.Repeat(3, BrainAST.Inc))))

// Evaluator
val mem = Memory()
BrainParser.parse(BrainLexer.tokenize("$a 3+ $b 5+").getOrThrow).getOrThrow.eval(mem)
assert(mem.cells(0) == 3 && mem.cells(1) == 5)  // named cells get indices in order of first use
val cleared = Memory()
BrainParser.parse(BrainLexer.tokenize("+++++[-]").getOrThrow).getOrThrow.eval(cleared)
assert(cleared.cells(0) == 0)  // the loop decrements the cell to zero
```

## What's Next

Some ways to take the interpreter further:

- **Source positions** — add `Column` and `Line` fields to `BrainLexContext` (see [Built-in Tracking Fragments](lexer-context.md#built-in-tracking-fragments)); `LexerError` and `ParserError` messages then name the line and column of each error.
- **String literals** — add a `"..."` token for printing text inline.

This interpreter uses the simplest form of every Alpaca feature. The rest of the documentation covers each in full:

- [Lexer](lexer.md) — regex patterns, value extraction, token naming rules
- [Lexer Context](lexer-context.md) — tracking state during tokenization
- [Parser](parser.md) — rules, named productions, EBNF operators
- [Parser Context](parser-context.md) — shared state during parsing
- [Extractors](extractors.md) — pattern matching on terminals and non-terminals
- [Conflict Resolution](conflict-resolution.md) — resolving shift/reduce and reduce/reduce conflicts
- [Theory](theory/index.md) — formal foundations behind everything above
