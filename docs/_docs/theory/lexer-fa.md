# The Lexer: Regex to Finite Automata

## What Does a Lexer Do?

A lexer reads a character stream from left to right and emits a token stream. At each scan step,
it tries the token class patterns in a fixed order and picks the first pattern whose regex
matches at the current position, consuming that matched prefix. When no pattern matches the
current position, the lexer throws an error. The result is a flat list of lexemes that the parser
consumes next.

## Regular Languages

> **Definition — Regular language:**
> A language L ⊆ Σ* is *regular* if it is recognized by a finite automaton (FA). Equivalently,
> L can be described by a regular expression over alphabet Σ.
> Each token class defines a regular language: `NUMBER` defines the set
> { "0", "1", ..., "3.14", "100", ... }.

Regex notation is a concise way to specify regular languages. This is why regex is the right
tool for token class definitions — token classes have a "look ahead a bounded amount" structure
that regular languages capture exactly. More complex patterns such as balanced parentheses
require a more powerful formalism (context-free grammars, which the parser handles), but for
token recognition, regular expressions are both necessary and sufficient.

## NFA and DFA: The Conceptual Picture

Any regular expression can be translated into a finite automaton that accepts the same strings.
The standard construction proceeds in two steps.

**Step 1 — NFA (nondeterministic finite automaton).** A regex is converted into an NFA via
Thompson's construction. An NFA can have multiple possible transitions from a state on the same
input, or transitions on the empty string. For simple patterns this is easy to visualize. The
`PLUS` token pattern `\+` produces a two-state NFA:

| State | Input `+` | Accept? |
|-------|-----------|---------|
| q₀    | q₁        | No      |
| q₁    | —         | Yes     |

The machine starts at q₀, consumes a `+`, and moves to q₁ — an accepting state. Any other
input from q₀ leads nowhere, meaning the string does not match.

**Step 2 — DFA (deterministic finite automaton).** An NFA is then converted to a DFA. A DFA
has exactly one transition per (state, input-character) pair, with no ambiguity. This matters
for performance: a DFA can be executed in O(n) time by reading the input left to right, one
character at a time, following the single applicable transition at each step. A DFA is therefore
the right runtime data structure for a lexer — no backtracking, no branching.

> **Definition — Deterministic Finite Automaton (DFA):**
> A DFA is a 5-tuple (Q, Σ, δ, q₀, F) where:
> - Q is a finite set of states
> - Σ is the input alphabet (here: Unicode characters)
> - δ : Q × Σ → Q is the transition function
> - q₀ ∈ Q is the start state
> - F ⊆ Q is the set of accepting states
>
> A DFA accepts a string w if δ*(q₀, w) ∈ F, where δ* is the iterated transition function.
> In Alpaca's combined lexer DFA, each accepting state also carries a *token label* indicating
> which token class was matched.

## Combining Token Patterns into One Automaton

To lex a language with multiple token classes, the standard approach builds one combined DFA. In
theory: construct an NFA for each token pattern, connect them all to a new start state with
epsilon transitions, then convert the combined NFA to a single DFA.

Alpaca follows the same principle but implements it using Java's regex engine, which is itself
backed by NFA/DFA machinery:

- All token patterns are combined into a single Java regex alternation at compile time:

```
// Conceptual: how Alpaca combines patterns internally
(?<NUMBER>[0-9]+(\.[0-9]+)?)|(?<PLUS>\+)|(?<MINUS>-)|(?<TIMES>\*)|...
```

- `java.util.regex.Pattern.compile(...)` is called inside the `lexerImpl` macro at compile
  time. An invalid regex pattern therefore causes a compile error, not a runtime crash.
- At runtime, `Tokenization.tokenize()` uses `matcher.lookingAt()` on the combined pattern at
  the current input position. It then checks which named group matched using
  `matcher.start(i)` to determine the token class.

In practice, this combined-pattern approach lets Alpaca's lexer scan the input from left to
right in a single pass, much like a hand-built DFA-based lexer. However, it still relies on
Java's backtracking regex engine internally, so Alpaca does not claim a strict worst-case O(n)
time guarantee or the complete absence of backtracking for arbitrary token patterns.

## Shadowing Detection

A practical issue with ordered alternation is *shadowing*: pattern A shadows pattern B if every
string matched by B is also matched by A (that is, L(B) ⊆ L(A), meaning every string in B's
language is also in A's language), and A appears before B in the lexer definition. If this
occurs, B will never match — it is dead code.

Alpaca's `SubsetChecker` uses its own `regex` library (a Brzozowski-derivative DFA implementation
for decidable regex operations) to check at compile time whether any pattern's language is a
subset of an earlier pattern's language. If shadowing is detected, the macro reports a compile
error ("Token ... can never match") pointing at the shadowed pattern.

**Example:** If you wrote the integer pattern `"[0-9]+"` before the decimal pattern
`"[0-9]+(\\.[0-9]+)?"`, the integer pattern would shadow the decimal one — every decimal like
`"3.14"` is also matched by `"[0-9]+"` up to the decimal point, but more critically the integer
pattern can match the prefix `"3"` and would consume it first. The `SubsetChecker` check catches
this ordering mistake at compile time rather than silently producing wrong output at runtime.

In `CalcLexer`, the decimal pattern `"[0-9]+(\\.[0-9]+)?"` is listed first, before any simpler
integer-only pattern, so no shadowing occurs.

## Cross-links

- See [Lexer](../lexer.md) for the complete `lexer` DSL reference.
- See [Tokens and Lexemes](tokens.md) for what the lexer produces — the lexeme stream.
- Next: [Context-Free Grammars](cfg.md) for how token streams are parsed.
