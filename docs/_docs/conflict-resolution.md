# Conflict Resolution

LR parsing is deterministic: the parser always knows exactly what to do next -- or it reports a conflict at compile time. A conflict arises when the grammar is ambiguous in a way the LR algorithm cannot resolve on its own. Alpaca gives you the `before`/`after` DSL to resolve conflicts by declaring precedence relationships.

The BrainFuck grammar from [Getting Started](getting-started.md) has no conflicts -- all tokens are unambiguous. This page uses an arithmetic grammar to illustrate conflicts and their resolution.

<details>
<summary>Under the hood: compile-time resolution</summary>

When you define a `given Resolutions[MyParser.type] = resolutions(...)`, the Alpaca macro incorporates your precedence declarations into the LALR(1) parse table at compile time. Conflicts (shift/reduce, reduce/reduce) and cycles in the resolution rules are detected and reported as compile errors. At runtime, the resolved table executes deterministically.

</details>

## Understanding Conflicts

**Shift/reduce conflict:** the parser has matched a complete production but can also shift the next token. With `1 + 2 + 3`, after matching `1 + 2`, the parser can reduce `1 + 2` to `Expr` or shift the second `+`.

**Reduce/reduce conflict:** two productions can reduce the same token sequence. If `Integer -> Num` and `Float -> Num` are both valid and the parser has `Num` on the stack, it cannot decide which reduction to apply.

Both are detected at compile time. They do not manifest as runtime errors.

## Where resolutions Live

`Resolutions` is a type class, keyed by the parser type: `Resolutions[P <: Parser[?]]`. You provide an instance with `given Resolutions[MyParser.type] = resolutions(...)`, and Alpaca picks it up via implicit search when it builds `MyParser`'s parse table.

Because the `given` refers to `MyParser.type`, it must be declared **after** the full parser object, as a sibling declaration at the same scope -- not as a member inside the object:

```scala sc-hidden sc-name:cr-plus-lexer
import halotukozak.alpaca.*

val Lexer = lexer:
  case num @ "[0-9]+" => Token["NUMBER"](num.toInt)
  case "\\+" => Token["PLUS"]
  case "\\s+" => Token.Ignored
```

```scala sc-compile-with:cr-plus-lexer
object CalcParser extends Parser:              // object first, fully defined
  val Expr: Rule[Int] = rule(
    "plus" { case (Expr(a), Lexer.PLUS(_), Expr(b)) => a + b },
    { case Lexer.NUMBER(n) => n.value },
  )
  val root = rule:
    case Expr(e) => e

given Resolutions[CalcParser.type] = resolutions(  // given AFTER
  production.plus.before(Lexer.PLUS),
)
```

`production.name` inside `resolutions(...)` still refers to productions by name without qualification -- it is resolved by the inferred parser type `P`, not by textual scope. Only bare non-terminal references passed to `Production(symbols*)` need to be qualified with the parser object's name (see [The Production(symbols*) Selector](#the-productionsymbols-selector)).

## Reading the Error Messages

Without a `given Resolutions`, the `CalcParser` from [Where resolutions Live](#where-resolutions-live) does not compile:

```scala sc-compile-with:cr-plus-lexer sc:fail
object CalcParser extends Parser: // error
  val Expr: Rule[Int] = rule(
    "plus" { case (Expr(a), Lexer.PLUS(_), Expr(b)) => a + b },
    { case Lexer.NUMBER(n) => n.value },
  )
  val root = rule:
    case Expr(e) => e
```

The compiler reports a shift/reduce conflict:

```
Shift "PLUS" vs Reduce Expr -> Expr PLUS Expr (plus)
In situation like:
Expr PLUS Expr PLUS ...
Consider marking production Expr -> Expr PLUS Expr (plus) to be before or after "PLUS"
```

The fix is the rule the message suggests -- here, reducing `plus` before shifting another `PLUS` (left associativity):

```scala sc-compile-with:cr-plus-lexer
object CalcParser extends Parser:
  val Expr: Rule[Int] = rule(
    "plus" { case (Expr(a), Lexer.PLUS(_), Expr(b)) => a + b },
    { case Lexer.NUMBER(n) => n.value },
  )
  val root = rule:
    case Expr(e) => e

given Resolutions[CalcParser.type] = resolutions(
  production.plus.before(Lexer.PLUS),
)
```

## Naming Productions

To reference a production in `resolutions`, name it with a string literal placed before the `{ case ... }` block:

```scala sc-hidden sc-name:cr-plusminus-lexer
import halotukozak.alpaca.*

val Lexer = lexer:
  case num @ "[0-9]+" => Token["NUMBER"](num.toInt)
  case "\\+" => Token["PLUS"]
  case "-" => Token["MINUS"]
  case "\\s+" => Token.Ignored
```

```scala sc-compile-with:cr-plusminus-lexer
object CalcParser extends Parser:
  val Expr: Rule[Int] = rule(
    "plus"  { case (Expr(a), Lexer.PLUS(_), Expr(b))  => a + b },
    "minus" { case (Expr(a), Lexer.MINUS(_), Expr(b)) => a - b },
    { case Lexer.NUMBER(n) => n.value },   // unnamed -- not referenced in resolutions
  )
  val root = rule:
    case Expr(e) => e

given Resolutions[CalcParser.type] = resolutions(
  production.plus.before(Lexer.PLUS, Lexer.MINUS),
  production.minus.before(Lexer.PLUS, Lexer.MINUS),
)
```

The name must be a string literal placed immediately before the brace. Not all productions need names -- only those you reference in `resolutions`. Referencing an undefined name produces: _"Production with name 'typo' not found"_.

## The before/after DSL

Four resolution forms:

1. **`production.name.before(tokens*)`** -- prefer reducing this production over shifting those tokens.
2. **`production.name.after(tokens*)`** -- prefer shifting those tokens over reducing this production.
3. **`Token.before(productions*)`** -- prefer shifting this token over reducing those productions.
4. **`production.name.before(productions*)`** -- resolve reduce/reduce conflicts between productions.

Full example for a calculator:

```scala sc-hidden sc-name:cr-full-lexer
import halotukozak.alpaca.*

val Lexer = lexer:
  case num @ "[0-9]+" => Token["NUMBER"](num.toInt)
  case "\\+" => Token["PLUS"]
  case "-" => Token["MINUS"]
  case "\\*" => Token["TIMES"]
  case "/" => Token["DIVIDE"]
  case "\\s+" => Token.Ignored
```

```scala sc-compile-with:cr-full-lexer
object CalcParser extends Parser:
  val Expr: Rule[Int] = rule(
    "plus"  { case (Expr(a), Lexer.PLUS(_), Expr(b))  => a + b },
    "minus" { case (Expr(a), Lexer.MINUS(_), Expr(b)) => a - b },
    "times" { case (Expr(a), Lexer.TIMES(_), Expr(b)) => a * b },
    "div"   { case (Expr(a), Lexer.DIVIDE(_), Expr(b)) => a / b },
    { case Lexer.NUMBER(n) => n.value },
  )
  val root = rule:
    case Expr(e) => e

given Resolutions[CalcParser.type] = resolutions(
  // + and - are left-associative and have equal precedence
  production.plus.before(Lexer.PLUS, Lexer.MINUS),
  production.minus.before(Lexer.PLUS, Lexer.MINUS),
  // * and / bind tighter than + and -
  production.plus.after(Lexer.TIMES, Lexer.DIVIDE),
  production.minus.after(Lexer.TIMES, Lexer.DIVIDE),
  production.times.before(Lexer.TIMES, Lexer.DIVIDE, Lexer.PLUS, Lexer.MINUS),
  production.div.before(Lexer.TIMES, Lexer.DIVIDE, Lexer.PLUS, Lexer.MINUS),
)
```

Reading `production.plus.before(Lexer.PLUS, Lexer.MINUS)`: when the parser has reduced `plus` and the next token is `+` or `-`, prefer the reduction. This gives `+` left associativity.

### Transitivity

`before`/`after` constraints are transitive. If A is before B and B is before C, then A is before C. You only state direct relationships; the compiler derives the full order.

## The Production(symbols*) Selector

For unnamed productions, use `Production(symbols*)` to identify them by their right-hand side. Because `resolutions` is now declared *outside* the parser object (see [Where resolutions Live](#where-resolutions-live) above), non-terminals must be qualified with the parser object's name:

```scala sc-compile-with:cr-plusminus-lexer
import halotukozak.alpaca.Production as P

object CalcParser extends Parser:
  val Expr: Rule[Int] = rule(
    "plus" { case (Expr(a), Lexer.PLUS(_), Expr(b)) => a + b },
    { case (Expr(a), Lexer.MINUS(_), Expr(b)) => a - b },   // unnamed -- referenced via P(...) below
    { case Lexer.NUMBER(n) => n.value },
  )
  val root = rule:
    case Expr(e) => e

given Resolutions[CalcParser.type] = resolutions(
  production.plus.before(Lexer.PLUS, Lexer.MINUS),
  P(CalcParser.Expr, Lexer.MINUS, CalcParser.Expr).before(Lexer.PLUS, Lexer.MINUS),
)
```

Both `production.name` and `Production(symbols*)` can coexist in one `resolutions(...)` call.

## Token-Side Resolution

`before`/`after` can be called on a token directly:

```scala sc-hidden sc-name:cr-unary-lexer
import halotukozak.alpaca.*

val UnaryLexer = lexer:
  case "\\^" => Token["exp"]
  case num @ "[0-9]+" => Token["num"](num.toInt)
  case "\\s+" => Token.Ignored
```

```scala sc-compile-with:cr-unary-lexer
object UnaryParser extends Parser:
  val Expr: Rule[Int] = rule(
    "pow" { case (Expr(a), UnaryLexer.exp(_), Expr(b)) => math.pow(a, b).toInt },
    { case UnaryLexer.num(n) => n.value },
  )
  val root = rule:
    case Expr(e) => e

given Resolutions[UnaryParser.type] = resolutions(
  UnaryLexer.exp.before(production.pow),   // right-associative: 2^3^2 == 2^(3^2)
)
```

This is the token-side spelling of `production.pow.after(UnaryLexer.exp)`. Use whichever reads more naturally.

## Associativity

**Left-associative** (`1 + 2 + 3 = (1 + 2) + 3`): prefer reducing before shifting the same operator.

```scala sc-compile-with:cr-plus-lexer
object CalcParser extends Parser:
  val Expr: Rule[Int] = rule(
    "plus" { case (Expr(a), Lexer.PLUS(_), Expr(b)) => a + b },
    { case Lexer.NUMBER(n) => n.value },
  )
  val root = rule:
    case Expr(e) => e

given Resolutions[CalcParser.type] = resolutions(
  production.plus.before(Lexer.PLUS)   // reduce first -> left grouping
)
```

**Right-associative** (`a = b = c` groups as `a = (b = c)`): prefer shifting before reducing.

```scala sc-hidden sc-name:cr-assign-lexer
import halotukozak.alpaca.*

val AssignLexer = lexer:
  case num @ "[0-9]+" => Token["NUMBER"](num.toInt)
  case "=" => Token["ASSIGN"]
  case "\\s+" => Token.Ignored
```

```scala sc-compile-with:cr-assign-lexer
object AssignParser extends Parser:
  val Expr: Rule[Int] = rule(
    "assign" { case (AssignLexer.NUMBER(a), AssignLexer.ASSIGN(_), Expr(b)) => b },
    { case AssignLexer.NUMBER(n) => n.value },
  )
  val root = rule:
    case Expr(e) => e

given Resolutions[AssignParser.type] = resolutions(
  production.assign.after(AssignLexer.ASSIGN)  // shift first -> right grouping
)
```

## Conflict Cycle Detection

The compiler detects cycles in the transitive closure of constraints. A cycle (A before B before C before A) is contradictory and produces an "Inconsistent conflict resolution detected" error showing the full cycle path, reported at the rule that closes the cycle.

## Best Practices

- **Only resolve actual conflicts.** Add resolutions only for conflicts the compiler reports.
- **Use named productions.** They make resolutions readable and survive refactoring better than `Production(symbols*)`.
- **Think in terms of trees.** `production.plus.after(Lexer.TIMES)` gives `*` higher precedence than `plus`: the `*` operation ends up lower in the parse tree -- it binds tighter.

See [Parser](parser.md) for grammar rules and EBNF operators.
