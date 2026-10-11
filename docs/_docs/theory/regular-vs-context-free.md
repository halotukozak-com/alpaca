# Regular vs Context-Free Languages

Alpaca splits language processing into two stages — lexer and parser — because the two stages handle fundamentally different classes of languages. The lexer recognizes *regular* languages; the parser recognizes *context-free* languages. This page explains what that distinction means and why it matters.

## The Chomsky Hierarchy (at a Glance)

Formal languages are classified by the power of the grammar needed to describe them:

| Type | Name | Recognizer | Example |
|------|------|-----------|---------|
| 3 | Regular | Finite automaton | Token patterns (`[0-9]+`, `[A-Za-z]+`) |
| 2 | Context-free | Pushdown automaton | Nested structures (`[...]`, `{...}`) |
| 1 | Context-sensitive | Linear-bounded automaton | Type checking, scope rules |
| 0 | Recursively enumerable | Turing machine | Arbitrary computation |

Each level strictly contains the one below it. Every regular language is context-free, but not every context-free language is regular.

## What Makes a Language Regular?

A language is *regular* if it can be described by a regular expression (or equivalently, recognized by a finite automaton with no stack or memory).

Regular languages can express:
- Fixed strings: `"if"`, `"while"`
- Character classes: `[0-9]+`, `[A-Za-z_]+`
- Alternation: `"true" | "false"`
- Repetition: `\s+`, `[a-z]*`

Regular languages **cannot** express:
- Balanced nesting: matched `[` and `]` pairs
- Counting: "exactly as many `a`s as `b`s"
- Cross-references: "the string before `=` must match the string after `=`"

## Why Balanced Brackets Are Not Regular

Consider the language of balanced square brackets: `[]`, `[[]]`, `[[[]]]`, etc. To recognize this language, a machine must count how many opening brackets it has seen and verify the same number of closing brackets follows. A finite automaton has a fixed number of states — it cannot count to an arbitrary depth.

The BrainFuck lexer can track bracket *depth* (using `ctx.squareBrackets`) and reject some malformed inputs — an unmatched `]` or leftover `[` at end of input. But that is still not full context-free parsing: the lexer emits a flat token stream and does not build nested structure. The parser's grammar rules and parse stack turn `[` ... `]` sequences into the nested `While` nodes in the AST.

<details>
<summary>The pumping lemma (informal)</summary>

The *pumping lemma for regular languages* states: for any regular language L, there exists a length p such that any string s in L with |s| >= p can be split into s = xyz where |xy| <= p, |y| > 0, and xy^i z is in L for all i >= 0.

For balanced brackets, pumping the opening brackets produces strings like `[[[]]` — unbalanced, and not in the language. This contradicts the lemma, proving balanced brackets are not regular.

</details>

## Why the Lexer/Parser Split?

Given that context-free grammars are strictly more powerful, why not use one grammar for everything — including token recognition?

**Performance.** Regular language recognition (finite automata) runs in O(n) with no backtracking. Context-free parsing (LR) also runs in O(n) but with a larger constant factor due to the parse stack. Processing characters one at a time through a DFA is faster than pushing and popping a parse stack for every character.

**Simplicity.** Token patterns are naturally described by regex. Expressing `[0-9]+(\.[0-9]+)?` as a context-free grammar requires multiple productions — the regex is both shorter and clearer.

**Separation of concerns.** The lexer handles character-level details (whitespace, comments, escape sequences) and produces a clean token stream. The parser handles structural details (nesting, precedence, associativity). Neither needs to know about the other's concerns.

## BrainFuck Mapping

In BrainFuck>:

- **Regular (lexer handles):** recognizing `>`, `<`, `+`, `-`, `.`, `,`, `[`, `]`, `!`, function names (`[A-Za-z]+`), and counting brackets via `ctx`
- **Context-free (parser handles):** matching `[` with `]` to form `While` loops, matching `name(body)` to form `FunctionDef`, sequencing operations into lists via `.List`

The lexer emits a flat stream of tokens. The parser gives them structure.

## Cross-links

- See [The Lexer: Regex to Finite Automata](lexer-fa.md) for how Alpaca compiles regex into automata.
- See [Context-Free Grammars](cfg.md) for the formal definition of CFGs and how Alpaca maps them to parser rules.
- Next: [Context-Free Grammars](cfg.md) — the formal definition of grammars and how Alpaca rules map to them.
