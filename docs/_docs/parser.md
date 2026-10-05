# Parser

The Alpaca parser transforms a `List[Lexeme]` into a typed result by matching token sequences against grammar rules. You define rules using pattern matching, and the macro builds an LALR(1) parse table at compile time.

<details>
<summary>Under the hood: compile-time table generation</summary>

When you define `object MyParser extends Parser`, the Alpaca macro:

1. Reads every `Rule` val declaration
2. Builds an LALR(1) parse table (states, transitions, actions)
3. Compiles semantic actions (your `case` bodies) into the action table
4. Reports grammar conflicts (shift/reduce, reduce/reduce) as compile errors

At runtime, `parse()` executes the precomputed table. No grammar analysis happens during parsing.

</details>

## Defining a Parser

Extend `Parser` for a stateless parser, or `Parser[Ctx]` to carry custom state through reductions. The required entry point is `val root: Rule[R]` -- the macro uses this as the start symbol.

```scala sc-hidden sc-name:brain-lexer-defs
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case ">" => Token["next"]
  case "<" => Token["prev"]
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case "\\." => Token["print"]
  case "," => Token["read"]
  case "\\[" => Token["jumpForward"]
  case "\\]" => Token["jumpBack"]
  case "." => Token.Ignored
  case "\n" => Token.Ignored

enum BrainAST:
  case Root(ops: List[BrainAST])
  case While(ops: List[BrainAST])
  case Next, Prev, Inc, Dec, Print, Read
```

```scala sc-name:brain-parser sc-compile-with:brain-lexer-defs
import halotukozak.alpaca.*

object BrainParser extends Parser:
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
    { case While(whl) => whl },
  )
```

The macro reads both `val` and `def` declarations. `val` is the recommended form for grammar rules, but `def` also works.

## Rules and Productions

A `Rule[R]` is a named non-terminal that produces values of type `R`. Use `rule` to define one or more productions.

**Single production** -- colon syntax:

```scala sc-compile-with:brain-lexer-defs
object SingleProductionParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)

  val Operation: Rule[BrainAST] = rule:
    case BrainLexer.inc(_) => BrainAST.Inc
```

**Multiple productions** -- argument list:

```scala sc-compile-with:brain-lexer-defs
object MultipleProductionsParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)

  val While: Rule[BrainAST] = rule:
    case (BrainLexer.jumpForward(_), Operation.List(stmts), BrainLexer.jumpBack(_)) =>
      BrainAST.While(stmts)

  val Operation: Rule[BrainAST] = rule(
    { case BrainLexer.inc(_) => BrainAST.Inc },     // single-symbol: direct match
    { case BrainLexer.dec(_) => BrainAST.Dec },
    { case While(whl) => whl },                      // non-terminal reference
  )
```

Multi-symbol productions match a tuple; single-symbol productions match directly (no parentheses). Each `{ case ... }` block must contain exactly one alternative.

### Multiline Actions

Rule bodies can span multiple statements. Use intermediate variables and return the final value:

```scala sc-hidden sc-name:func-lexer-defs
import halotukozak.alpaca.*
import scala.collection.mutable

val BrainLexer = lexer:
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "\\(" => Token["functionOpen"]
  case "\\)" => Token["functionClose"]
  case "!" => Token["functionCall"]
  case "\\s+" => Token.Ignored

enum BrainAST:
  case Root(ops: List[BrainAST])
  case FunctionDef(name: String, ops: List[BrainAST])
  case FunctionCall(name: String)
  case Inc, Dec

case class BrainParserCtx(
  functions: mutable.Set[String] = mutable.Set.empty,
) extends ParserCtx
```

```scala sc-compile-with:func-lexer-defs
object FunctionDefParser extends Parser[BrainParserCtx]:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)

  val FunctionDef: Rule[BrainAST] = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionOpen(_),
          Operation.List(ops), BrainLexer.functionClose(_)) =>
      val funcName = name.value
      require(ctx.functions.add(funcName), s"Function $funcName already defined")
      BrainAST.FunctionDef(funcName, ops)

  val Operation: Rule[BrainAST] = rule(
    { case BrainLexer.inc(_) => BrainAST.Inc },
    { case FunctionDef(fdef) => fdef },
  )
```

### Named Productions with Special Characters

Production names can contain hyphens, dots, spaces, or any other character that is not a valid Scala identifier. Access them with backtick quoting in `resolutions`:

```scala sc-hidden sc-name:named-prod-lexer
import halotukozak.alpaca.*

val Lexer = lexer:
  case "\\+" => Token["PLUS"]
  case "<<" => Token["SHL"]
  case "if" => Token["IF"]
  case "then" => Token["THEN"]
  case x @ "[0-9]+" => Token["NUM"](x.toInt)
  case "\\s+" => Token.Ignored
```

```scala sc-compile-with:named-prod-lexer
object MyParser extends Parser:
  val root: Rule[Int] = rule:
    case Expr(e) => e

  val Expr: Rule[Int] = rule(
    "left-add" { case (Expr(a), Lexer.PLUS(_), Expr(b)) => a + b },
    "shift.left" { case (Expr(a), Lexer.SHL(_), Expr(b)) => a << b },
    "if then" { case (Lexer.IF(_), Expr(c), Lexer.THEN(_), Expr(t)) => if c != 0 then t else 0 },
    { case Lexer.NUM(n) => n.value },
  )

given Resolutions[MyParser.type] = resolutions(
  production.`left-add`.before(Lexer.PLUS, Lexer.SHL, Lexer.THEN),
  production.`shift.left`.before(Lexer.PLUS, Lexer.SHL, Lexer.THEN),
  production.`if then`.before(Lexer.PLUS, Lexer.SHL, Lexer.THEN),
)
```

## Terminal and Non-Terminal Matching

### Terminals

Use `MyLexer.TOKEN(binding)` to match a terminal. The binding is a `Lexeme` -- use `binding.value` for the extracted value:

```scala sc-hidden sc-name:terminal-lexer-defs
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "\\[" => Token["jumpForward"]

val MyLexer = lexer:
  case "\\+" => Token["\\+"]
```

```scala sc-compile-with:terminal-lexer-defs
object TerminalMatchingParser extends Parser:
  val root: Rule[Any] = rule(
    // Value-bearing: use binding.value
    { case BrainLexer.functionName(name) => name.value },  // name.value: String

    // Structural: discard the binding
    { case BrainLexer.jumpForward(_) => "loop start" },

    // Backtick quoting for special-character token names (e.g., if a lexer defines Token["\\+"])
    { case MyLexer.`\\+`(_) => "plus" },
  )
```

### Non-Terminals

Use the rule name in unapply position. The binding has exactly type `R` from `Rule[R]`:

```scala sc-compile-with:brain-lexer-defs
object NonTerminalMatchingParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)

  val While: Rule[BrainAST] = rule:
    // Recursive reference
    case (BrainLexer.jumpForward(_), Operation.List(stmts), BrainLexer.jumpBack(_)) =>
      BrainAST.While(stmts)

  val Operation: Rule[BrainAST] = rule(
    { case BrainLexer.inc(_) => BrainAST.Inc },
    // While(whl) extracts the BrainAST produced by the While rule
    { case While(whl) => whl },   // whl: BrainAST
  )
```

## EBNF Operators

`.Option`, `.List`, and `.SeparatedBy` on any `Rule[R]` express optional, repeated, and delimiter-separated symbols without hand-written recursion.

**`.List`** produces `List[R]`. The BrainFuck parser uses this heavily -- the root rule matches zero or more operations:

```scala sc-compile-with:brain-lexer-defs
object ListOperatorParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)
    // stmts: List[BrainAST] -- zero or more operations

  val Operation: Rule[BrainAST] = rule:
    case BrainLexer.inc(_) => BrainAST.Inc
```

**`.Option`** produces `Option[R]`:

```scala sc-compile-with:func-lexer-defs
object OptionOperatorParser extends Parser:
  val root = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionCall.Option(call)) =>
      (name.value, call)   // call: Option[Lexeme]
```

**`.SeparatedBy[Separator]`** produces `List[R | SepValue[Separator]]` for zero or more items interleaved with a separator, where `SepValue[Separator]` is the separator's runtime value type (`Lexeme` for token separators, the bound result type for rule separators). Pass a token (as a type) or a rule (as `.type`) for the separator:

```scala sc-hidden sc-name:sep-lexer-defs
import halotukozak.alpaca.*

val MyLexer = lexer:
  case "\\s+" => Token.Ignored
  case "," => Token[","]
  case x @ "[0-9]+" => Token["NUM"](x.toInt)
```

```scala sc-compile-with:sep-lexer-defs
object SeparatedByParser extends Parser:
  val Num: Rule[Int] = rule:
    case MyLexer.NUM(n) => n.value

  val root: Rule[List[Any]] = rule:
    case Num.SeparatedBy[MyLexer.`,`](items) => items
    // for "1,2,3": List(1, <,>, 2, <,>, 3)
```

All three operators work on terminals too, not only rules.

## Parsing Input

Call `parse(lexemes)` where `lexemes` comes from `tokenize()`:

```scala sc-hidden sc-name:brain-eval-defs sc-compile-with:brain-parser
class Memory(
  val cells: Array[Int] = new Array(256),
  var pointer: Int = 0,
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
    case BrainAST.While(ops) => while mem.cells(mem.pointer) != 0 do ops.foreach(_.eval(mem))
```

```scala sc-name:brain-tokenize sc-compile-with:brain-eval-defs
val (_, lexemes) = BrainLexer.tokenize("++[>+<-]")
val parsed = BrainParser.parse(lexemes)
// parsed: Result[ParserCtx.Empty, BrainAST, ParseError]
```

`parse()` does not throw when the input does not match the grammar. It returns a `Result`, which is one of two cases, both carrying the parser context (`ctx`) as it was when parsing ended:

- `Result.Success(ctx, value)` -- the input matched, and `value` is what the root rule produced (a `BrainAST` here);
- `Result.Failure(ctx, recovered, errors)` -- the input did not match; `errors` is a non-empty list of `ParseError`s. `recovered` is a value produced anyway by an error-handling strategy that skips past errors; the parser does not have one yet, so here it is always `None`.

Match on it to handle both outcomes:

```scala sc-compile-with:brain-tokenize
parsed match
  case Result.Success(_, ast) => ast.eval(Memory())
  case Result.Failure(_, _, errors) => errors.foreach(error => println(error.message))
```

When failing is not an option you want to handle at that point, `getOrThrow` returns the value or throws a `ParserException` holding the errors, and `toOption` / `toEither` convert the result:

```scala sc-compile-with:brain-tokenize
parsed.getOrThrow.eval(Memory())  // the AST, or throws a ParserException
parsed.toOption                   // Some(ast), or None
parsed.toEither                   // Right(ast), or Left(errors)
```

A `ParseError` is plain data: it carries the lexeme the parser could not accept (`unexpected`) and the token names the grammar would have accepted there (`expected`, with `"$"` standing for the end of the input). Its `message` also gives the token's line and column when the lexer tracks them, e.g. for `1 + + 2` in a grammar of numbers and `+`:

```
Unexpected PLUS "+" at line 1, column 5. Expected one of: NUMBER
```

```scala sc-compile-with:brain-tokenize
BrainParser.parse(BrainLexer.tokenize("[+").lexemes) match
  case Result.Failure(_, _, errors) =>
    println(errors.head.message)    // Unexpected end of input. Expected one of: ...
    println(errors.head.expected)   // the token names the grammar would have accepted
  case Result.Success(_, _) => ()
```

The parser currently stops at the first error, so `errors` has one element; it is a list so that error recovery can report more than one without changing the API.

`map` transforms the value, and `flatMap` runs the next stage on it, keeping the errors of both stages in input order.

## Conflict Resolution

Ambiguous grammars produce compile-time errors. The BrainFuck grammar has no conflicts (all tokens are unambiguous), but arithmetic grammars do. See [Conflict Resolution](conflict-resolution.md) for the full `before`/`after` DSL.

Quick example:

```scala sc-hidden sc-name:calc-quick-lexer
import halotukozak.alpaca.*

val CalcLexer = lexer:
  case "\\+" => Token["PLUS"]
  case x @ "[0-9]+" => Token["NUMBER"](x.toInt)
  case "\\s+" => Token.Ignored
```

```scala sc-compile-with:calc-quick-lexer
object CalcParser extends Parser:
  val Expr: Rule[Double] = rule(
    "plus" { case (Expr(a), CalcLexer.PLUS(_), Expr(b)) => a + b },
    { case CalcLexer.NUMBER(n) => n.value.toDouble },
  )
  val root = rule:
    case Expr(e) => e

given Resolutions[CalcParser.type] = resolutions(
  production.plus.before(CalcLexer.PLUS),  // left-associative
)
```

`Resolutions` is a type class: the `given` must be declared **after** the parser object, as a sibling declaration, not as a member inside it. See [Conflict Resolution](conflict-resolution.md#where-resolutions-live) for details.

See [Parser Context](parser-context.md) for custom state, [Extractors](extractors.md) for all pattern forms, and [Debug Settings](debug-settings.md) for compile-time debugging.
