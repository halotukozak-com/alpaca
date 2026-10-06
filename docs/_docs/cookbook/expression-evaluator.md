# Expression Evaluator

This guide builds a math expression evaluator supporting arithmetic (`+`, `-`, `*`, `/`), exponentiation (`**`), unary minus, parentheses, constants (`pi`), and functions (`sin`, `atan2`). It demonstrates Alpaca's operator precedence and conflict resolution.

**What you'll learn:** dynamic token names, named productions, the `before`/`after` DSL, and how to express a complete precedence hierarchy.

## The Lexer

```scala sc-name:calc-lexer
import halotukozak.alpaca.*

val CalcLexer = lexer:
  case "\\s+" => Token.Ignored
  case "#.*" => Token.Ignored
  case "\\*\\*" => Token["exp"]
  case literal @ ("\\+" | "-" | "\\*" | "/" | "\\(" | "\\)" | ",") =>
    Token[literal.type]
  case keyword @ ("pi" | "sin" | "atan2") =>
    Token[keyword.type]
  case x @ """(\d+\.\d*|\.\d+)([eE][+-]?\d+)?""" => Token["float"](x.toDouble)
  case x @ "\\d+" => Token["int"](x.toInt)
```

`"\\*\\*"` (exponentiation) and `"\\*"` (multiplication) can be declared in either order: the lexer takes the longest match, so `**` is always one `exp` token (see [How a Token Is Chosen](../lexer.md#how-a-token-is-chosen)).

## The Parser

Named productions (`"plus"`, `"times"`, etc.) let us reference specific alternatives in conflict resolution:

```scala sc-compile-with:calc-lexer sc:fail
import halotukozak.alpaca.*

object CalcParser extends Parser: // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error
  val root: Rule[Double] = rule:
    case Expr(v) => v

  val Expr: Rule[Double] = rule(
    "plus"   { case (Expr(a), CalcLexer.`\\+`(_), Expr(b)) => a + b },
    "minus"  { case (Expr(a), CalcLexer.`-`(_), Expr(b)) => a - b },
    "times"  { case (Expr(a), CalcLexer.`\\*`(_), Expr(b)) => a * b },
    "divide" { case (Expr(a), CalcLexer.`/`(_), Expr(b)) => a / b },
    "exp"    { case (Expr(a), CalcLexer.`exp`(_), Expr(b)) => math.pow(a, b) },
    "uminus" { case (CalcLexer.`-`(_), Expr(a)) => -a },
    "pi"     { case CalcLexer.pi(_) => math.Pi },
    "sin"    { case (CalcLexer.sin(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.sin(a) },
    "atan2"  {
      case (CalcLexer.atan2(_), CalcLexer.`\\(`(_), Expr(y), CalcLexer.`,`(_), Expr(x), CalcLexer.`\\)`(_)) =>
        math.atan2(y, x)
    },
    { case (CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => a },
    { case CalcLexer.float(x) => x.value },
    { case CalcLexer.int(n) => n.value.toDouble },
  )
```

Without conflict resolution, this grammar is ambiguous -- the compiler reports shift/reduce conflicts for every operator production (30 in total, one per `// error` marker above).

## Conflict Resolution

The parse table is built when `CalcParser` is compiled, so its `given Resolutions[CalcParser.type]` has to be declared together with it (see [Where resolutions Live](../conflict-resolution.md#where-resolutions-live)) -- resolving the grammar above means restating `CalcParser` with the `given` in one block:

```scala sc-name:calc-resolved sc-compile-with:calc-lexer
object CalcParser extends Parser:
  val root: Rule[Double] = rule:
    case Expr(v) => v

  val Expr: Rule[Double] = rule(
    "plus"   { case (Expr(a), CalcLexer.`\\+`(_), Expr(b)) => a + b },
    "minus"  { case (Expr(a), CalcLexer.`-`(_), Expr(b)) => a - b },
    "times"  { case (Expr(a), CalcLexer.`\\*`(_), Expr(b)) => a * b },
    "divide" { case (Expr(a), CalcLexer.`/`(_), Expr(b)) => a / b },
    "exp"    { case (Expr(a), CalcLexer.`exp`(_), Expr(b)) => math.pow(a, b) },
    "uminus" { case (CalcLexer.`-`(_), Expr(a)) => -a },
    "pi"     { case CalcLexer.pi(_) => math.Pi },
    "sin"    { case (CalcLexer.sin(_), CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => math.sin(a) },
    "atan2"  {
      case (CalcLexer.atan2(_), CalcLexer.`\\(`(_), Expr(y), CalcLexer.`,`(_), Expr(x), CalcLexer.`\\)`(_)) =>
        math.atan2(y, x)
    },
    { case (CalcLexer.`\\(`(_), Expr(a), CalcLexer.`\\)`(_)) => a },
    { case CalcLexer.float(x) => x.value },
    { case CalcLexer.int(n) => n.value.toDouble },
  )

given Resolutions[CalcParser.type] = resolutions(
  // Exponentiation: right-associative, highest binary precedence
  CalcLexer.exp.before(production.uminus, production.exp, production.times, production.divide),
  production.exp.before(CalcLexer.`\\*`, CalcLexer.`/`),

  // Unary minus binds tighter than * /
  production.uminus.before(CalcLexer.`\\*`, CalcLexer.`/`),

  // Multiplication/division: left-associative, higher than +/-
  production.times.before(CalcLexer.`\\*`, CalcLexer.`/`),
  production.divide.before(CalcLexer.`\\*`, CalcLexer.`/`),

  // Addition/subtraction: left-associative, lowest binary precedence
  production.plus.after(CalcLexer.`\\*`, CalcLexer.`/`),
  production.plus.before(CalcLexer.`\\+`, CalcLexer.`-`),
  production.minus.after(CalcLexer.`\\*`, CalcLexer.`/`),
  production.minus.before(CalcLexer.`\\+`, CalcLexer.`-`),
)
```

The precedence hierarchy from highest to lowest: `**` > unary `-` > `*` `/` > `+` `-`.

- `before(tokens)` = prefer reducing this production over shifting those tokens
- `after(tokens)` = prefer shifting those tokens over reducing this production
- `Token.before(productions)` = prefer shifting this token over reducing those productions

## Running It

```scala sc-compile-with:calc-resolved
val input = "sin(pi / 2) + 2 ** 3 * 4"
val lexemes = CalcLexer.tokenize(input).getOrThrow
val result = CalcParser.parse(lexemes).getOrThrow
println(result) // 33.0 (1.0 + 8.0 * 4.0)
```

## Exercises

- Add a modulo operator (`%`) with the same precedence as `*` and `/`
- Add variable assignment (`x = expr`) with right-associative binding
- Add `cos`, `tan`, and `sqrt` functions
