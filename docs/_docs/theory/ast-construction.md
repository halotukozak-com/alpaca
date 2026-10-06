# AST Construction Patterns

A parser's job is to recognize structure in a token stream. What it *does* with that structure depends on its semantic actions — the `=>` expressions in your `rule` definitions. This page covers the two main approaches: computing values directly during parsing, and building an abstract syntax tree (AST) for later processing.

## Parse Tree vs Abstract Syntax Tree

A **parse tree** (concrete syntax tree) mirrors the grammar exactly. Every non-terminal and terminal in a derivation becomes a node. For the input `1 + 2 * 3` with a calculator grammar, the parse tree includes nodes for `Expr`, `Term`, `Factor`, and every operator token.

An **abstract syntax tree** (AST) strips away syntactic detail — parentheses, operator tokens, intermediate non-terminals — and keeps only the semantic structure. For `1 + 2 * 3`, the AST might be:

```
    Add
   /   \
  1    Mul
      /   \
     2     3
```

The parse tree says *how* the input was parsed. The AST says *what* it means.

## Why Alpaca Skips the Parse Tree

Alpaca's parser never materializes a parse tree. As the LR algorithm reduces productions, it immediately executes the semantic action (your `case` body) and pushes the result onto the parse stack. The intermediate grammar structure exists only in the sequence of shift/reduce steps — it is never stored as a tree object.

This means you choose what to produce at each reduction:

- **A value** (like `Double`) — compute the result inline, no tree needed
- **An AST node** (like `BrainAST`) — build a tree for later traversal

Both approaches use the same `rule` syntax. The difference is in what you return.

## Approach 1: Direct Computation

For simple languages, compute the result during parsing. The calculator from the [Expression Evaluator](../cookbook/expression-evaluator.md) does this:

```scala sc-hidden sc-name:ac-calc-lexer
import halotukozak.alpaca.*

val CalcLexer = lexer:
  case "\\+" => Token["\\+"]
  case x @ """(\d+\.\d*|\.\d+)""" => Token["float"](x.toDouble)
  case "\\s+" => Token.Ignored
```

```scala sc-compile-with:ac-calc-lexer
object CalcParser extends Parser:
  val root: Rule[Double] = rule:
    case Expr(v) => v

  val Expr: Rule[Double] = rule(
    "plus" { case (Expr(a), CalcLexer.`\\+`(_), Expr(b)) => a + b },
    { case CalcLexer.float(x) => x.value },
  )

given Resolutions[CalcParser.type] = resolutions(
  production.plus.before(CalcLexer.`\\+`),
)
```

Each reduction produces a `Double`. By the time parsing finishes, the result is a single number. No tree is ever built.

This works when:
- You only need one pass over the input
- The result type is simple (a number, a string, a boolean)
- Order of evaluation matches order of parsing (bottom-up)

## Approach 2: Building an AST

For complex languages, build an AST and process it separately. The BrainFuck interpreter does this:

```scala sc-name:ac-brain-ast
enum BrainAST:
  case Root(ops: List[BrainAST])
  case While(ops: List[BrainAST])
  case FunctionDef(name: String, ops: List[BrainAST])
  case FunctionCall(name: String)
  case Next, Prev, Inc, Dec, Print, Read
```

Each reduction produces an AST node instead of a computed value:

```scala sc-name:ac-brain-operation sc-compile-with:ac-brain-ast
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case "\\[" => Token["jumpForward"]
  case "\\]" => Token["jumpBack"]
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "\\(" => Token["functionOpen"]
  case "\\)" => Token["functionClose"]
  case "!" => Token["functionCall"]
  case "\\s+" => Token.Ignored

object BrainParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)

  val While: Rule[BrainAST] = rule:
    case (BrainLexer.jumpForward(_), Operation.List(stmts), BrainLexer.jumpBack(_)) =>
      BrainAST.While(stmts)

  val FunctionDef: Rule[BrainAST] = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionOpen(_), Operation.List(stmts), BrainLexer.functionClose(_)) =>
      BrainAST.FunctionDef(name.value, stmts)

  val FunctionCall: Rule[BrainAST] = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionCall(_)) =>
      BrainAST.FunctionCall(name.value)

  val Operation: Rule[BrainAST] = rule(
    { case BrainLexer.inc(_) => BrainAST.Inc },
    { case BrainLexer.dec(_) => BrainAST.Dec },
    { case While(whl) => whl },
    { case FunctionDef(fdef) => fdef },
    { case FunctionCall(call) => call },
  )
```

After parsing, the AST is a data structure you can traverse, transform, optimize, or interpret.

This works when:
- You need multiple passes (optimization, type checking, code generation)
- The language has control flow (loops, conditionals, functions)
- You want to separate parsing from evaluation

## The Visitor Pattern

Once you have an AST, the most common traversal pattern is a recursive match:

```scala sc-compile-with:ac-brain-ast
import scala.collection.mutable

class Memory(
  val cells: Array[Int] = new Array(256),
  var pointer: Int = 0,
  val functions: mutable.Map[String, List[BrainAST]] = mutable.Map.empty,
)

extension (ast: BrainAST)
  def eval(mem: Memory): Unit = ast match
    case BrainAST.Root(ops)  => ops.foreach(_.eval(mem))
    case BrainAST.Next       => mem.pointer = (mem.pointer + 1) % mem.cells.length
    case BrainAST.Prev       => mem.pointer = (mem.pointer - 1 + mem.cells.length) % mem.cells.length
    case BrainAST.Inc        => mem.cells(mem.pointer) = (mem.cells(mem.pointer) + 1) & 0xff
    case BrainAST.Dec        => mem.cells(mem.pointer) = (mem.cells(mem.pointer) - 1) & 0xff
    case BrainAST.Print      => print(mem.cells(mem.pointer).toChar)
    case BrainAST.Read       => mem.cells(mem.pointer) = scala.io.StdIn.readChar().toInt & 0xff
    case BrainAST.While(ops) => while mem.cells(mem.pointer) != 0 do ops.foreach(_.eval(mem))
    case BrainAST.FunctionDef(name, ops) => mem.functions += (name -> ops)
    case BrainAST.FunctionCall(name) =>
      mem.functions(name).foreach(_.eval(mem))
```

In Scala 3, sealed enums with pattern matching give you exhaustiveness checking — the compiler warns if you miss a case.

The same tree can be traversed multiple times: an evaluator interprets it, a pretty-printer formats it back to source, an optimizer rewrites subtrees (e.g., collapse consecutive `Inc`s), and a compiler emits bytecode.

## Choosing Between the Two

| Concern | Direct computation | AST |
|---------|-------------------|-----|
| Simplicity | Simpler — no intermediate data structure | Requires defining an enum/sealed trait |
| Multiple passes | Not possible — result is computed during the single parse | Natural — traverse the tree multiple times |
| Optimization | Not possible after parsing | Transform the tree before evaluation |
| Error reporting | Harder — error context is lost after reduction | Easier — AST nodes can carry source positions |
| Memory | Lower — no tree in memory | Higher — entire tree in memory |

For most real languages, building an AST is the right choice. Direct computation is a good fit for calculators, simple query languages, and configuration file parsers.

## Cross-links

- See [Semantic Actions](semantic-actions.md) for how Alpaca executes `case` bodies during reductions.
- See [Getting Started](../getting-started.md) for the complete BrainFuck AST and evaluator.
