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

`Rule.List(binding)` binds to a `List[R]`. The macro generates a left-recursive accumulation production (empty → `Nil`, append → `list :+ elem`).

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

The macro generates two synthetic non-terminals and four productions: an empty case (→ `Nil`), a bridge from the outer to the non-empty non-terminal (identity), a singleton (→ `List(elem)`), and a left-recursive append (→ `list :+ separator :+ elem`).

## Lexeme Fields

When a terminal extractor binds a variable, the variable is a `Lexeme` carrying both the value and a snapshot of the lexer context at match time. Access fields with dot notation:

| Field | Type | Description |
|-------|------|-------------|
| `binding.value` | Token-specific | The extracted semantic value |
| `binding.name` | `String` | The token name (e.g., `"functionName"`) |
| `binding.text` | `String` | The raw matched characters |
| `binding.position` | `Int` | Character position (post-match) |
| `binding.line` | `Int` | Line number |

```scala sc-hidden sc-name:CalcLexerPreamble
import halotukozak.alpaca.*

val CalcLexer = lexer:
  case "\\s+" => Token.Ignored
  case "," => Token["COMMA"]
  case x @ "[0-9]+" => Token["NUMBER"](x.toInt)
  case x @ "[a-zA-Z_][a-zA-Z0-9_]*" => Token["ID"](x)
```

```scala sc-compile-with:CalcLexerPreamble
object LexemeFieldsParser extends Parser:
  val Num: Rule[Int] = rule:
    case CalcLexer.NUMBER(n) => n.value

  val root: Rule[(Int, Option[Int], List[Int])] = rule:
    case (Num(n), CalcLexer.COMMA(_), Num.Option(opt), CalcLexer.COMMA(_), Num.List(lst)) =>
      (n, opt, lst)
      // n: Int, opt: Option[Int], lst: List[Int]

// "1,,3"       => (1, None, List(3))
// "1,2,1 2 3"  => (1, Some(2), List(1, 2, 3))
```

## Lexeme Object Structure

When a terminal extractor binds a variable, the variable is a `Lexeme`.
A `Lexeme` is the record that crosses the lexer-to-parser boundary and carries both the extracted value and a snapshot of the lexer context at match time.

The user-visible fields are:

- **`name: String`** — the token type name (e.g., `"NUMBER"`, `"PLUS"`)
- **`value: T`** — the extracted value; the type depends on the `Token["NAME"](value)` definition in the lexer
- **`text: String`** — the raw matched characters; always a `String` regardless of token type
- **`position: Int`** — 1-based column within the current line at match time (post-match; resets after a token that matches exactly `"\n"`). Only present when the lexer context has a `Column` field, as `LexerCtx.Default` does.
- **`line: Int`** — line number at match time. Only present when the lexer context has a `Line` field, as `LexerCtx.Default` does.

`Lexeme` extends `Selectable`, so custom context fields captured at match time are also accessible by name, type-safely at compile time — `id.position` returns `Int`, not `Any`. There is no aggregate `fields: Map[String, Any]` accessor; each field is exposed individually through structural selection.
The type refinement is encoded in the `tokenize()` return type and flows through to the parser.

A concrete example of the snapshot embedded in each lexeme:

```scala
import halotukozak.alpaca.*

val MiniLang = lexer:
  case num @ "[0-9]+" => Token["NUM"](num.toInt)
  case "\\+"          => Token["PLUS"]
  case "\\s+"         => Token.Ignored

val (_, lexemes) = MiniLang.tokenize("42 + 13")
// lexemes(0): name = "NUM",  value = 42, text = "42", position = 3, line = 1
// lexemes(1): name = "PLUS", value = (), text = "+",  position = 5, line = 1
// lexemes(2): name = "NUM",  value = 13, text = "13", position = 8, line = 1

// Inside parser rules, access via dot notation:
//   n.value     == 42     (Int)
//   n.text      == "42"   (String — matched characters)
//   n.position  == 3      (Int — post-match character position)
//   n.line      == 1      (Int — line number)
```

Available fields depend on the `LexerCtx` used to build the lexer:
- `LexerCtx.Default` provides `text`, `position`, and `line`.
- A `Line` field (already included in `LexerCtx.Default`) provides `line`; a `Column` field provides `position`.
- Custom context fields appear if the lexer context declares them.

See [Between Stages](on-token-match.md) for the full Lexeme structure, context snapshot lifecycle, and how positional values are computed.

## Accessing Fields on a Bound Lexeme

After binding a terminal, use dot notation to access any field from the context snapshot:

```scala sc-compile-with:CalcLexerPreamble
import scala.collection.mutable

case class ErrorTrackingCtx(
  errors: mutable.Buffer[(String, Any, Int)] = mutable.Buffer.empty,
) extends ParserCtx

object FieldAccessParser extends Parser[ErrorTrackingCtx]:
  val root: Rule[Int] = rule:
    case CalcLexer.ID(id) =>
      val name = id.value      // String — the identifier text
      val raw  = id.text       // String — matched characters
      val pos  = id.position   // Int — character position
      val ln   = id.line       // Int — line number
      // Use for error reporting:
      ctx.errors.append(("undefined", id, id.line))
      pos
```

Field access is type-safe via the `Selectable` refinement on `Lexeme`. The `position` and `line` fields are available when the lexer uses `LexerCtx.Default` or a custom context with `Column`/`Line` fields. Custom context fields (e.g., `name.squareBrackets`) are accessible if the lexer context declares them.

**Pitfall:** `position` records the post-match cursor position (after advancing by the token length), not the start position.
For a token `"42"` starting at column 1, `position` is 3. See [Between Stages](on-token-match.md) for the exact semantics.

See [Parser](parser.md) for grammar rules and [Between Stages](on-token-match.md) for how lexeme snapshots are constructed.
