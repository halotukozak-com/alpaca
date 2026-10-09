# EBNF and Extended Notations

BNF (Backus-Naur Form) is sufficient to describe any context-free grammar, but common patterns — optional elements, repetition, grouping — require verbose workarounds. EBNF (Extended BNF) adds shorthand for these patterns, and Alpaca's `.Option`, `.List`, and `.SeparatedBy` operators map directly to EBNF concepts.

## BNF vs EBNF

**BNF** uses only: non-terminals, terminals, alternatives (`|`), and concatenation. To express "zero or more Xs" you write explicit recursion:

```
XList → ε
XList → XList X
```

**EBNF** adds three shorthands:

| EBNF | Meaning | BNF equivalent |
|------|---------|---------------|
| `[X]` or `X?` | Optional (zero or one) | `Opt → ε \| X` |
| `{X}` or `X*` | Repetition (zero or more) | `List → ε \| List X` |
| `(A \| B)` | Grouping | Inline alternatives |

EBNF is purely syntactic sugar — it generates the same language as the equivalent BNF, just with less boilerplate.

## Alpaca's EBNF Operators

Alpaca provides three EBNF operators that work on both `Rule[R]` and terminals:

### `.List` — Zero or More

`Rule.List(binding)` matches zero or more occurrences and returns a `List[R]`. The macro generates two synthetic productions (see [Desugaring to Plain BNF](#desugaring-to-plain-bnf) below for the exact expansion).

In Alpaca:

```scala
import halotukozak.alpaca.*

enum BrainAST:
  case Root(ops: List[BrainAST])
  case Inc

val EbnfLexer = lexer:
  case "\\+" => Token["inc"]
  case "\\s+" => Token.Ignored

object EbnfParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)
    // stmts: List[BrainAST]

  val Operation: Rule[BrainAST] = rule:
    case EbnfLexer.inc(_) => BrainAST.Inc
```

This is equivalent to the EBNF notation `root → {Operation}`.

### `.Option` — Zero or One

`Rule.Option(binding)` matches zero or one occurrence and returns an `Option[R]`. The macro generates similar synthetic productions (see [Desugaring to Plain BNF](#desugaring-to-plain-bnf) below).

In Alpaca:

```scala
import halotukozak.alpaca.*

val NumLexer = lexer:
  case n @ "[0-9]+" => Token["num"](n.toInt)
  case "\\s+" => Token.Ignored

object NumParser extends Parser:
  val Num: Rule[Int] = rule:
    case NumLexer.num(n) => n.value

  val root: Rule[(Int, Option[Int])] = rule:
    case (Num(n), Num.Option(maybeNum)) =>
      (n, maybeNum)   // maybeNum: Option[Int]
```

This is equivalent to the EBNF notation `root → Num [Num]`.

### `.SeparatedBy` — Zero or More, Separator-Delimited

`Rule.SeparatedBy[Separator](binding)` matches zero or more occurrences delimited by a separator and returns a `List[R | SepValue[Separator]]`. Separators are preserved in the result list (interleaved between the rule values), which is useful when a separator carries its own semantic information (e.g. distinguishing `,` from `;`).

`SepValue[Separator]` is the runtime value type of the separator: for a token separator named `n` whose value has type `v` it is `Lexeme[n, v]` (terminals are pushed to the parse stack as lexemes); for a rule separator `Rule[t]` it is `t`.

The `Separator` type parameter is a token type (e.g. ``MyLexer.`,` ``) or a rule's singleton type (e.g. `Sep.type`).

```scala
import halotukozak.alpaca.*

val MyLexer = lexer:
  case n @ "[0-9]+" => Token["num"](n.toInt)
  case "," => Token[","]
  case "\\s+" => Token.Ignored

object MyParser extends Parser:
  val Num: Rule[Int] = rule:
    case MyLexer.num(n) => n.value

  val root: Rule[List[Any]] = rule:
    case Num.SeparatedBy[MyLexer.`,`](items) => items
    // For "1,2,3": items == List(1, <","-lexeme>, 2, <","-lexeme>, 3)
```

This is equivalent to the EBNF notation `root → [Num {"," Num}]`.

## Desugaring to Plain BNF

Every use of `.List` and `.Option` desugars to plain BNF productions at compile time. The macro generates synthetic non-terminals for them.

For the BrainFuck parser:

```
-- Source Alpaca:
root → Operation.List

-- Desugared BNF:
root         → OperationList
OperationList → ε
OperationList → OperationList Operation
```

For nested EBNF:

```
-- Source Alpaca:
While → jumpForward Operation.List jumpBack

-- Desugared BNF:
While         → jumpForward OperationList jumpBack
OperationList → ε
OperationList → OperationList Operation
```

Every `Operation.List` in the parser shares the same synthetic non-terminal, so its productions are added once. The `OperationList` name above is schematic — the actual generated names are internal.

The left recursion keeps the parser stack constant. The semantic actions prepend each element (`elem :: list`) and the list is reversed once where it is bound, so a list of n elements is built in O(n).

## When to Use EBNF vs Explicit Recursion

Use `.List` for **unseparated** sequences — elements that follow each other with no delimiter:

```scala
import halotukozak.alpaca.*

enum BrainAST:
  case Root(ops: List[BrainAST])
  case Inc

val EbnfLexer2 = lexer:
  case "\\+" => Token["inc"]
  case "\\s+" => Token.Ignored

object EbnfParser2 extends Parser:
  val Operation: Rule[BrainAST] = rule:
    case EbnfLexer2.inc(_) => BrainAST.Inc

  // Good: BrainFuck operations have no separators
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)
```

Use `.SeparatedBy[Sep]` for **separator-delimited** sequences (comma-separated lists, semicolon-separated statements):

```scala
import halotukozak.alpaca.*

val JsonLexer = lexer:
  case n @ "[0-9]+" => Token["num"](n.toInt)
  case "," => Token[","]
  case "\\s+" => Token.Ignored

object JsonMemberParser extends Parser:
  val ObjectMember: Rule[Int] = rule:
    case JsonLexer.num(n) => n.value

  // Good: JSON members are separated by commas
  val ObjectMembers: Rule[List[Any]] = rule:
    case ObjectMember.SeparatedBy[JsonLexer.`,`](members) => members

  val root: Rule[List[Any]] = rule:
    case ObjectMembers(members) => members
```

Use **explicit recursion** only when you need to customise the action — for example, dropping separators from the result instead of preserving them, or building a non-`List` shape.

## EBNF in the BrainFuck Grammar

The BrainFuck parser uses `.List` in three places:

| Rule | EBNF equivalent | Purpose |
|------|----------------|---------|
| `root → Operation.List` | `root → {Operation}` | Top-level program: zero or more operations |
| `While → jumpForward Operation.List jumpBack` | `While → "[" {Operation} "]"` | Loop body: zero or more operations |
| `FunctionDef → name "(" Operation.List ")"` | `FunctionDef → name "(" {Operation} ")"` | Function body |

All three expand to the same kind of synthetic list recursion over `Operation`.

## Cross-links

- See [Context-Free Grammars](cfg.md) for the formal BNF notation.
- See [Parser](../parser.md) for the `.List` and `.Option` API reference.
- See [Extractors](../extractors.md) for how to pattern-match on `.List` and `.Option` results.
