# Semantic Actions

A parser recognizes whether an input string belongs to a grammar — it accepts or rejects. But most programs need to *compute* something from the input, not just verify it. Semantic actions bridge structure and computation: they attach a computation to each production rule, so that the parser produces a typed value as a direct outcome of parsing, rather than a parse tree.

## Syntax-Directed Translation

A *semantic action* is a function associated with a production rule. Each time the parser reduces by a production A → α, the semantic action for that production is called with the values of the right-hand side symbols, producing a value for A.

This is the *S-attributed translation scheme*: each non-terminal has one *synthesized attribute* — a value computed bottom-up from its children's values. Values flow upward: leaves (terminals) contribute raw values; each reduction combines child values into a parent value.

For the calculator: every non-terminal produces a `Double`. Leaves (NUMBER tokens) contribute `n.value` (a Double from the lexer). Each binary operator reduction computes `a op b`.

No top-down information flow is needed — the calculator is purely bottom-up. Each value is computed from the immediately reduced children. (For stateful parsing where context flows from parent to child, see [Parser](../parser.md).)

> **Definition — Semantic Action:**
> Given a production A → X₁ X₂ ... Xₙ, a semantic action is a function
> f(v₁, v₂, ..., vₙ) → vₐ
> where vᵢ is the value associated with symbol Xᵢ (a token's extracted value or a
> non-terminal's synthesized value), and vₐ is the synthesized value for A.
>
> A grammar with semantic actions on every production is a *syntax-directed definition*.
> The final result is the value synthesized at the start symbol.

## Semantic Actions in Alpaca

In Alpaca, each production rule is expressed as a `case` clause in a `rule(...)` call. The left side of `=>` is the pattern (matching the production's right-hand side symbols). The right side of `=>` IS the semantic action.

The `=>` expression receives the extracted values of the matched symbols and computes the synthesized value for the left-hand side non-terminal.

The explicit correspondence between production, pattern, and action:

```
Production:      Expr → Expr PLUS Expr
Pattern:         (Expr(a), CalcLexer.PLUS(_), Expr(b))
Semantic action: a + b
Result type:     Double
```

When the parser reduces by this production, it pops `Expr(a)`, `PLUS(_)`, `Expr(b)` from the stack, evaluates `a + b`, and pushes the resulting `Double` as the new `Expr` value.

## The Extractor Pattern

Alpaca's `case` patterns implement semantic action notation through three extractor forms:

- `Expr(a)` — non-terminal extractor: matches a reduced `Expr` value; `a` has type `Double` (the `Rule[Double]` result type)
- `CalcLexer.PLUS(_)` — terminal extractor: matches the PLUS token; `_` discards the lexeme value (PLUS carries Unit — it is a structural token)
- `CalcLexer.NUMBER(n)` — terminal extractor with value: `n` is a `Lexeme`; `n.value: Double` is the number value extracted by the lexer

The semantic action table for all CalcParser productions:

| Production | Pattern | Action | Result type |
|---|---|---|---|
| Expr → Expr PLUS Expr | `(Expr(a), CalcLexer.PLUS(_), Expr(b))` | `a + b` | Double |
| Expr → Expr MINUS Expr | `(Expr(a), CalcLexer.MINUS(_), Expr(b))` | `a - b` | Double |
| Expr → Expr TIMES Expr | `(Expr(a), CalcLexer.TIMES(_), Expr(b))` | `a * b` | Double |
| Expr → Expr DIVIDE Expr | `(Expr(a), CalcLexer.DIVIDE(_), Expr(b))` | `a / b` | Double |
| Expr → LPAREN Expr RPAREN | `(CalcLexer.LPAREN(_), Expr(e), CalcLexer.RPAREN(_))` | `e` | Double |
| Expr → NUMBER | `CalcLexer.NUMBER(n)` | `n.value` | Double |
| root → Expr | `Expr(v)` | `v` | Double |

For the complete extractor reference — all extractor forms, EBNF operators (`.Option`, `.List`), Lexeme fields — see [Extractors](../extractors.md).

## No Parse Tree

In Alpaca, the parse tree is an *implicit* data structure. It exists conceptually — the sequence of reduce steps IS the parse tree traversal, bottom-up — but it is never materialized as an object.

From `Parser.scala` `loop()`: on each `Reduction(prod)`, the runtime pops `rhs.size` items, calls the action function immediately (`tables.actionTable(prod)(ctx, children)`), and pushes the typed result. No tree node is constructed.

The action table entry is of type `(Ctx, Seq[Any]) => Any` — a function applied per production on each reduce. The typed value (a `Double` for CalcParser) is pushed directly onto the stack. The `Seq[Any]` argument holds the popped stack values; the action function casts and uses them according to the pattern match compiled into it at build time.

This is why `CalcParser.parse(lexemes)` returns a `Result[Ctx, Double, ParseError]` holding the computed `Double` — not a tree. The semantic actions produce the final value during the parse itself.

The calculator's semantic actions therefore operate as a fold over the parse structure: each reduce step folds the children's values into the parent's value, bottom-up, until the root value is the final result.

## Typed Results

Each `Rule[R]` has a declared result type `R`. The semantic action for every production of that rule must return a value of type `R`. Scala's type checker enforces this at compile time.

For `Rule[Double]`: every `case` clause's `=>` expression must evaluate to `Double`. `a + b` where `a: Double` and `b: Double` returns `Double`. `n.value` for a NUMBER token is `Double` (CalcLexer defines `Token["NUMBER"](num.toDouble)`).

The final result type is the type declared for `root`. For CalcParser: `val root: Rule[Double]`, so `parse()` returns a `Result[Ctx, Double, ParseError]`: a `Result.Success` with the `Double`, or, for input that does not match the grammar, a `Result.Failure` listing the `ParseError`s.

> **Compile-time processing:** Alpaca collects every `{ case pattern => expression }` block at compile time, extracts the action function, and stores it in the action table indexed by production. At runtime, `parse(lexemes)` looks up the action for each reduction and calls it directly — the Scala type checker has already verified that each action returns the declared `Rule[R]` type.

## Cross-links

- See [Shift-Reduce Parsing](shift-reduce.md) for the reduce steps that trigger semantic actions and how each step calls the action function.
- See [Conflicts & Disambiguation](conflicts.md) for how conflict resolution ensures each reduce is unambiguous, so the correct semantic action is always called.
- See [Extractors](../extractors.md) for the complete extractor reference (terminal, non-terminal, EBNF, Lexeme fields).
- See [Parser](../parser.md) for the `rule` DSL reference and `parse()` return type.
- Next: [Full Example](full-example.md) — the complete CalcParser with all semantic actions assembled and running.
