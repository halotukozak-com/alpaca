# Extractors

Parser rule bodies are partial functions -- everything on the left side of `=>` is a pattern. Extractors provide type-safe access to terminals (tokens), non-terminals (rule results), and EBNF operators.

<details>
<summary>Under the hood: compile-time pattern analysis</summary>

The Alpaca macro transforms patterns like `BrainLexer.inc(n)` into code that extracts a `Lexeme` from the parse stack. The macro reads each case pattern at compile time, identifies the symbols involved, constructs the grammar productions, and generates the parse table. What you write as patterns is syntactic sugar resolved against the grammar.

</details>

## Terminal Extractors

Use `MyLexer.TOKEN(binding)` to match a terminal. The `binding` is a `Lexeme` -- **not** the extracted value. Use `binding.value` to access the semantic content.

```scala sc-hidden sc-name:ExtractorsCore
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "!" => Token["functionCall"]
  case "\\+" => Token["inc"]
  case "\\[" => Token["jumpForward"]
  case "\\]" => Token["jumpBack"]

enum BrainAST:
  case Root(ops: List[BrainAST])
  case While(ops: List[BrainAST])
  case Inc
  case FunctionCall(name: String)
```

```scala sc-compile-with:ExtractorsCore
object TerminalExtractorParser extends Parser:
  val root: Rule[String] = rule(
    // Value-bearing token: use binding.value
    { case BrainLexer.functionName(name) => name.value },   // name: Lexeme, name.value: String
    // Structural token: discard the binding
    { case BrainLexer.jumpForward(_) => "loop start" },
  )
```

Special-character token names (e.g., a lexer defining `Token["\\+"]`) need backtick quoting to reference in a pattern:

```scala
import halotukozak.alpaca.*

val MyLexer = lexer:
  case "\\+" => Token["\\+"]

object EscapedTokenParser extends Parser:
  val root: Rule[Unit] = rule:
    case MyLexer.`\\+`(_) => ()
```

**Pitfall:** After `BrainLexer.functionName(name)`, the variable `name` is a `Lexeme`, not a `String`. Using `name` where a `String` is expected is a type error. Always use `name.value`.

## Non-Terminal Extractors

Use `Rule(binding)` to match a non-terminal. This calls `Rule[R].unapply`, extracting the value of type `R` produced during the parse:

```scala sc-compile-with:ExtractorsCore
object NonTerminalExtractorParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)

  val While: Rule[BrainAST] = rule:
    // Multiple non-terminals in a tuple
    case (BrainLexer.jumpForward(_), Operation.List(stmts), BrainLexer.jumpBack(_)) =>
      BrainAST.While(stmts)

  val Operation: Rule[BrainAST] = rule(
    { case BrainLexer.inc(_) => BrainAST.Inc },
    // While(whl) extracts the BrainAST produced by the While rule
    { case While(whl) => whl },   // whl: BrainAST
  )
```

Rules can refer to themselves recursively. The macro handles left recursion and mutual recursion automatically.

## Tuple Patterns

Multi-symbol productions match a **tuple**; single-symbol productions match **directly**:

```scala sc-compile-with:ExtractorsCore
object TuplePatternParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)

  val While: Rule[BrainAST] = rule:
    case (BrainLexer.jumpForward(_), Operation.List(stmts), BrainLexer.jumpBack(_)) =>
      BrainAST.While(stmts)

  val Operation: Rule[BrainAST] = rule(
    // Multi-symbol: tuple pattern with parentheses
    { case (BrainLexer.functionName(name), BrainLexer.functionCall(_)) =>
        BrainAST.FunctionCall(name.value) },
    // Single-symbol: no parentheses
    { case BrainLexer.inc(_) => BrainAST.Inc },
    { case While(whl) => whl },
  )
```

## EBNF Extractors: .List

`Rule.List(binding)` binds to a `List[R]`. The macro generates a left-recursive accumulation production (empty → `Nil`, prepend → `elem :: list`); the accumulated list is reversed once where it is bound, so parsing stays linear in the number of elements.

The BrainFuck parser uses `.List` for the root and for loop bodies:

```scala sc-compile-with:ExtractorsCore
object ListRuleParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)
    // stmts: List[BrainAST] -- zero or more operations

  val While: Rule[BrainAST] = rule:
    case (BrainLexer.jumpForward(_), Operation.List(stmts), BrainLexer.jumpBack(_)) =>
      BrainAST.While(stmts)

  val Operation: Rule[BrainAST] = rule(
    { case BrainLexer.inc(_) => BrainAST.Inc },
    { case While(whl) => whl },
  )
```

`.List` also works on terminals:

```scala sc-compile-with:ExtractorsCore
object IncListParser extends Parser:
  val root = rule:
    case BrainLexer.inc.List(incs) =>
      incs    // List[Lexeme] -- zero or more inc tokens
```

## EBNF Extractors: .Option

`Rule.Option(binding)` binds to an `Option[R]`. The macro generates an empty production (→ `None`) and a single-element production (→ `Some`).

```scala sc-compile-with:ExtractorsCore
object OptionRuleParser extends Parser:
  val root = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionCall.Option(call)) =>
      (name.value, call)   // call: Option[Lexeme]
```

## EBNF Extractors: .SeparatedBy

`Rule.SeparatedBy[Separator](binding)` matches zero or more occurrences delimited by a separator. The binding is a `List[R | SepValue[Separator]]` — separators are interleaved into the list along with the rule's results. `SepValue[Separator]` is the runtime value of the separator: for a token separator it is the corresponding `Lexeme`, and for a rule separator it is the rule's result type.

The type parameter `Separator` is the type of the separator symbol:

- For a **token separator**, pass the token as a type (e.g. ``MyLexer.`,` ``). The refinement on the tokenization makes the token name a valid type.
- For a **rule separator**, pass the rule's singleton type (e.g. `Sep.type`).

```scala sc-hidden sc-name:CommaLexer
import halotukozak.alpaca.*

val MyLexer = lexer:
  case "\\s+" => Token.Ignored
  case "," => Token[","]
  case x @ "[0-9]+" => Token["NUM"](x.toInt)
```

```scala sc-compile-with:CommaLexer
// Token separator: comma-separated numbers
object TokenSeparatorParser extends Parser:
  val Num: Rule[Int] = rule:
    case MyLexer.NUM(n) => n.value

  val root: Rule[List[Any]] = rule:
    case Num.SeparatedBy[MyLexer.`,`](items) => items
    // items: List[Int | Lexeme] -- values interleaved with comma lexemes
```

```scala sc-compile-with:CommaLexer
// Rule separator: separator carries a semantic value
object RuleSeparatorParser extends Parser:
  val Num: Rule[Int] = rule:
    case MyLexer.NUM(n) => n.value

  val Sep: Rule[String] = rule:
    case MyLexer.`,`(_) => ","

  val root: Rule[List[Any]] = rule:
    case Num.SeparatedBy[Sep.type](items) => items
// For "1,2,3", items == List(1, ",", 2, ",", 3)
```

The macro generates two synthetic non-terminals and four productions: an empty case (→ `Nil`), a bridge from the outer to the non-empty non-terminal, a singleton (→ `List(elem)`), and a left-recursive prepend (→ `elem :: separator :: list`). The bridge reverses the accumulated list once, so the binding sees the source order.

## Mixing EBNF Extractors

EBNF extractors combine freely with each other and with plain terminals in one production:

```scala sc-hidden sc-name:CalcLexerPreamble
import halotukozak.alpaca.*

val CalcLexer = lexer:
  case "\\s+" => Token.Ignored
  case "," => Token["COMMA"]
  case x @ "[0-9]+" => Token["NUMBER"](x.toInt)
  case x @ "[a-zA-Z_][a-zA-Z0-9_]*" => Token["ID"](x)
```

```scala sc-compile-with:CalcLexerPreamble
object MixedEbnfParser extends Parser:
  val Num: Rule[Int] = rule:
    case CalcLexer.NUMBER(n) => n.value

  val root: Rule[(Int, Option[Int], List[Int])] = rule:
    case (Num(n), CalcLexer.COMMA(_), Num.Option(opt), CalcLexer.COMMA(_), Num.List(lst)) =>
      (n, opt, lst)
      // n: Int, opt: Option[Int], lst: List[Int]

// "1,,3"       => (1, None, List(3))
// "1,2,1 2 3"  => (1, Some(2), List(1, 2, 3))
```

## Lexeme Bindings

A terminal extractor binds a `Lexeme` (see [The Lexeme Structure](lexer.md#the-lexeme-structure)): its `value`, `name` and matched `text`, plus every field of the lexer context as it was right after the match -- `column` and `line` with `LexerCtx.Default`, or your own fields with a custom context (see [Context Snapshots in Lexemes](lexer-context.md#context-snapshots-in-lexemes)). The fields are typed, so `id.column` is an `Int`, and a field the context does not have is a compile error:

```scala sc-compile-with:CalcLexerPreamble
object FieldAccessParser extends Parser:
  val root: Rule[(String, Int, Int)] = rule:
    case CalcLexer.ID(id) => (id.value, id.column, id.line)
```

**Pitfall:** `column` is the column right *after* the token. For a token `"42"` starting at column 1, `column` is 3.

See [Parser](parser.md) for grammar rules and [Between Stages](on-token-match.md) for how lexemes are built.
