# Operator Precedence Grammars

When a grammar has binary operators at multiple precedence levels, the parser must decide which operations to perform first. There are two approaches: encode precedence in the grammar structure (stratification), or use a flat grammar with explicit conflict resolution. Alpaca supports both.

## The Problem

The expression `1 + 2 * 3` has two valid parse trees:

```
    +               *
   / \             / \
  1   *           +   3
     / \         / \
    2   3       1   2

  = 1 + 6 = 7   = 3 * 3 = 9
```

The grammar `Expr → Expr + Expr | Expr * Expr | NUMBER` is ambiguous — it does not specify which tree to build. Precedence rules resolve this: `*` binds tighter than `+`, so `1 + 2 * 3 = 7`.

## Approach 1: Grammar Stratification

Encode precedence by splitting `Expr` into layers, one per precedence level:

```
Expr   → Expr + Term | Expr - Term | Term
Term   → Term * Factor | Term / Factor | Factor
Factor → ( Expr ) | NUMBER
```

This grammar is **unambiguous**. The structure forces `*` and `/` to bind tighter than `+` and `-`: a `Term` must be fully reduced before it participates in an `Expr`-level operation.

In Alpaca:

```scala sc-hidden sc-name:op-calc-lexer
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

```scala sc-compile-with:op-calc-lexer
import halotukozak.alpaca.*

object CalcParser extends Parser:
  val Expr: Rule[Double] = rule(
    { case (Expr(a), CalcLexer.PLUS(_), Term(b))  => a + b },
    { case (Expr(a), CalcLexer.MINUS(_), Term(b)) => a - b },
    { case Term(t) => t },
  )
  val Term: Rule[Double] = rule(
    { case (Term(a), CalcLexer.TIMES(_), Factor(b)) => a * b },
    { case (Term(a), CalcLexer.DIVIDE(_), Factor(b)) => a / b },
    { case Factor(f) => f },
  )
  val Factor: Rule[Double] = rule(
    { case CalcLexer.NUMBER(n) => n.value },
    { case (CalcLexer.LPAREN(_), Expr(e), CalcLexer.RPAREN(_)) => e },
  )
  val root: Rule[Double] = rule:
    case Expr(e) => e
```

No `resolutions` needed — the grammar itself is unambiguous.

## Approach 2: Flat Grammar + Conflict Resolution

Keep all operators at the same grammar level and use `before`/`after` to declare precedence:

```scala sc-compile-with:op-calc-lexer
import halotukozak.alpaca.*

object CalcParser extends Parser:
  val Expr: Rule[Double] = rule(
    "plus"  { case (Expr(a), CalcLexer.PLUS(_), Expr(b))  => a + b },
    "minus" { case (Expr(a), CalcLexer.MINUS(_), Expr(b)) => a - b },
    "times" { case (Expr(a), CalcLexer.TIMES(_), Expr(b))  => a * b },
    "div"   { case (Expr(a), CalcLexer.DIVIDE(_), Expr(b)) => a / b },
    { case CalcLexer.NUMBER(n) => n.value },
    { case (CalcLexer.LPAREN(_), Expr(e), CalcLexer.RPAREN(_)) => e },
  )
  val root: Rule[Double] = rule:
    case Expr(e) => e

given Resolutions[CalcParser.type] = resolutions(
  production.times.before(CalcLexer.TIMES, CalcLexer.DIVIDE),
  production.div.before(CalcLexer.TIMES, CalcLexer.DIVIDE),
  production.plus.before(CalcLexer.PLUS, CalcLexer.MINUS),
  production.minus.before(CalcLexer.PLUS, CalcLexer.MINUS),
  production.plus.after(CalcLexer.TIMES, CalcLexer.DIVIDE),
  production.minus.after(CalcLexer.TIMES, CalcLexer.DIVIDE),
)
```

The grammar is ambiguous, but the resolutions tell the parser exactly how to handle every conflict.

## Trade-offs

| | Stratified grammar | Flat grammar + resolution |
|-|-|-|
| **Grammar size** | More non-terminals (one per level) | Fewer non-terminals, more resolution rules |
| **Ambiguity** | None — grammar is unambiguous | Ambiguous — conflicts resolved by declarations |
| **Adding operators** | Requires restructuring non-terminal hierarchy | Add a production and a resolution rule |
| **Readability** | Precedence visible in grammar structure | Precedence visible in resolution declarations |
| **Error messages** | Cleaner — no conflicts to report | Conflict errors until all resolutions are added |

For grammars with 2-3 precedence levels, stratification is straightforward. For grammars with many levels (C has 15), flat grammar + resolution is more maintainable.

## Extending BrainFuck with Arithmetic

Standard BrainFuck has no operator precedence — `+` always means "increment by 1." But suppose we extend the language with arithmetic expressions for cell values: `+(3*2)` adds 6 to the current cell, `-(1+2)` subtracts 3. Now the BrainFuck lexer needs number and operator tokens, and the parser needs expression rules with precedence.

The extended lexer adds arithmetic tokens:

```scala
import halotukozak.alpaca.*

val ExtendedLexer = lexer:
  // Standard BrainFuck
  case ">" => Token["next"]
  case "<" => Token["prev"]
  case "\\." => Token["print"]
  case "," => Token["read"]
  case "\\[" => Token["jumpForward"]
  case "\\]" => Token["jumpBack"]
  // Arithmetic extension
  case "\\+" => Token["+"]
  case "-" => Token["-"]
  case "\\*" => Token["*"]
  case "/" => Token["/"]
  case "\\(" => Token["("]
  case "\\)" => Token[")"]
  case n @ "[0-9]+" => Token["NUM"](n.toInt)
  case "\\s+" => Token.Ignored
```

The parser uses a flat grammar with conflict resolution for precedence:

```scala sc-hidden sc-name:op-brain-ast
enum BrainAST:
  case Root(ops: List[BrainAST])
  case Add(n: Int)
  case Sub(n: Int)
  case Inc, Dec
```

```scala sc-hidden sc-name:op-brain-arith-lexer
import halotukozak.alpaca.*

val ExtendedLexer = lexer:
  case "\\+" => Token["+"]
  case "-" => Token["-"]
  case "\\*" => Token["*"]
  case "/" => Token["/"]
  case "\\(" => Token["("]
  case "\\)" => Token[")"]
  case n @ "[0-9]+" => Token["NUM"](n.toInt)
  case "\\s+" => Token.Ignored
```

```scala sc-compile-with:op-brain-ast,op-brain-arith-lexer
import halotukozak.alpaca.*

object ExtendedParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case Operation.List(ops) => BrainAST.Root(ops)

  // Arithmetic expressions with precedence
  val Expr: Rule[Int] = rule(
    "add"  { case (Expr(a), ExtendedLexer.`+`(_), Expr(b)) => a + b },
    "sub"  { case (Expr(a), ExtendedLexer.`-`(_), Expr(b)) => a - b },
    "mul"  { case (Expr(a), ExtendedLexer.`*`(_), Expr(b)) => a * b },
    "div"  { case (Expr(a), ExtendedLexer.`/`(_), Expr(b)) => a / b },
    { case ExtendedLexer.NUM(n) => n.value },
    { case (ExtendedLexer.`(`(_), Expr(e), ExtendedLexer.`)`(_)) => e },
  )

  // BrainFuck operations, now with arithmetic amounts
  val Operation: Rule[BrainAST] = rule(
    { case (ExtendedLexer.`+`(_), ExtendedLexer.`(`(_), Expr(n), ExtendedLexer.`)`(_)) =>
        BrainAST.Add(n) },        // +(3*2) adds 6
    { case (ExtendedLexer.`-`(_), ExtendedLexer.`(`(_), Expr(n), ExtendedLexer.`)`(_)) =>
        BrainAST.Sub(n) },        // -(1+2) subtracts 3
    { case ExtendedLexer.`+`(_) => BrainAST.Inc },  // bare + is increment by 1
    { case ExtendedLexer.`-`(_) => BrainAST.Dec },
    // ... other operations
  )

given Resolutions[ExtendedParser.type] = resolutions(
  // * and / bind tighter than + and -
  production.mul.before(ExtendedLexer.`*`, ExtendedLexer.`/`),
  production.div.before(ExtendedLexer.`*`, ExtendedLexer.`/`),
  production.add.before(ExtendedLexer.`+`, ExtendedLexer.`-`),
  production.sub.before(ExtendedLexer.`+`, ExtendedLexer.`-`),
  production.add.after(ExtendedLexer.`*`, ExtendedLexer.`/`),
  production.sub.after(ExtendedLexer.`*`, ExtendedLexer.`/`),
)
```

Without the resolutions, the compiler reports one conflict per (operator, production) pair, for example:

```
Shift "*" vs Reduce Expr -> Expr + Expr (add)
In situation like:
Operation.List + ( Expr + Expr * ...
Consider marking production Expr -> Expr + Expr (add) to be before or after "*"
```

Named productions are shown with their name (`(add)`), and the non-terminals that EBNF extractors generate as the extractor you wrote (`Operation.List`).

The resolutions establish: `*`/`/` bind tighter than `+`/`-`, and all operators are left-associative. Now `+(3+2*4)` correctly evaluates to `+(11)` — adding 11 to the current cell.

## Transitivity and Cycle Detection

Alpaca treats `before` and `after` constraints as a partial order over productions. The compiler computes the transitive closure: if `A.before(B)` and `B.before(C)` are both declared, then `A.before(C)` holds implicitly — you do not need to state it.

This matters for grammars with many precedence levels. For C-like operators (`*`, `+`, `<`, `&&`, `||`), declaring `mul.before(add).before(cmp).before(and).before(or)` is enough; pairwise constraints between non-adjacent levels are derived.

Cycles in the constraint graph are contradictions. If the closure ever produces both `A.before(B)` and `A.after(B)` (directly or indirectly through other productions), the compiler rejects the resolution set with an "Inconsistent conflict resolution detected" error showing the full cycle path. This catches mistakes like declaring `mul.before(add)` together with `add.before(mul)` — even when the contradiction is not direct.

The detection runs at compile time, so a grammar that compiles is guaranteed to have a consistent precedence ordering.

## Cross-links

- See [Conflict Resolution](../conflict-resolution.md) for the full `before`/`after` DSL reference.
- See [Conflicts and Disambiguation](conflicts.md) for the formal theory of shift/reduce and reduce/reduce conflicts.
- See the [Expression Evaluator](../cookbook/expression-evaluator.md) for a complete calculator with precedence resolution.
