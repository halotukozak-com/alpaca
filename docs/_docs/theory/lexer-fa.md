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

Alpaca follows exactly this principle, using its own [`regex`](https://github.com/halotukozak/regex)
library instead of `java.util.regex`:

- At compile time, the `lexer` macro parses every token pattern and builds **one DFA for all of
  them at once** (`TokenMatcher`), using Brzozowski derivatives rather than an explicit
  NFA-to-DFA subset construction. Each accepting state is labelled with the token it accepts.
  An invalid or unsupported pattern is therefore a compile error, not a runtime crash.
- At runtime, `tokenize()` runs that DFA from the current input position. Every step is an array
  lookup -- there is no regex backtracking.
- The lexer uses **longest match** (maximal munch): it consumes the longest prefix of the input
  that any pattern matches. When several patterns match that same longest prefix, the one
  declared first wins. For example, with `"if"` declared before `"[a-z]+"`, the input `iffy` is a
  single identifier, while `if` alone is the keyword.

## Shadowing Detection

Because ties go to the earlier pattern, a later pattern can be dead code: pattern B can never
produce a token if every string it matches is also matched by some earlier pattern -- on any such
input the earlier pattern matches at least as much text and wins the tie. Formally, B is shadowed
when L(B) ⊆ L(A₁) ∪ … ∪ L(Aₙ), where A₁ … Aₙ are the patterns declared before it. Alpaca checks
this at compile time with its `SubsetChecker` (built on the same derivative-based `regex` library)
and reports a compile error ("Token ... can never match") pointing at the shadowed pattern.

Typical cases:

- `"."` declared before `"\\."` -- every literal dot is also "any character", so `"\\."` is dead.
  Declare the specific pattern first.
- `"[a-z]+"` declared before the keyword `"if"` -- the keyword is dead. With `"if"` first, both
  work: `if` is the keyword, `iffy` is still one identifier, because longest match wins.
- `"[0-9]+(\\.[0-9]+)?"` declared before `"[0-9]+"` -- every integer is also a decimal with the
  fraction omitted, so `"[0-9]+"` never wins. Here the fix is either a single pattern or giving the
  integer pattern inputs of its own, e.g. `"[0-9]+\\.[0-9]+"` for decimals.

A pattern that an earlier one merely matches a *prefix* of is fine: with `"a"` before `"ab"`, the
input `ab` is a single `ab` token. The check also catches a pattern covered only by several earlier
patterns together (`"[a-m]"` and `"[n-z]"` before `"[a-z]"`) and names all of them.

## Cross-links

- See [Lexer](../lexer.md) for the complete `lexer` DSL reference.
- See [Tokens and Lexemes](tokens.md) for what the lexer produces — the lexeme stream.
- Next: [Context-Free Grammars](cfg.md) for how token streams are parsed.
