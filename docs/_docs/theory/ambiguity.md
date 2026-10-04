# Ambiguity

A grammar is *ambiguous* if there exists at least one string that can be parsed in two or more distinct ways — that is, the string has more than one parse tree. Ambiguity is not always a bug; sometimes it reflects genuine choices that need explicit resolution.

## Inherent vs Accidental Ambiguity

**Accidental ambiguity** arises from how you wrote the grammar. The expression grammar `Expr → Expr + Expr | NUMBER` is ambiguous because `1 + 2 + 3` has two parse trees. Rewriting it as `Expr → Expr + NUMBER | NUMBER` (left-recursive) or adding conflict resolution removes the ambiguity without changing the language.

**Inherent ambiguity** is a property of the *language* itself, not the grammar. Some context-free languages are inherently ambiguous — every grammar for them is ambiguous. These are rare in practice. Programming language grammars are almost never inherently ambiguous; the ambiguity comes from the grammar's structure and can be resolved.

## Classic Examples

### The Dangling-Else Problem

```
Stmt → if Expr then Stmt
     | if Expr then Stmt else Stmt
     | other
```

The input `if a then if b then s1 else s2` is ambiguous:

- Parse 1: `if a then (if b then s1 else s2)` — the `else` binds to the inner `if`
- Parse 2: `if a then (if b then s1) else s2` — the `else` binds to the outer `if`

Most languages resolve this by convention: the `else` binds to the nearest `if`. In Alpaca, you would use conflict resolution to prefer shifting `else` over reducing the short `if-then`.

### Binary Operator Chains

```
Expr → Expr + Expr | NUMBER
```

The input `1 + 2 + 3` has two parse trees:
- `(1 + 2) + 3` — left-associative
- `1 + (2 + 3)` — right-associative

Alpaca reports this as a shift/reduce conflict. Resolution: `production.plus.before(CalcLexer.PLUS)` makes it left-associative.

## Detecting Ambiguity

Ambiguity in context-free grammars is **undecidable** in general — there is no algorithm that can determine for every CFG whether it is ambiguous. However, LR(1) parsing provides a practical approximation: if the grammar has no shift/reduce or reduce/reduce conflicts, the LR(1) parse table is deterministic and the grammar is unambiguous (for the LR(1) class).

Alpaca catches ambiguity at compile time: when building the LR(1) parse table, any cell with two actions is a conflict and the compiler reports it.

## How Alpaca Reports Ambiguity

**Shift/reduce conflict:**

```
Shift "+" vs Reduce Expr -> Expr + Expr
In situation like:
Expr + Expr + ...
Consider marking production Expr -> Expr + Expr to be before or after "+"
```

**Reduce/reduce conflict:**

```
Reduce Integer -> Number vs Reduce Float -> Number
In situation like:
Number ...
Conflicting production: Integer -> Number (line 3)
Consider marking one of the productions to be before or after the other
```

Both are compile errors. The parser cannot be instantiated until all conflicts are resolved.

## Disambiguation Strategies

1. **Grammar rewriting.** Restructure the grammar to eliminate ambiguity (e.g., stratification for operator precedence). No `resolutions` needed.

2. **Explicit resolution.** Keep the ambiguous grammar and use `production.name.before(tokens)` / `.after(tokens)` to declare which action the parser should prefer. See [Conflict Resolution](../conflict-resolution.md).

3. **Combination.** Use stratification for major precedence levels and resolution for fine-tuning (e.g., associativity of same-level operators).

## BrainFuck: Unambiguous by Design

The BrainFuck grammar has no ambiguity. Every token uniquely determines the applicable rule:

- `[` starts a `While` loop
- `name(` starts a `FunctionDef`
- `name!` is a `FunctionCall`
- All single-character commands map to exactly one `Operation` variant

No two rules compete for the same token sequence, so the LR(1) table has no conflicts.

## Cross-links

- See [Conflicts and Disambiguation](conflicts.md) for how LR(1) lookahead resolves conflicts.
- See [Conflict Resolution](../conflict-resolution.md) for the `before`/`after` DSL.
- See [Operator Precedence Grammars](operator-precedence.md) for resolving operator ambiguity.
