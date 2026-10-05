# Lexer Context

Every Alpaca lexer carries a **context** object that evolves as the input is processed. Context lets you do stateful lexing: counting brackets, tracking indentation, recording whether you are inside a string literal, or anything else that depends on the tokens seen so far.

By default, the lexer uses `LexerCtx.Default`, which gives you position and line tracking with no extra setup.

<details>
<summary>Under the hood: how tracking fields update</summary>

When you write `lexer[MyCtx]:`, the Alpaca macro inspects `MyCtx`'s case fields at compile time. For every field whose type provides a `given Tracking`, it wires the corresponding per-token update into the generated tokenizer. After each match the engine threads a single functional `copy` of the context through those updates -- so tracked fields stay immutable `val`s and still advance automatically.

</details>

## Default Context

When you write a `lexer:` block without a type parameter, the lexer uses `LexerCtx.Default`. It is a case class with two tracking fields:

```scala
import halotukozak.alpaca.*

final case class Default(
  position: Column = Column.Start,
  line: Line = Line.Start,
) extends LexerCtx
```

- `position` -- 1-based column within the current line, incremented by the matched length and reset to 1 when a token matches exactly `"\n"`
- `line` -- 1-based line number, incremented when a token matches exactly `"\n"`

> **Note:** the built-in trackers look at the whole matched text, not at individual characters. A token that matches `"\n"` together with other characters (e.g. `case "\\s+" => Token.Ignored` matching `" \n  "`) does not advance `line`. If you need line numbers, give newlines their own pattern -- `case "\n" => Token.Ignored` -- before any broader whitespace pattern, and keep that pattern from matching `\n` (e.g. `"[ \t]+"`).

`Column` and `Line` are opaque subtypes of `Int`, so `ctx.position` and `ctx.line` read as plain `Int`s everywhere. Each carries a `given Tracking` that the lexer macro finds and applies after every match.

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case "\\s+" => Token.Ignored

val lexed = BrainLexer.tokenize("+ - +")


val lexemes = lexed.getOrThrow
// lexed.ctx.position == 6
// lexed.ctx.line     == 1
//
// Each lexeme carries a snapshot of context fields at match time:
// inc: text="+", position=2, line=1
// dec: text="-", position=4, line=1
// inc: text="+", position=6, line=1
```

Position advances by the matched length after each token. The snapshot captures values *after* the token was consumed, not before.

## The LexerCtx Trait

`LexerCtx` is the base trait for all lexer contexts. Any custom context must satisfy two rules:

1. **It must be a case class** -- `LexerCtx` extends `Product` directly, and the auto-derivation machinery requires a `Product` instance.
2. **All fields must have default values** -- The `Empty[T]` derivation macro reads default parameter values from the companion to construct the initial context. If any parameter lacks a default, the macro fails at compile time.

> **Warning:** Do not declare `var text`, `var lastLexeme`, or `var lastRawMatched` in your case class. These fields are provided by the `LexerCtx` trait and managed internally by the lexer. Redeclaring them shadows the internal fields and breaks tokenization.

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

Inside a `lexer[Ctx]:` block, the name `ctx` is implicitly available and refers to the current context object. You can read any field and assign to it -- the assignment is rewritten to a `copy`:

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

Each `Lexeme` carries a snapshot of all context fields at the moment of the match. Access them by name via `Selectable`:

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\+" => Token["inc"]
  case "\\s+" => Token.Ignored

val lexemes = BrainLexer.tokenize("+ +").getOrThrow
lexemes(0).position  // 2: Int (post-match position)
lexemes(0).line      // 1: Int
lexemes(0).text      // "+": String (the matched text, not remaining input)
```

The type safety comes from `Selectable`: the `tokenize()` return type carries a structural refinement that encodes every context field and its type. If you access a field that does not exist on the context type (e.g., `.brackets` when using `LexerCtx.Default`), the compiler reports a type error.

Two important details:

- **`text` is the matched string**, not the remaining input. The snapshot replaces `text` with the actual matched characters for that token.
- **Snapshots are independent.** Each lexeme captures the context state at its own match time. Modifying the context after a match does not retroactively change earlier lexemes.

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

**`Column`** -- an opaque `Int` that advances by the matched length after each token and resets to 1 when the matched text is exactly `"\n"`.

**`Line`** -- an opaque `Int` that increments when the matched text is exactly `"\n"`.

Each is a plain case-class field with a `given Tracking` in its companion. Use either one, both, or neither. `LexerCtx.Default` uses both. To add them to a custom context:

```scala
import halotukozak.alpaca.*

case class BrainLexContext(
  squareBrackets: Int = 0,
  position: Column = Column.Start,
  line: Line = Line.Start,
) extends LexerCtx
```

With this context, every lexeme carries `squareBrackets`, `position`, and `line`. `squareBrackets` changes only where a rule body assigns it; `position` and `line` advance automatically after every match.

## The Post-Match Update

After every successful token match -- once the text cursor has already advanced past the matched text -- the lexer:

1. applies each tracked field's `Tracking` update (`position`, `line`, and any custom fragments),
2. applies the rule body's own context changes,
3. records the lexeme snapshot.

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

For cases where you need no tracking at all -- no position, no line counter, no custom fields -- use `LexerCtx.Empty`:

```scala
import halotukozak.alpaca.*

val Lexer = lexer[LexerCtx.Empty]:
  case "\\+" => Token["inc"]
  case "." => Token.Ignored

val lexemes = Lexer.tokenize("+ +").getOrThrow
// lexemes(0).text == "+"  -- the only snapshot field: no position, no line
```

See [Between Stages](on-token-match.md) to learn how context snapshots in lexemes flow into the parser.
