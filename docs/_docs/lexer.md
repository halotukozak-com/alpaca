# Lexer

The Alpaca lexer transforms raw text into a stream of structured tokens. You define lexical rules as regex patterns paired with token constructors, and the macro generates a tokenizer at compile time.

```scala
import halotukozak.alpaca.*
```

<details>
<summary>Under the hood: compile-time processing</summary>

The `lexer` block is a Scala 3 macro. At compile time, it:

1. Parses and validates every regex pattern with Alpaca's own [`regex`](https://github.com/halotukozak/regex) library
2. Checks for shadowed patterns (`SubsetChecker`, a Brzozowski-derivative DFA inclusion check)
3. Builds a single DFA for all patterns at once (`TokenMatcher`)
4. Generates the tokenization loop

At runtime, `tokenize()` executes the generated code. If a pattern is invalid or shadows another, you get a compile error, not a runtime surprise.

</details>

## Defining a Lexer

A lexer is defined with the `lexer` block. Each `case` branch maps a regex pattern to a token constructor. At each position the lexer takes the **longest** match; when several patterns match equally long text, the one declared first wins (see [How a Token Is Chosen](#how-a-token-is-chosen)).

```scala sc-name:BrainLexer
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case ">" => Token["next"]
  case "<" => Token["prev"]
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case "\\." => Token["print"]
  case "," => Token["read"]
  case "\\[" => Token["jumpForward"]
  case "\\]" => Token["jumpBack"]
  case "." => Token.Ignored
  case "\n" => Token.Ignored
```

The result is a `Tokenization` object. It can tokenize input strings, provides typed accessors for each defined token (e.g., `BrainLexer.inc`), and exposes a `.tokens` list for introspection (`BrainLexer.tokens` returns all defined tokens including ignored ones).

## How a Token Is Chosen

A `lexer` block looks like a Scala `match`, but it does **not** pick the first `case` that fits. At each position in the input:

1. every pattern is tried against the remaining input,
2. the pattern that matches the **longest** text wins (*longest match*, also called *maximal munch*),
3. only when several patterns match text of the same length does declaration order decide: the `case` declared first wins.

This is the rule used by flex, JFlex and ocamllex (which, like Alpaca, writes its rules in `match`-like syntax).

```scala sc-name:longest-match
import halotukozak.alpaca.*

val Lexer = lexer:
  case "=" => Token["ASSIGN"]
  case "==" => Token["EQ"]
  case "if" => Token["IF"]
  case id @ "[a-z]+" => Token["ID"](id)
  case "\\s+" => Token.Ignored

println(Lexer.tokenize("== = if iffy").lexemes.map(_.name)) // List(EQ, ASSIGN, IF, ID)
```

`"=="` beats the earlier `"="` because it is longer. `if` is matched equally long by `IF` and `ID`, so the earlier `IF` wins the tie. `iffy` is longer only for `ID`, so it stays a single identifier instead of splitting into `if` + `fy`.

In practice this means:

- **Patterns that extend each other can go in any order.** `"="` and `"=="`, or `"[0-9]+"` and `"[0-9]+\\.[0-9]+"`, work either way round.
- **Keywords go before the identifier pattern.** That order only decides the exact tie. The other order is a compile-time error, because the keyword could never win (see [Shadowed Patterns](lexer-error-recovery.md#shadowed-patterns)).
- **Ignored patterns compete too.** A comment pattern `"//[^\n]*"` beats a `"/"` operator wherever both match, because it is longer.

Longest match decides one token at a time, so it has some consequences that surprise people coming from `match`:

- **It does not look at what comes next.** With `"-?[0-9]+"` for numbers and `"-"` for minus, `1-2` lexes as `1` followed by `-2`, because `-2` is longer than `-`. Handle the sign in the parser instead of in the number pattern.
- **It never backtracks.** With `"a"`, `"ab"` and `"bc"`, the input `abc` fails at `c`. The lexer commits to the longest `ab` and does not go back to try `a` + `bc`.
- **A single pattern takes as much as it can.** `"/\\*.*\\*/"` reads `/* a */ x /* b */` as one comment. Exclude the closing delimiter from the body instead, since lazy quantifiers are not supported:

```scala sc-name:longest-match-comment
import halotukozak.alpaca.*

val Lexer = lexer:
  case "/\\*([^*]|\\*+[^*/])*\\*+/" => Token.Ignored
  case id @ "[a-z]+" => Token["ID"](id)
  case "\\s+" => Token.Ignored

println(Lexer.tokenize("/* a */ x /* b */").lexemes.map(_.name)) // List(ID)
```

## Regular Expressions

Patterns use Java-style regex syntax and are validated at compile time by Alpaca's own regex library (a few Java constructs -- lookbehind, lazy and possessive quantifiers, `\\p{...}` classes -- are not supported; see [Invalid Regex](lexer-error-recovery.md#invalid-regex)). Backslashes must be doubled inside Scala string literals: `"\\+"` matches a literal `+`, and `"\\d+"` matches one or more digits.

```scala
import halotukozak.alpaca.*

val Lexer = lexer:
  // Literals that are regex metacharacters need escaping
  case "\\+" => Token["inc"]         // literal +
  case "\\." => Token["print"]       // literal .
  case "\\[" => Token["jumpForward"] // literal [

  // Non-metacharacters need no escaping
  case ">" => Token["next"]          // literal >
  case "-" => Token["dec"]           // literal -
  case "," => Token["read"]          // literal ,

  // Character classes and quantifiers
  case "[0-9]+" => Token["NUM"]      // one or more digits
  case "[a-zA-Z_][a-zA-Z0-9_]*" => Token["ID"] // identifier
  case "\\n" => Token.Ignored        // newline -- on its own so `line` tracking sees it
  case "[ \\t\\r]+" => Token.Ignored   // other whitespace, including the \r of Windows line endings
```

An invalid regex (unmatched parentheses, bad quantifiers) produces a compile-time error. A pattern that can never win -- because an earlier one always matches the same text at least as long -- produces a compile-time shadowing error; see [Shadowed Patterns](lexer-error-recovery.md#shadowed-patterns).

## Tokens

Tokens come in three forms.

### Named Tokens

`Token["NAME"]` creates a token with a `Unit` value. The token name becomes both the lexeme's `.name` field and the accessor on the lexer object. To access the matched text, use `lexeme.text` from the context snapshot.

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case ">" => Token["next"]
  case "<" => Token["prev"]
  case "\\+" => Token["inc"]
  case "\\s+" => Token.Ignored

val (_, lexemes) = BrainLexer.tokenize("> < +")
// lexemes: next, prev, inc
```

### Value-Bearing Tokens

`Token["NAME"](value)` attaches a computed value. Bind the matched text with `@` and transform it:

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "!" => Token["functionCall"]
  case "\\s+" => Token.Ignored

val (_, lexemes) = BrainLexer.tokenize("foo!")
// lexemes: functionName("foo"), functionCall
```

The type system tracks the value type: `BrainLexer.functionName` has type `Token["functionName", ..., String]`.

### Ignored Tokens

`Token.Ignored` matches text but excludes it from the token stream. Use it for whitespace, comments, and anything syntactically irrelevant.

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\+" => Token["inc"]
  case "." => Token.Ignored   // any non-command character
  case "\n" => Token.Ignored  // newlines

val (_, lexemes) = BrainLexer.tokenize("+ hello +\n+")
// lexemes: inc, inc, inc (everything else is ignored)
```

## Variable Binding

The `@` syntax binds the matched text to a variable, giving you a `String` to transform before passing to the token constructor.

```scala
import halotukozak.alpaca.*

val Lexer = lexer:
  case num @ "[0-9]+" => Token["NUM"](num.toInt)
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "\\s+" => Token.Ignored
```

Without `@`, you cannot access the matched text for transformation. `Token["inc"]` without a binding creates a token with `Unit` value. If you need the raw match, use `lexeme.text` from the context snapshot, or bind and pass it: `case x @ "\\+" => Token["inc"](x)`.

## Token Naming Rules

### The Pipeline

1. You write `Token["NAME"]` or `Token[variable.type]` in the lexer definition
2. The string inside the type parameter becomes the **token name**
3. To access the token on the lexer object (e.g., in parser rules), Scala's standard name encoding applies
4. If the name is a Scala keyword or contains operator characters, use **backticks**: `` BrainLexer.`\\+` ``

### Dynamic Token Names

When several patterns share the same structure, use alternation with `variable.type` to create one token per alternative:

```scala
import halotukozak.alpaca.*

val Lexer = lexer:
  case keyword @ ("if" | "else" | "while") => Token[keyword.type]
  case op @ ("\\+" | "-" | "\\*") => Token[op.type]
  case id @ "[a-zA-Z_][a-zA-Z0-9_]*" => Token["ID"](id)
  case "\\s+" => Token.Ignored

// Each alternative becomes a separate token:
// Lexer.`if`    : Token["if", ...]
// Lexer.`else`  : Token["else", ...]
// Lexer.`\\+`   : Token["\\+", ...]
// Lexer.-       : Token["-", ...]
```

Keywords like `if` always need backticks. `-` is a valid Scala identifier and does not.

## Tokenization

Call `tokenize()` on your lexer with an input string:

```scala sc-compile-with:BrainLexer
val (ctx, lexemes) = BrainLexer.tokenize("++[>+<-].")
```

The method returns a named tuple `(ctx: Ctx, lexemes: List[Lexeme])`:

- **`ctx`** -- the final lexer context after processing all input. With `LexerCtx.Default`, this includes `position` and `line`.
- **`lexemes`** -- matched tokens with `Token.Ignored` entries removed. Each `Lexeme` carries the token `name`, extracted `value`, and a snapshot of context fields at match time.

If the input contains a character that matches no pattern, `tokenize` throws a `RuntimeException`. See [Error Recovery](lexer-error-recovery.md) for alternatives.

### Tokenizing Files with LazyReader

For large files, use `LazyReader` instead of loading the entire file into a `String`. It reads characters on demand from the underlying file:

```scala sc-compile-with:BrainLexer
import java.nio.file.Path

val reader = LazyReader.from(Path.of("program.bf"))
val (ctx, lexemes) =
  try BrainLexer.tokenize(reader)
  finally reader.close()
```

`LazyReader.from(path)` accepts an optional `Charset` parameter (defaults to UTF-8). Always close the reader in a `finally` block (or use `scala.util.Using.resource`) so the file handle is released even if tokenization throws.

Note: `tokenize` currently wraps the reader in an `OffsetCharSequence`, so previously consumed characters are still retained in memory during tokenization. The lazy reader avoids the upfront cost of slurping the file but does not bound the working set.

## Token Value Types

The value type depends on how the token is defined:

| Definition | Value Type | Example |
|-----------|-----------|---------|
| `Token["NAME"]` | `Unit` | `Token["inc"]` -- value is `()` |
| `Token["NAME"](expr)` | Type of `expr` | `Token["NUM"](x.toInt)` -- value is `Int` |
| `Token.Ignored` | N/A | No lexeme produced |

`Token["NAME"]` without a value argument produces `Unit`. To carry the matched text as a value, bind it and pass it: `case x @ "pattern" => Token["NAME"](x)`.

## The Lexeme Structure

Every non-ignored match produces a `Lexeme`. From the caller's perspective, a lexeme exposes:

- **`name`** -- the token name as a string literal type (e.g., `"inc"`, `"functionName"`)
- **`value`** -- the extracted value (`Unit` for `Token["NAME"]`, or the computed type for `Token["NAME"](expr)`)

Each lexeme also carries a snapshot of all context fields at match time. The snapshot is accessed via `Selectable` -- you write `lexeme.position` or `lexeme.line` and the compiler resolves the types:

```scala
import halotukozak.alpaca.*

val Lexer = lexer:
  case num @ "[0-9]+" => Token["NUM"](num.toInt)
  case "\\s+" => Token.Ignored

val (_, lexemes) = Lexer.tokenize("42 13")
lexemes(0).position  // 3: Int (post-match position)
lexemes(0).line      // 1: Int
lexemes(0).text      // "42": String (the matched text, not remaining input)
```

<details>
<summary>Under the hood: how context snapshots work</summary>

The `Lexeme` class extends `Selectable` with a structural refinement that encodes every context field and its type. The compiler resolves `lexeme.position` to `Int` at compile time -- not by casting from `Any` at runtime. If you access a field that does not exist on the context type (e.g., `.indent` when using `LexerCtx.Default`), you get a compile error.

The `text` field in the snapshot is the **matched string**, not the remaining input. Even though `LexerCtx.text` holds the remaining input during lexing, the post-match update replaces it with `ctx.lastRawMatched` when building the snapshot.

The `position` value is the **post-match** cursor. The token `"42"` starts at column 1 but the snapshot records `position = 3` (1 + 2 characters consumed).

</details>

The parser appends `Lexeme.EOF` (name `"$"`, value `""`, empty fields) internally before running. You do not need to handle EOF in your lexer rules.

## Running Example: BrainLexer

The BrainFuck lexer introduced in [Getting Started](getting-started.md) tokenizes the eight BrainFuck commands. It uses `Token.Ignored` for everything else -- BrainFuck treats non-command characters as comments.

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case ">" => Token["next"]
  case "<" => Token["prev"]
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case "\\." => Token["print"]
  case "," => Token["read"]
  case "\\[" => Token["jumpForward"]
  case "\\]" => Token["jumpBack"]
  case "." => Token.Ignored
  case "\n" => Token.Ignored

val (_, lexemes) = BrainLexer.tokenize("++[>+<-].")
// lexemes.map(_.name) == List("inc", "inc", "jumpForward", "next", "inc", "prev", "dec", "jumpBack", "print")
```

Pattern order matters here: `"\\."` (literal dot -- the BF print command) must appear before `"."` (any character -- the catch-all). Otherwise the catch-all shadows the print command and you get a compile error.

Later pages extend this lexer with [custom context](lexer-context.md) (bracket counting), [error recovery](lexer-error-recovery.md), and value-bearing tokens for function names.

See [Debug Settings](debug-settings.md) for dumping the parser's generated grammar tables during compilation.
