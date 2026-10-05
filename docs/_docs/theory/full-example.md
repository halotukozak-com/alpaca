# Full Calculator Example

The preceding theory pages have built up each component of the compiler pipeline: tokens and lexical analysis, context-free grammars, LR parsing mechanics, conflict resolution, and semantic actions. This page assembles all the pieces into a working arithmetic calculator — the same grammar used throughout the tutorial, now fully resolved and evaluating. Follow the steps below from grammar definition to the evaluated result `7.0`.

## Step 1: The Lexer

CalcLexer tokenizes arithmetic expressions into the seven token classes introduced in [Tokens and Lexemes](tokens.md).

```scala sc-name:calc-lexer
import halotukozak.alpaca.*

val CalcLexer = lexer:
  case num @ "[0-9]+(\\.[0-9]+)?" => Token["NUMBER"](num.toDouble)
  case "\\+" => Token["PLUS"]
  case "-" => Token["MINUS"]
  case "\\*" => Token["TIMES"]
  case "/" => Token["DIVIDE"]
  case "\\(" => Token["LPAREN"]
  case "\\)" => Token["RPAREN"]
  case "\\s+" => Token.Ignored
```

## Step 2: The Grammar

The calculator grammar (from [Context-Free Grammars](cfg.md)) defines arithmetic expressions with four binary operators and parentheses:

```
Expr  → Expr PLUS Expr
      | Expr MINUS Expr
      | Expr TIMES Expr
      | Expr DIVIDE Expr
      | LPAREN Expr RPAREN
      | NUMBER

root  → Expr
```

This grammar is ambiguous — the expression `1 + 2 * 3` can be parsed in two ways depending on which `Expr` is expanded first (see [Context-Free Grammars](cfg.md) for the parse tree, and [Conflicts & Disambiguation](conflicts.md) for the theory). Mapping it directly to Alpaca without conflict resolution causes a compile error.

## Step 3: The First Attempt — Compile Error

The bare CalcParser definition — grammar productions with semantic actions but no conflict resolution — triggers a compile error:

```scala sc-compile-with:calc-lexer sc:fail
import halotukozak.alpaca.*

object CalcParser extends Parser: // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error // error
  val Expr: Rule[Double] = rule(
    "plus"  { case (Expr(a), CalcLexer.PLUS(_),   Expr(b)) => a + b },
    "minus" { case (Expr(a), CalcLexer.MINUS(_),  Expr(b)) => a - b },
    "times" { case (Expr(a), CalcLexer.TIMES(_),  Expr(b)) => a * b },
    "div"   { case (Expr(a), CalcLexer.DIVIDE(_), Expr(b)) => a / b },
    { case (CalcLexer.LPAREN(_), Expr(e), CalcLexer.RPAREN(_)) => e },
    { case CalcLexer.NUMBER(n) => n.value },
  )
  val root: Rule[Double] = rule:
    case Expr(v) => v
// ↑ Compile error: shift/reduce conflicts
```

The compiler reports one shift/reduce conflict per (operator, production) pair -- 16 in total, one per `// error` marker above. The first of them:

```
Shift "PLUS" vs Reduce Expr -> Expr PLUS Expr (plus)
In situation like:
Expr PLUS Expr PLUS ...
Consider marking production Expr -> Expr PLUS Expr (plus) to be before or after "PLUS"
```

The parser does not know whether `1 + 2 + 3` should reduce `1 + 2` first (left-associative) or shift the second `+` first. This is a shift/reduce conflict — both actions are valid for the same parse state and lookahead. See [Conflicts & Disambiguation](conflicts.md) for the formal theory.

## Step 4: Adding Conflict Resolution

Adding a `given Resolutions[CalcParser.type]` declares which action wins in each conflict state. The full resolution set for the calculator encodes standard BODMAS precedence (`*` and `/` before `+` and `-`) and left-associativity for all four operators. The `given` is declared *after* the parser object, as a sibling declaration at the same scope -- see [Conflict Resolution](../conflict-resolution.md#where-resolutions-live) for why:

```scala sc-name:full-example-parser sc-compile-with:calc-lexer
import halotukozak.alpaca.*

object CalcParser extends Parser:
  val Expr: Rule[Double] = rule(
    "plus"  { case (Expr(a), CalcLexer.PLUS(_),   Expr(b)) => a + b },
    "minus" { case (Expr(a), CalcLexer.MINUS(_),  Expr(b)) => a - b },
    "times" { case (Expr(a), CalcLexer.TIMES(_),  Expr(b)) => a * b },
    "div"   { case (Expr(a), CalcLexer.DIVIDE(_), Expr(b)) => a / b },
    { case (CalcLexer.LPAREN(_), Expr(e), CalcLexer.RPAREN(_)) => e },
    { case CalcLexer.NUMBER(n) => n.value },
  )
  val root: Rule[Double] = rule:
    case Expr(v) => v

given Resolutions[CalcParser.type] = resolutions(
  // + and - are left-associative with equal precedence
  production.plus.before(CalcLexer.PLUS, CalcLexer.MINUS),
  production.plus.after(CalcLexer.TIMES, CalcLexer.DIVIDE),
  production.minus.before(CalcLexer.PLUS, CalcLexer.MINUS),
  production.minus.after(CalcLexer.TIMES, CalcLexer.DIVIDE),
  // * and / are left-associative; bind tighter than + and -
  production.times.before(CalcLexer.TIMES, CalcLexer.DIVIDE, CalcLexer.PLUS, CalcLexer.MINUS),
  production.div.before(CalcLexer.TIMES, CalcLexer.DIVIDE, CalcLexer.PLUS, CalcLexer.MINUS),
)
```

Key decisions in the resolution set:

- `production.plus.before(PLUS, MINUS)` — after reducing `a + b`, do not shift another `+` or `-`. This gives `+` left-associativity: `1 + 2 + 3` = `(1 + 2) + 3`.
- `production.plus.after(TIMES, DIVIDE)` — prefer shifting `*` or `/` over reducing `+`. This gives `*`/`/` higher precedence: `1 + 2 * 3` shifts `*` before completing `1 + ...`.
- `production.times.before(TIMES, DIVIDE, PLUS, MINUS)` — after reducing `a * b`, do not shift any operator. `*` and `/` bind tightest.

For the full conflict resolution DSL — including `Production(symbols*)` selector, token-side resolution, cycle detection, and the ordering constraint — see [Conflict Resolution](../conflict-resolution.md).

## Step 5: Running the Calculator

With conflict resolution in place, the compiler builds the LALR(1) parse table without errors. The parser is ready:

```scala sc-compile-with:full-example-parser
val (_, lexemes) = CalcLexer.tokenize("1 + 2 * 3")
val (_, result)  = CalcParser.parse(lexemes)
// result: Double | Null = 7.0   (not 9.0 — * binds tighter than +)

val (_, l2) = CalcLexer.tokenize("(1 + 2) * 3")
val (_, r2) = CalcParser.parse(l2)
// r2: Double | Null = 9.0       (parentheses override precedence)

// result is typed Double | Null:
if result != null then println(result)
```

`1 + 2 * 3 = 7.0` (not 9.0) confirms that the `times`/`div` resolutions give `*` higher precedence than `+`. Parentheses `(1 + 2) * 3 = 9.0` override precedence as expected. The result is typed `Double | Null`, so check it (or use `.nn`) before using it as a `Double`. Input that the grammar does not match, such as `1 + * 2`, makes `parse()` throw a `ParseError` instead; see [Parser](../parser.md#parsing-input).

## Step 6: Semantic Action Trace

To see how `1 + 2 * 3 = 7.0` is computed, trace the semantic actions fired during the parse:

```
Reduce NUMBER(1.0) → Expr(1.0)                  action: n.value = 1.0
Shift PLUS
Reduce NUMBER(2.0) → Expr(2.0)                  action: n.value = 2.0
Shift TIMES
Reduce NUMBER(3.0) → Expr(3.0)                  action: n.value = 3.0
Reduce Expr(2.0) TIMES Expr(3.0) → Expr(6.0)   action: a * b = 6.0
Reduce Expr(1.0) PLUS  Expr(6.0) → Expr(7.0)   action: a + b = 7.0
Reduce root → Expr(7.0)                         result: 7.0
```

The `times` conflict resolution caused the parser to reduce `2 * 3` before completing `1 + ...`. Each reduce step calls the corresponding semantic action immediately — no parse tree object is ever constructed (see [Semantic Actions](semantic-actions.md)). The typed `Double` result propagates upward at each step.

## Formal Definition

> **Definition — Syntax-Directed Calculator:**
> A syntax-directed calculator is a grammar G = (V, Σ, R, S) together with
> a conflict resolution order ≺ on R and Σ, and semantic actions fᵣ for each r ∈ R.
> The parser reduces deterministically by the action preferred under ≺,
> and each fᵣ maps the Double values of the right-hand side symbols to a Double.
> The value of the start symbol is the arithmetic result.

## What Compile Time Does

> **Compile-time processing:** Every part of CalcParser shown above is processed at compile time. The `lexer` macro compiles the token patterns and generates the tokenizer. The `extends Parser` macro reads the `rule` declarations, builds the complete LALR(1) parse table, incorporates the `resolutions` priority rules, and reports any conflicts immediately. At runtime, `tokenize()` and `parse()` execute the pre-built tables — no grammar analysis happens at runtime.

## Theory to Code

Each piece of the CalcParser traces back to a theory concept:

| What you wrote | Theory behind it |
|---|---|
| `val CalcLexer = lexer:` | Lexical analysis, regex → DFA — see [The Lexer: Regex to Finite Automata](lexer-fa.md) |
| BNF grammar in `rule(...)` | Context-free grammars — see [Context-Free Grammars](cfg.md) |
| `extends Parser` generates LALR(1) table | LR parse table construction — see [Why LR?](why-lr.md) |
| Shift/reduce loop | LR parse mechanics — see [Shift-Reduce Parsing](shift-reduce.md) |
| Shift/reduce conflict compile error | Grammar ambiguity — see [Conflicts & Disambiguation](conflicts.md) |
| `given Resolutions[CalcParser.type] = resolutions(...)` | Conflict resolution — see [Conflict Resolution](../conflict-resolution.md) |
| `case (Expr(a), ...) => a + b` | Semantic actions — see [Semantic Actions](semantic-actions.md) |
| `parse()` returns `7.0: Double` | Typed results via S-attributed translation |

## Cross-links

- [Tokens and Lexemes](tokens.md) — CalcLexer's token definitions
- [Context-Free Grammars](cfg.md) — the calculator grammar and parse trees
- [Why LR?](why-lr.md) — why LR parsing was chosen over LL alternatives
- [Shift-Reduce Parsing](shift-reduce.md) — the shift/reduce loop step by step
- [Conflicts & Disambiguation](conflicts.md) — why conflicts arise and the priority model
- [Semantic Actions](semantic-actions.md) — how typed values are computed during reduce
- [Conflict Resolution](../conflict-resolution.md) — the complete `before`/`after` DSL reference
- [Parser](../parser.md) — the `rule` DSL and `parse()` usage
- [Extractors](../extractors.md) — all extractor forms for terminals and non-terminals
