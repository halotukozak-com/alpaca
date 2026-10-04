# Why LR Parsing

Not every parsing strategy handles every grammar. Top-down parsers are intuitive but stumble on the natural structure of
arithmetic expressions. LR parsing was developed specifically to handle the grammars that arise in practice — including
left-recursive grammars like the one that drives CalcParser.

## Two Approaches to Parsing

Two broad families of parsing algorithms exist — top-down (LL) and bottom-up (LR). Both read input left to right; they
differ in how they build structure. Top-down parsers predict which production to expand next, working from the start
symbol downward. Bottom-up parsers recognize completed right-hand sides and reduce them, working from leaves upward
toward the start symbol.

## Top-Down Parsing and Left Recursion

An LL parser predicts which production to apply by looking at the current non-terminal and the next input token. It then
expands that production and tries to match each symbol in the right-hand side against the input, left to right.

The fatal problem is left recursion. When a grammar contains `Expr → Expr PLUS Expr`, an LL parser processing input
`1 + 2` must predict what to do with `Expr`. The first alternative begins with `Expr` itself — so the parser expands
`Expr` again, which immediately tries to expand `Expr` again, into an infinite loop before consuming any input.

```
Parse "1 + 2" top-down, current symbol: Expr
  Predict Expr → Expr PLUS Expr   (first alternative)
    Predict Expr → Expr PLUS Expr   (Expr again — infinite loop ↻)
      Predict Expr → Expr PLUS Expr   (...)
```

No amount of lookahead fixes this. The problem is structural: to make progress, the parser must consume some input, but
the left-recursive production demands expanding the same non-terminal before touching any terminal.

Right-recursive grammars are the LL workaround: `Expr → Term PLUS Expr` would work for LL, but this makes addition
right-associative — `1 + 2 + 3` evaluates as `1 + (2 + 3)`. Natural arithmetic is left-associative: `(1 + 2) + 3`.
Rewriting the grammar to right-recursive form changes the language's semantics and obscures its natural structure.

## LR Parsing: Bottom-Up

LR parsers take the opposite approach. They shift tokens onto a stack and watch for a complete right-hand side to appear
on top of the stack — at which point they *reduce*: pop the right-hand side, call the associated semantic action, and
push the resulting non-terminal. This is the shift-reduce loop that gives LR its character.

Left recursion is not a problem for LR parsers. When processing `1 + 2 + 3`, an LR parser shifts `1`, recognizes it as
`Expr → NUMBER`, reduces, then shifts `+` and `2`, reduces `Expr → NUMBER`, then reduces `Expr → Expr PLUS Expr` —
producing `(1 + 2)` first. The `+ 3` step repeats the pattern, yielding `((1 + 2) + 3)`. Left associativity falls out
naturally, without grammar rewriting. See [Shift-Reduce Parsing](shift-reduce.md) for the step-by-step trace.

## The LR Family

Several algorithms implement the LR approach, differing in how much lookahead they track per parse state. More lookahead
means more resolving power (fewer conflicts) but more states. The family in ascending power:

| Algorithm | Lookahead per item                          | State count       | Notes                                    |
|-----------|---------------------------------------------|-------------------|------------------------------------------|
| LR(0)     | None (reduce always)                        | Smallest          | Too weak for most real grammars          |
| SLR(1)    | FOLLOW sets (global per non-terminal)       | Same as LR(0)     | Better, still limited                    |
| LALR(1)   | Per-state lookahead (merged item-set cores) | Same as LR(0)/SLR | Most common in practice (yacc, Bison)    |
| LR(1)     | Per-item lookahead (full)                   | Largest           | Most powerful; fewest spurious conflicts |

Alpaca uses **LALR(1)**: the same lookahead-driven decisions as LR(1), on the much smaller LR(0)
state machine.

## Why LALR(1) Instead of Canonical LR(1)?

Canonical LR(1) keeps a separate state for every combination of item core (the production and dot
position) and lookahead terminal. That makes it the most powerful member of the family, but the
number of states can grow very large -- and Alpaca builds the whole table inside the compiler, on
every compilation of your parser.

LALR(1) merges LR(1) states that share the same cores and unions their lookahead sets, so it has
exactly as many states as LR(0). This is what yacc and Bison use, and it accepts nearly every grammar
written in practice. The price is that the merging can, in rare cases, introduce a reduce/reduce
conflict that the canonical LR(1) automaton would not have -- the grammar is unambiguous, but two
merged states can no longer tell their reductions apart. Shift/reduce conflicts are never introduced
by the merge. For the grammars in these docs (including the calculator) the LALR(1) and LR(1) tables
make the same decisions.

Concrete grounding from Alpaca's source: `ParseTable.scala` first builds the LR(0) automaton
(`LR0Automaton`), then computes each state's lookaheads by propagation over it (`Lookaheads`, the
spontaneous-generation-and-propagation algorithm), and finally closes each state with its real
lookaheads (`State.fromItem`) to read off the reduce actions.

## The LR(1) Item

> **Definition — LR(1) item:**
> An LR(1) item is a triple [A → α • β, a] where:
> - A → αβ is a production rule
> - the dot • marks how much of the right-hand side has been recognized
    > (α is the part already on the stack; β is what remains to be shifted)
> - a ∈ Σ ∪ {$} is the lookahead terminal
>
> The item [A → α •, a] (dot at the end) means A is fully recognized.
> A reduction by A → α fires when the next input symbol is exactly a.

Three example items using the dot notation from Alpaca's `Item.scala` docstring:

```
[Expr → • NUMBER, PLUS]       — about to shift NUMBER; after reduction, PLUS follows
[Expr → NUMBER •, PLUS]       — NUMBER fully recognized; reduce when next token is PLUS
[Expr → Expr • PLUS Expr, $]  — have an Expr on the stack; expect PLUS next
```

The lookahead in `[A → α •, a]` allows the parser to decide: if the next token is `a`, reduce; otherwise, do something
else. LALR(1) uses exactly these items; it just shares one state between all items with the same core.

## O(n) Parsing

LR parsers run in O(n) time for any deterministic context-free grammar. Each token is shifted at most once and reduced
at most once per step through the stack. The pre-built parse table turns each step into a constant-time table lookup —
no backtracking, no reparsing.

> **Compile-time processing:** Alpaca's `Parser` macro builds the LALR(1) parse table at compile time — all item
> sets, closure computations, and state transitions are resolved before your program runs. At runtime, `parse(lexemes)`
> executes the pre-built table with O(n) table lookups.

## Cross-links

- See [Context-Free Grammars](cfg.md) for the grammar analyzed on this page.
- Next: [Shift-Reduce Parsing](shift-reduce.md) — the shift/reduce loop step by step.
- See [Conflict Resolution](../conflict-resolution.md) for how Alpaca handles ambiguous grammars using `before`/`after`.
- See [Parser](../parser.md) for the complete `rule` DSL reference.
