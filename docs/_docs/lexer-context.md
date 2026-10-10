# Lexer Context

Every Alpaca lexer carries a **context** object that evolves as the input is processed. Context lets you do stateful lexing: counting brackets, tracking indentation, recording whether you are inside a string literal, or anything else that depends on the tokens seen so far.

By default, the lexer uses `LexerCtx.Default`, which gives you line and column tracking with no extra setup.

<details>
<summary>Under the hood: how tracking fields update</summary>

When you write `lexer[MyCtx]:`, the Alpaca macro inspects `MyCtx`'s case fields at compile time. For every field whose type provides a `given Tracking`, it wires the corresponding per-token update into the generated tokenizer. After each match the engine threads a single functional `copy` of the context through those updates -- so tracked fields stay immutable `val`s and still advance automatically.

</details>

## Default Context

When you write a `lexer:` block without a type parameter, the lexer uses `LexerCtx.Default`. It is a case class with two tracking fields:

```scala
import halotukozak.alpaca.*

final case class Default(
  column: Column = Column.Start,
  line: Line = Line.Start,
) extends LexerCtx
```

- `column` -- 1-based column within the current line, counted in Unicode code points (an emoji is one column), advanced by the matched text and restarted after every `\n` in it
- `line` -- 1-based line number, advanced by every `\n` in the matched text

Newlines are counted wherever they appear in a match, so `case "\\s+" => Token.Ignored` matching `" \n  "` advances `line` by one and leaves `column` at 3. A Windows line ending `\r\n` counts once, through its `\n`.

`Column` and `Line` are opaque subtypes of `Int`, so `ctx.column` and `ctx.line` read as plain `Int`s everywhere. Each carries a `given Tracking` that the lexer macro finds and applies after every match.

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case "\\s+" => Token.Ignored

val lexed = BrainLexer.tokenize("+ - +")


val lexemes = lexed.getOrThrow
// lexed.ctx.column == 6
// lexed.ctx.line   == 1
//
// Each lexeme records where its token starts:
// inc: text="+", column=1, line=1
// dec: text="-", column=3, line=1
// inc: text="+", column=5, line=1
```

In the context, `column` and `line` are the position right after the last match. In a lexeme, they are the position where its token **starts** -- the place to point an error message at.

## The LexerCtx Trait

`LexerCtx` is the base trait for all lexer contexts. Any custom context must satisfy two rules:

1. **It must be a case class** -- `LexerCtx` extends `Product` directly, and the auto-derivation machinery requires a `Product` instance.
2. **All fields must have default values** -- The `lexer` macro reads default parameter values from the companion to construct the initial context. If any parameter lacks a default, the macro fails at compile time.

> **Note:** Context fields are read by name on lexemes and lexer errors, so `name`, `value`, `text` (taken by `Lexeme`), `unexpected` and `message` (taken by `LexerError`) are reserved: the `lexer` macro reports a field with one of these names as a compile error.

State fields are ordinary immutable `val` case-class parameters. Writing `ctx.count += 1` in a rule body still type-checks and does the expected thing -- the `lexer` macro rewrites every such assignment into a functional `copy` before the rule is compiled. A field of a mutable collection type (e.g., `scala.collection.mutable.Stack`) works too: you mutate the collection in place and never reassign the field.

## Custom Context

The BrainFuck lexer from [Getting Started](getting-started.md) does not validate bracket matching -- it tokenizes `]` even without a prior `[`. To fix that, we track bracket depth in a custom context:

```scala sc-name:lc-brainlex
import halotukozak.alpaca.*

case class BrainLexContext(
  brackets: Int = 0,
  squareBrackets: Int = 0,
) extends LexerCtx

val BrainLexer = lexer[BrainLexContext]:
  case ">" => Token["next"]
  case "<" => Token["prev"]
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case "\\." => Token["print"]
  case "," => Token["read"]
  case "\\[" =>
    ctx.squareBrackets += 1
    Token["jumpForward"]
  case "\\]" =>
    require(ctx.squareBrackets > 0, "Mismatched brackets")
    ctx.squareBrackets -= 1
    Token["jumpBack"]
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "\\(" =>
    ctx.brackets += 1
    Token["functionOpen"]
  case "\\)" =>
    require(ctx.brackets > 0, "Mismatched brackets")
    ctx.brackets -= 1
    Token["functionClose"]
  case "!" => Token["functionCall"]
  case "." => Token.Ignored
  case "\n" => Token.Ignored
```

The type parameter `lexer[BrainLexContext]` tells the macro which context to use. The final context state is the `ctx` of the `Result` that `tokenize()` returns:

```scala sc-compile-with:lc-brainlex
val lexed = BrainLexer.tokenize("[>+<-]")
val lexemes = lexed.getOrThrow
// lexed.ctx.squareBrackets == 0  -- balanced
```

## Accessing Context in Patterns

Inside a `lexer[Ctx]:` block, the name `ctx` is implicitly available and refers to the current context object. You can read any field and assign to it -- the assignment is rewritten to a `copy`. Outside a lexer rule, `ctx` and `Token[...]` do not compile:

```scala sc-compile-with:lc-brainlex
val ExampleLexer = lexer[BrainLexContext]:
  case "\\[" =>
    ctx.squareBrackets += 1       // write
    Token["jumpForward"]
  case "\\]" =>
    require(ctx.squareBrackets > 0, "Mismatched brackets")  // read + validate
    ctx.squareBrackets -= 1       // write
    Token["jumpBack"]
```

> **Note on guards:** Guards (`case "regex" if condition =>`) are not supported in lexer rules. Use the rule body to read context state and decide what to emit -- you cannot filter matches before they occur.

## Context Snapshots in Lexemes

Each `Lexeme` carries a snapshot of all context fields right after its rule body ran (see [The Lexeme Structure](lexer.md#the-lexeme-structure)), with one exception: fields with a `given Tracking` (such as `Line` and `Column`) hold their values from before the match, even if the rule body assigned them, so a lexeme describes where its token starts. Snapshots are independent: later changes to the context do not reach lexemes that were already produced. Accessing a field the context type does not have (e.g., `.brackets` with `LexerCtx.Default`) is a compile error.

For custom contexts, all case class fields appear in the snapshot:

```scala
import halotukozak.alpaca.*

case class BrainLexContext(
  squareBrackets: Int = 0,
) extends LexerCtx

val BrainLexer = lexer[BrainLexContext]:
  case "\\[" =>
    ctx.squareBrackets += 1
    Token["jumpForward"]
  case "\\]" =>
    ctx.squareBrackets -= 1
    Token["jumpBack"]
  case "\\+" => Token["inc"]
  case "." => Token.Ignored

val lexemes = BrainLexer.tokenize("[+[+]]").getOrThrow
// lexemes(0).squareBrackets == 1  -- after first [
// lexemes(2).squareBrackets == 2  -- after second [
// lexemes(4).squareBrackets == 1  -- after first ]
```

## Built-in Tracking Fragments

Alpaca ships two ready-made tracking fields, both re-exported from `halotukozak.alpaca`:

**`Column`** -- an opaque `Int` that advances by the number of code points matched after each token; when the match contains a `\n`, it becomes 1 plus the code points after the last one.

**`Line`** -- an opaque `Int` that advances by the number of `\n`s in each match.

Each is a plain case-class field with a `given Tracking` in its companion. Use either one, both, or neither. `LexerCtx.Default` uses both. Like any context field, they can be called anything, and lexemes and `LexerError`s carry them under that name. To add them to a custom context:

```scala
import halotukozak.alpaca.*

case class BrainLexContext(
  squareBrackets: Int = 0,
  column: Column = Column.Start,
  line: Line = Line.Start,
) extends LexerCtx
```

With this context, every lexeme carries `squareBrackets`, `column`, and `line`. `squareBrackets` changes only where a rule body assigns it; `column` and `line` advance automatically after every match.

## The Post-Match Update

After every successful token match -- once the text cursor has already advanced past the matched text -- the lexer:

1. applies each tracked field's `Tracking` update (`column`, `line`, and any custom fragments),
2. applies the rule body's own context changes,
3. records the lexeme snapshot, taking every tracked field from before step 1.

Steps 1 and 3 are derived by the `lexer` macro from the context's case fields -- there is nothing to wire up by hand.

<details>
<summary>Under the hood: custom tracking fragments</summary>

A tracking fragment is any field type that provides a `given Tracking[F]`. `Tracking[F]` is a single-method function `(matched: String, field: F) => F`: given the raw text just matched and the field's current value, return its next value. The `lexer` macro finds one for each case field and threads a functional `copy` through them after every match.

```scala
import halotukozak.alpaca.*

// Step 1: a distinct type for what you track
opaque type Indent <: Int = Int
object Indent:
  val Start: Indent = 0

  // Step 2: a given Tracking in its companion
  given Tracking[Indent] =
    case ("\t", n) => n + 1
    case ("\n", _) => 0
    case (_, n)    => n

// Step 3: use it as a context field
case class MyCtx(indent: Indent = Indent.Start) extends LexerCtx

// Step 4: the lexer macro applies the update automatically
val Lexer = lexer[MyCtx]:
  case "\t" => Token.Ignored
  case "\n" => Token.Ignored
  case id @ "[a-z]+" => Token["ID"](id)
```

No inheritance, no trait companion, no composition macro: a fragment is just a field type plus its `given`.

</details>

## LexerCtx.Empty

For cases where you need no tracking at all -- no column, no line counter, no custom fields -- use `LexerCtx.Empty`:

```scala
import halotukozak.alpaca.*

val Lexer = lexer[LexerCtx.Empty]:
  case "\\+" => Token["inc"]
  case "." => Token.Ignored

val lexemes = Lexer.tokenize("+ +").getOrThrow
// lexemes(0).text == "+"  -- no context fields: no column, no line
```

See [Between Stages](on-token-match.md) to learn how context snapshots in lexemes flow into the parser.
