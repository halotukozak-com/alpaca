# Conflicts and Disambiguation

A grammar is ambiguous if a string can be parsed in more than one way. In LR parsing, ambiguity manifests as a conflict: the parse table has two valid entries for the same (state, symbol) pair, and the parser cannot proceed deterministically.

## What is a Parse Table Conflict?

The LALR(1) parse table maps (state, lookahead terminal) pairs to actions — either Shift (push the next token) or Reduce (pop a production's right-hand side and produce a non-terminal). A conflict exists when a single (state, terminal) pair has more than one valid action: the parse table has a collision.

> **Definition — Parse Table Conflict:**
> A conflict in parse state s exists when the parse table has more than one entry
> for the pair (s, t) for some lookahead terminal t ∈ Σ ∪ {$}.
> A shift/reduce conflict has one entry Shift(s') and one entry Reduce(A → α).
> A reduce/reduce conflict has two entries Reduce(A → α) and Reduce(B → β).

## Shift/Reduce Conflicts

At some parse state, given lookahead token t, the parser could either shift t (push it and move to a new state) or reduce by some production A → α (pop the right-hand side and produce A). Both are valid actions for the same (state, t) pair — the parser cannot decide between them deterministically.

Why it happens: two or more LR items in the same state propose incompatible actions for the same lookahead. The grammar allows the same prefix to continue in two different ways, and the LR automaton sees both paths simultaneously.

**Example: `1 + 2 + 3` in the calculator grammar.** After parsing `Expr PLUS Expr` with lookahead `PLUS`, the parser has two valid choices:

- **Reduce** `Expr → Expr PLUS Expr` — complete the first addition and produce a single `Expr`.
- **Shift** the second `PLUS` — keep accumulating, treating the input as `1 + (2 + 3)`.

Both are valid parse trees for `1 + 2 + 3` — the grammar (from [Context-Free Grammars](cfg.md)) is ambiguous for binary operator chains. Alpaca detects this conflict at compile time and reports:

```
Shift "PLUS" vs Reduce Expr -> Expr PLUS Expr (plus)
In situation like:
Expr PLUS Expr PLUS ...
Consider marking production Expr -> Expr PLUS Expr (plus) to be before or after "PLUS"
```

## Reduce/Reduce Conflicts

A reduce/reduce conflict occurs when two different productions can reduce the same token sequence with the same lookahead. The parser has two Reduce entries for the same (state, t) pair and cannot decide which to apply.

**Example:** if a grammar has both `Integer → NUMBER` and `Float → NUMBER`, and the parser has `NUMBER` on the stack with lookahead `$`, it cannot determine which reduction to apply — both are valid. Alpaca reports:

```
Reduce Float -> NUMBER vs Reduce Integer -> NUMBER
In situation like:
NUMBER ...
Conflicting production: Float -> NUMBER (line 11)
Consider marking one of the productions to be before or after the other
```

Reduce/reduce conflicts are less common than shift/reduce conflicts. They typically indicate a grammar design issue — two rules competing for the same token sequence. The usual fix is to restructure the grammar so the two competing productions have distinct right-hand sides, or to use a different non-terminal.

## How Lookahead Helps

Lookahead (LALR(1) and LR(1)) often disambiguates conflicts that earlier LR variants (LR(0), SLR) cannot resolve. Each item carries its specific lookahead terminal, so the parser only fires a reduce when the actual next token matches that item's lookahead. This eliminates many spurious conflicts.

But for inherently ambiguous grammars — like the calculator's binary operator productions — lookahead alone is not enough. The grammar has the same prefix structure regardless of which associativity is intended, so both shift and reduce appear valid to the automaton. Explicit resolution is required.

For a detailed explanation of items and lookahead, see [Shift-Reduce Parsing](shift-reduce.md).

## Resolution by Priority

Resolving a conflict means declaring which action wins. For a shift/reduce conflict: should the reduction or the shift take priority?

Alpaca's `before`/`after` DSL lets you declare priorities directly in the parser definition:

- `production.name.before(tokens*)` — when the conflict is between reducing `name` and shifting one of those tokens, the reduction wins. Use this for left-associativity and higher-precedence reductions.
- `production.name.after(tokens*)` — prefer shifting those tokens over reducing this production. Use this when another operator should bind more tightly.

Priorities are transitive: if reducing `times` beats shifting `PLUS`, and reducing `plus` beats shifting `MINUS`, then the precedence relationships propagate through the graph.

A minimal example — declaring left-associativity for the `plus` production:

```scala sc-hidden sc-name:conflicts-plus-lexer
import halotukozak.alpaca.*

val CalcLexer = lexer:
  case num @ "[0-9]+(\\.[0-9]+)?" => Token["NUMBER"](num.toDouble)
  case "\\+" => Token["PLUS"]
  case "\\s+" => Token.Ignored
```

```scala sc-compile-with:conflicts-plus-lexer
object CalcParser extends Parser:
  val Expr: Rule[Double] = rule(
    "plus" { case (Expr(a), CalcLexer.PLUS(_), Expr(b)) => a + b },
    { case CalcLexer.NUMBER(n) => n.value },
  )
  val root: Rule[Double] = rule:
    case Expr(v) => v

given Resolutions[CalcParser.type] = resolutions(
  production.plus.before(CalcLexer.PLUS), // left-associative: reduce + before shifting another +
)
```

The complete CalcParser resolution set — including `minus`, `times`, and `div`, and the precedence relationships between them — is shown on [Full Calculator Example](full-example.md). For the full DSL reference (Production(symbols*) selector, token-side resolution, cycle detection, ordering constraint), see [Conflict Resolution](../conflict-resolution.md).

## Compile-Time Detection

Conflicts are detected at compile time when the LALR(1) parse table is constructed by the `extends Parser` macro. A conflict causes a compile error (shift/reduce or reduce/reduce) — no conflict checking happens at runtime.

When you add a `given Resolutions[MyParser.type] = resolutions(...)`, the macro incorporates your priority declarations into the table construction and re-checks for consistency. A cycle in your declarations ("Inconsistent conflict resolution detected") is also reported at compile time.

## Cross-links

- [Context-Free Grammars](cfg.md) — the calculator grammar that produces these conflicts
- [Shift-Reduce Parsing](shift-reduce.md) — the parse table mechanics behind conflicts
- [Conflict Resolution](../conflict-resolution.md) — the full DSL reference: Production(symbols*) selector, named productions, token-side resolution, cycle detection, ordering constraint
- Next: [Semantic Actions](semantic-actions.md) — what happens when a conflict-free reduction fires
- [Full Calculator Example](full-example.md) — the full CalcParser with conflict resolution applied
