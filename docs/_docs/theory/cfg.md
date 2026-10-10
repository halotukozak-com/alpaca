# Context-Free Grammars

Context-free grammars are the backbone of syntactic analysis. A grammar defines a language by specifying how symbols can
be combined and rewritten — "context-free" means each rule applies regardless of surrounding context. If the lexer is
the vocabulary of a language, the grammar is its syntax.

## What is a Context-Free Grammar?

A grammar consists of a set of non-terminal symbols (grammar variables that can be expanded), a set of terminal
symbols (the tokens the lexer produces), a set of production rules (rewrite rules), and a start symbol. A derivation
starts from the start symbol and repeatedly replaces non-terminals with production right-hand sides until only terminals
remain. The language of a grammar G is the set of all terminal strings reachable from the start symbol.

> **Definition — Context-Free Grammar:**
> A CFG is a 4-tuple G = (V, Σ, R, S) where:
> - V is a finite set of non-terminal symbols (grammar variables)
> - Σ is a finite set of terminal symbols (tokens), V ∩ Σ = ∅
> - R ⊆ V × (V ∪ Σ)* is a finite set of production rules
> - S ∈ V is the start symbol
>
> A production rule A → α means the non-terminal A can be replaced by the symbol string α.
> A grammar generates the language L(G) = { w ∈ Σ* | S ⇒* w } — all terminal strings
> derivable from S in zero or more steps.

## BNF Notation

Production rules are written in Backus-Naur Form (BNF): `A → α` means A can be rewritten as α. The vertical bar `|`
separates alternatives, so `A → α | β` is shorthand for two rules. On this page non-terminals are written in CamelCase and
terminals in UPPERCASE. That is only a convention: Alpaca token and rule names can use any case (the BrainFuck lexer's
tokens are lowercase).

EBNF (Extended BNF) adds optional elements `[...]`, repetition `{...}`, and grouping `(...)`. These shorthands can
always be translated into plain BNF, but are useful for compact notation. This page uses BNF throughout for clarity;
Alpaca's DSL maps directly to BNF productions.

## The Calculator Grammar

The calculator grammar is the running example for the entire Compiler Theory Tutorial. It defines arithmetic expressions
with four operators and parentheses:

```
Expr  → Expr PLUS Expr
      | Expr MINUS Expr
      | Expr TIMES Expr
      | Expr DIVIDE Expr
      | LPAREN Expr RPAREN
      | NUMBER

root  → Expr
```

Identifying the 4-tuple components:

- V = {Expr, root} — two non-terminals
- Σ = {NUMBER, PLUS, MINUS, TIMES, DIVIDE, LPAREN, RPAREN} — seven terminal symbols, produced by CalcLexer
- R = the 7 production rules above
- S = root — the start symbol

This grammar is **ambiguous** — the expression `1 + 2 * 3` can be parsed in two ways depending on which `Expr` is expanded first. See [Conflict Resolution](../conflict-resolution.md) for how Alpaca resolves this.

> **BrainFuck mapping:** The BrainFuck> grammar from [Getting Started](../getting-started.md) has different non-terminals (`root`, `Operation`, `While`, `FunctionDef`, `FunctionCall`) but the same structure: V = {root, Operation, While, ...}, Σ = {next, prev, inc, ..., functionName, ...}, and production rules mapping each non-terminal to token sequences. Unlike the calculator grammar, BrainFuck> is unambiguous — no conflicts arise.

## Derivation

A *derivation* is a sequence of rewriting steps from the start symbol to a terminal string. Each step replaces the
leftmost non-terminal with one of its production alternatives (leftmost derivation).

Leftmost derivation for `1 + 2`:

```
root ⇒ Expr
     ⇒ Expr PLUS Expr        (apply: Expr → Expr PLUS Expr)
     ⇒ NUMBER PLUS Expr      (apply: Expr → NUMBER, leftmost)
     ⇒ NUMBER PLUS NUMBER    (apply: Expr → NUMBER, leftmost)
```

The first step applies `root → Expr`; the second expands the leftmost `Expr` using the `Expr PLUS Expr` production; the
third and fourth substitute the literal `NUMBER` terminal for each remaining `Expr`.

## Parse Trees

A parse tree captures the grammatical structure of a derivation as a tree. Each internal node is a non-terminal; each
leaf is a terminal. The parse tree for `1 + 2`:

```
         root
          |
         Expr
        / | \
      Expr PLUS Expr
       |          |
     NUMBER     NUMBER
     (1.0)      (2.0)
```

Note: In Alpaca, the parse tree is never exposed to user code. The `Parser` macro builds it internally during the
shift-reduce parse, and immediately evaluates your semantic actions (the `=>` expressions in `rule` definitions) as each
node is reduced. What `parse()` returns is the typed result — a `Double` in the calculator case — not an intermediate
tree object. (See [The Compilation Pipeline](pipeline.md) for the full picture.)

## Alpaca DSL Mapping

The calculator grammar maps directly to an Alpaca `Parser` definition. Each production rule becomes a case clause in a
`rule(...)` call; the right-hand side pattern matches the grammatical structure, and the right-hand side expression
computes the result.

```scala sc-hidden sc-name:cfg-calc-lexer
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

```scala sc-compile-with:cfg-calc-lexer
object CalcParser extends Parser:
  val Expr: Rule[Double] = rule(
    "plus"  { case (Expr(a), CalcLexer.PLUS(_), Expr(b)) => a + b },
    "minus" { case (Expr(a), CalcLexer.MINUS(_), Expr(b)) => a - b },
    "times" { case (Expr(a), CalcLexer.TIMES(_), Expr(b)) => a * b },
    "div"   { case (Expr(a), CalcLexer.DIVIDE(_), Expr(b)) => a / b },
    { case (CalcLexer.LPAREN(_), Expr(e), CalcLexer.RPAREN(_)) => e },
    { case CalcLexer.NUMBER(n) => n.value },
  )
  val root: Rule[Double] = rule:
    case Expr(v) => v

given Resolutions[CalcParser.type] = resolutions(
  production.plus.before(CalcLexer.PLUS, CalcLexer.MINUS),
  production.plus.after(CalcLexer.TIMES, CalcLexer.DIVIDE),
  production.minus.before(CalcLexer.PLUS, CalcLexer.MINUS),
  production.minus.after(CalcLexer.TIMES, CalcLexer.DIVIDE),
  production.times.before(CalcLexer.TIMES, CalcLexer.DIVIDE, CalcLexer.PLUS, CalcLexer.MINUS),
  production.div.before(CalcLexer.TIMES, CalcLexer.DIVIDE, CalcLexer.PLUS, CalcLexer.MINUS),
)
```

The grammar is ambiguous as written (see above), so `Expr`'s productions are named and a `Resolutions` instance
disambiguates them by precedence and associativity -- see [Conflict Resolution](../conflict-resolution.md) for the
full `before`/`after` DSL. Each `case` clause corresponds to one production rule. `Expr(a)` matches a reduced `Expr` non-terminal with value `a`.
`CalcLexer.PLUS(_)` matches the PLUS terminal (the `_` discards the lexeme binding).
`CalcLexer.NUMBER(n)` matches a NUMBER terminal; `n.value` accesses the `Double` extracted by the lexer. The grammar's
non-terminals (`Expr`, `root`) become `Rule[Double]` values; the type parameter is the result type of each reduction.

## Cross-links

- See [Tokens and Lexemes](tokens.md) for how the terminal symbols (NUMBER, PLUS, etc.) are produced by the lexer.
- Next: [Why LR?](why-lr.md) — why LR parsing was chosen over top-down alternatives.
- See [Parser](../parser.md) for the complete `rule` DSL reference and all extractor forms.
- See [Conflict Resolution](../conflict-resolution.md) for how Alpaca resolves ambiguity in the calculator grammar.
