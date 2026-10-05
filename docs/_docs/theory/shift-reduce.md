# The Shift-Reduce Loop

The shift-reduce loop is the heart of LR parsing. Every LR parser — regardless of whether it uses LR(0), LALR(1), or full LR(1) lookahead — executes the same fundamental loop: shift the next token onto a stack, or reduce the top of the stack to a non-terminal. This page traces that loop step by step for a concrete input.

## The Parse Stack

From Alpaca's `Parser.scala` runtime: the stack is kept as two parallel stacks, one of state indices (`stateStack`) and one of nodes (`nodeStack`) -- conceptually a stack of `(stateIndex, node)` pairs. The `stateIndex` is a number indexing into the pre-built parse table. The `node` is either a `Lexeme` (for shifted terminals) or a computed value (for reduced non-terminals, after the semantic action has been applied).

The parser starts with state 0 on an empty stack: `[0]`. Two actions drive the loop:

- **Shift(gotoState):** The parse table says "push the current token and transition to state `gotoState`." Stack grows by one entry.
- **Reduce(production):** The parse table says "pop `rhs.size` entries, call the semantic action on the popped values, push the result with a new goto state." Stack shrinks by `rhs.size - 1` (one entry for the reduced non-terminal).

## Parse Tables

Alpaca builds two tables at compile time:

1. **Parse table:** maps `(currentState, nextSymbol) → Shift(newState) | Reduce(production)`. The "next symbol" is either the next input token (for shift decisions) or the non-terminal just produced (for goto after a reduce — which state to push after the reduction).

2. **Action table:** maps `production → (ctx, children) => result`. This is your semantic action — the `=>` expression in a `rule` clause. On each reduce, the runtime calls the action for that production with the popped stack values.

The separation means the parse logic (when to shift or reduce) is entirely separate from the computation logic (what value to produce). Alpaca computes both tables at compile time from your grammar rules.

## A Simplified Grammar for this Trace

The trace below uses a minimal two-production grammar to keep the steps readable. The full CalcParser grammar (with MINUS, TIMES, DIVIDE, LPAREN/RPAREN rules) produces a larger automaton; the simplified grammar isolates the LR mechanics without the extra states.

```
Expr → NUMBER           (production 1)
Expr → Expr PLUS Expr   (production 2)
root → Expr             (production 3)
```

Note: This grammar is ambiguous and would produce a shift/reduce conflict in Alpaca's table construction — the parser could either shift `PLUS` or reduce `Expr → Expr PLUS Expr` at certain points. Conflict resolution is covered on the [Conflict Resolution](../conflict-resolution.md) page. For this trace, we follow one deterministic path to illustrate the mechanics.

## Parse Trace: `1 + 2`

The table below shows each step of parsing `1 + 2` with this grammar. Stack entries are written as `stateNumber·symbol(value)`. The input column shows remaining tokens plus `$` (end of input).

| Step | Stack | Remaining input | Action |
|------|-------|-----------------|--------|
| 1 | `[0]` | `NUMBER(1.0) PLUS NUMBER(2.0) $` | Shift NUMBER(1.0) → state 2 |
| 2 | `[0, 2·NUMBER(1.0)]` | `PLUS NUMBER(2.0) $` | Reduce Expr → NUMBER; pop 1, push Expr(1.0) |
| 3 | `[0, 3·Expr(1.0)]` | `PLUS NUMBER(2.0) $` | Shift PLUS → state 4 |
| 4 | `[0, 3·Expr(1.0), 4·PLUS]` | `NUMBER(2.0) $` | Shift NUMBER(2.0) → state 2 |
| 5 | `[0, 3·Expr(1.0), 4·PLUS, 2·NUMBER(2.0)]` | `$` | Reduce Expr → NUMBER; pop 1, push Expr(2.0) |
| 6 | `[0, 3·Expr(1.0), 4·PLUS, 5·Expr(2.0)]` | `$` | Reduce Expr → Expr PLUS Expr; pop 3, push Expr(3.0) |
| 7 | `[0, 3·Expr(3.0)]` | `$` | Reduce root → Expr; pop 1, push result |
| 8 | Accept | — | result: 3.0 |

**Step 1:** The parse table lookup `(state=0, symbol=NUMBER)` returns `Shift(2)`. The lexeme `NUMBER(1.0)` is pushed with state 2.

**Step 2:** The item `[Expr → NUMBER •, PLUS]` is complete — dot at end, lookahead PLUS matches next token. Reduce fires: pop 1 item (`NUMBER(1.0)`), call semantic action (the `case CalcLexer.NUMBER(n) => n.value` clause), produce `1.0`, push `Expr(1.0)` with goto state 3.

**Step 6:** Pop 3 items (`Expr(1.0)`, `PLUS`, `Expr(2.0)`). Call `(a, _, b) => a + b`. Result: `3.0`. Push `Expr(3.0)`.

**Step 7:** `root → Expr` reduces. The result `3.0` propagates up.

**Step 8:** The stack is back to state 0 with the root value — accept condition reached.

State numbers 0, 2, 3, 4, 5 are illustrative labels for this simplified 3-production grammar. The full CalcParser grammar generates more states; the actual state indices in Alpaca's generated parser differ. The structure of the trace — which actions occur and in what order — is what matters.

## LR(1) Items and Lookahead

The lookahead in each item determines when a reduce fires. Three example items in the dot notation used by Alpaca's `Item.scala`:

```
[Expr → • NUMBER, PLUS]        — start state: about to shift NUMBER
[Expr → NUMBER •, PLUS]        — NUMBER recognized; reduce if next token is PLUS
[Expr → Expr • PLUS Expr, $]   — Expr on stack; shift PLUS if it follows
```

In Step 2 of the trace, the item `[Expr → NUMBER •, PLUS]` is active. The lookahead `PLUS` matches the actual next token, so the reduce fires. If the next token were `$` instead (input `1` with no operator), the parser would use item `[Expr → NUMBER •, $]` to reduce — a different item with a different lookahead. This per-item lookahead is what LR(1) and Alpaca's LALR(1) have in common; see [Why LR?](why-lr.md) for how the two differ.

## Connection to Alpaca's Runtime

In Alpaca, this trace corresponds directly to the `loop()` function in `Parser.scala`. Each iteration either calls `ParseAction.Shift(gotoState)` — pushing the lexeme and new state — or `ParseAction.Reduction(production)` — popping `rhs.size` items, calling the action table entry, and pushing the computed value and goto state. The accept condition fires when `lhs == Symbol.Start` and the new state index is 0.

No parse tree object is ever constructed. Each reduce immediately applies the semantic action and pushes the typed result. This is why `CalcParser.parse(CalcLexer.tokenize("1 + 2").lexemes)` returns the `Double` `3.0` directly (as the `result` of the returned `(ctx, result)` tuple), not an intermediate tree.

The shift-reduce loop terminates in O(n) time. Every token is shifted exactly once and participates in at most one reduce per grammar production it belongs to. Since the parse table maps each `(state, symbol)` pair to a single action (shift or reduce), each iteration is a constant-time table lookup. No backtracking occurs — if a conflict exists, Alpaca reports it at compile time rather than exploring alternatives at runtime.

The typed result flows naturally from the bottom up: each reduce step calls a semantic action that combines the values of the right-hand side symbols. In the calculator example, `Expr → Expr PLUS Expr` calls `(a, _, b) => a + b`, returning a `Double`. That `Double` becomes the value associated with the new `Expr` entry on the stack, ready to be consumed by the next reduce. By the time the stack contains only the root non-terminal, the final typed value is already computed.

> **Definition — LR parse configuration:**
> A parse configuration is a pair (stack, input) where:
> - stack = [(s₀, v₀), (s₁, v₁), ..., (sₙ, vₙ)] is a sequence of (state, value) pairs
> - input = [t₁, t₂, ..., tₘ, $] is the remaining token stream with the EOF marker $
>
> A **shift** action on terminal t: push (s', t) where s' = parseTable(sₙ, t). Advance input past t.
> A **reduce** action by A → α (|α| = k): pop k pairs, let s' = parseTable(sₙ₋ₖ, A), push (s', action(α)).
> **Accept**: when the input is [$] and the reduction reaches the start production.

> **Compile-time processing:** The parse table that drives the shift/reduce loop is built by the `Parser` macro at compile time. Each `(state, symbol) → action` entry is computed from your grammar rules and baked into the generated object. At runtime, `parse(lexemes)` executes the pre-built table loop — no grammar analysis happens at runtime.

## Cross-links

- See [Why LR?](why-lr.md) for why LR parsing handles left-recursive grammars where top-down parsers fail.
- See [Context-Free Grammars](cfg.md) for the grammar that drives this parser.
- See [Conflict Resolution](../conflict-resolution.md) for how Alpaca resolves the shift/reduce conflict in the grammar above.
- See [Parser](../parser.md) for the complete `rule` DSL reference — how productions map to Alpaca syntax.
- See [The Compilation Pipeline](pipeline.md) for where the shift-reduce loop fits in the broader tokenize → parse → result flow.
- Next: [Conflicts and Disambiguation](conflicts.md) — why ambiguous grammars trigger shift/reduce conflicts
