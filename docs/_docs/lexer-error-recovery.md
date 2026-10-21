# Lexer Error Recovery

The Alpaca lexer provides two layers of error feedback: compile-time validation that catches many problems before your program runs, and runtime error strategies for input that does not match any pattern.

<details>
<summary>Under the hood: compile-time validation</summary>

The `lexer` macro validates token definitions at compile time. Pattern shadowing, invalid or unsupported regex syntax, empty matches, duplicate token names and guards are caught during compilation. The macro checks every pattern against the ones declared before it -- each on its own, and together -- using Alpaca's own `regex` library, so that every pattern can produce a token.

</details>

## Compile-Time Errors

### Shadowed Patterns

The lexer uses **longest match**: at each position it takes the longest text any pattern matches, and when several patterns match equally long text, the one declared first wins. A shadowing error occurs when a pattern can never win. The classic case is a keyword declared after a general identifier pattern -- `if` is matched equally long by both, so the identifier always takes it:

```scala sc:fail
import halotukozak.alpaca.*

val MyLexer = lexer:
  case id @ "[a-z]+" => Token["ID"](id)
  case "if" => Token["IF"]  // error: shadowed by ID
```

The compiler reports:

```
Token "IF" can never match: every input it matches is also matched by "ID",
which is defined earlier, so it always wins.
Declare "IF" ("if") before "ID" ("[a-z]+").
```

The fix: declare the keyword first. Longest match still turns `iffy` into a single `ID`, so keywords never split identifiers.

A pattern can also be dead because several earlier patterns cover it together, though none does alone:

```scala sc:fail
import halotukozak.alpaca.*

val MyLexer = lexer:
  case "a" => Token["A"]
  case "b" => Token["B"]
  case "[ab]" => Token["AB"]  // error: covered by A and B together
```

```
Token "AB" can never match: every input it matches is also matched by "A" or "B",
which are defined earlier, so one of them always wins.
"AB" is redundant: remove it, or narrow "A" and "B" so they no longer cover it.
```

A pattern is reported only when it really can never win -- an earlier pattern matching just a *prefix* of it (`"a"` before `"ab"`) is fine. When one pattern's language contains the other's in the wrong order, e.g. `"[0-9]+(\\.[0-9]+)?"` before `"[0-9]+"`, give the later pattern inputs of its own -- e.g. `"[0-9]+\\.[0-9]+"` before `"[0-9]+"` -- or use a single pattern. See [How a Token Is Chosen](lexer.md#how-a-token-is-chosen) for which pattern wins where two can match, and [Shadowing Detection](theory/lexer-fa.md#shadowing-detection) for how the check works.

### Invalid Regex

Patterns use Java-style regex syntax, but they are parsed by Alpaca's own [`regex`](https://github.com/halotukozak-com/regex) library, not by `java.util.regex`. Malformed patterns -- unmatched parentheses, invalid quantifiers, bad character class syntax -- and the few constructs the library does not support (for example lookbehind `(?<=...)`, backreferences like `\\1`, the word boundary `\\b`, lazy quantifiers like `*?` and possessive quantifiers like `++`) produce a compile-time error naming the token and the position in the pattern:

```
Invalid regex pattern for token "T": unsupported regex feature `lookbehind` (at position 3 in "(?<=a)b")
```

### Empty Matches

A pattern that can match the empty string, such as `"a*"` or `"[0-9]*"`, would let the lexer match without moving forward. It is a compile-time error at its rule, for ignored tokens too:

```
Token "A" can match the empty string ("a*"); a token must consume at least one character
```

Require at least one character, e.g. `"a+"` instead of `"a*"`.

### Duplicate Token Names

Each token name can be defined by only one rule. A second rule with the same name is reported at that rule:

```
Token name "bracket" is defined 2 times. Combine the patterns into a single case using alternatives: case "\\[" | "\\]" => ...
```

Combine the patterns as the message suggests, or give each rule its own name:

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\[" | "\\]" => Token["bracket"]
```

### Rule Bodies Must End With a Token

A rule body can run any code, but its last expression must be the `Token[...]` or `Token.Ignored` call itself. Ending with a value that holds one, like `val token = Token["inc"]; token`, reports:

```
A lexer rule must end with `Token["NAME"]`, `Token["NAME"](value)` or `Token.Ignored`, written directly as its last expression
```

### Guards Not Supported

Pattern guards (`case "regex" if condition =>`) are not supported in lexer rules. The workaround is to move the condition into the rule body:

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
    // Validate inside the body, not as a guard
    require(ctx.squareBrackets > 0, "Mismatched brackets")
    ctx.squareBrackets -= 1
    Token["jumpBack"]
  case "\\+" => Token["inc"]
  case "." => Token.Ignored
```

## Runtime Error Handling

When `tokenize()` hits input that matches no pattern, it records a `LexerError` and consults the `ErrorHandling` strategy for the context type to decide how to go on. Either way, `tokenize()` does not throw: it returns a `Result.Failure` listing the errors.

Only unmatched input becomes a `LexerError`. An exception thrown by a rule body, like the failed `require` in the bracket-counting lexer of [Lexer Context](lexer-context.md#custom-context), propagates out of `tokenize()` unchanged.

### Default Behavior

By default the lexer stops at the first unmatched character. The `LexerError` names it in its `message`:

```
Unexpected character '@'
```

Like a lexeme, the error carries the context's fields as they were where the character starts, so with `LexerCtx.Default` its `line` and `column` give the position. Columns count Unicode code points, so an emoji is one column.

`getOrThrow` throws the errors as a `LexerException`; match on the result to handle them without an exception:

```scala
import halotukozak.alpaca.*

val MyLexer = lexer:
  case "[a-z]+" => Token["WORD"]
  case "\\s+" => Token.Ignored

MyLexer.tokenize("abc @def") match
  case Result.Success(_, lexemes) => println(lexemes.size)
  case Result.Failure(_, _, errors) =>
    errors.foreach: error =>
      println(error.message) // Unexpected character '@'
      println(error.column)  // 5
```

### Error Handling Strategies

You can provide a custom `ErrorHandling` instance for your context type. Every strategy records the unmatched input as a `LexerError`; they differ in how tokenizing goes on:

| Strategy | Behavior |
|----------|----------|
| `Stop` (default) | Stop at the unmatched character; the failure has no `recovered` lexemes |
| `SkipOne` | Skip the single unmatched character and continue |
| `SkipToNextMatch` | Skip to the next successful match and continue; one `LexerError` covers the whole skipped run |

With `SkipOne` or `SkipToNextMatch` the lexer reaches the end of the input, so the `Result.Failure` also carries the lexemes it collected in `recovered`:

```scala
import halotukozak.alpaca.*

// Skip unrecognized characters, but keep a record of them
given ErrorHandling[LexerCtx.Default, LexerError] = (_, _) => ErrorHandling.Strategy.SkipOne

val MyLexer = lexer:
  case "[a-z]+" => Token["WORD"]
  case "\\s+" => Token.Ignored

MyLexer.tokenize("abc @def") match
  case Result.Failure(_, recovered, errors) =>
    println(recovered.map(_.map(_.name))) // Some(List(WORD, WORD))
    println(errors.map(_.message))        // List(Unexpected character '@')
  case Result.Success(_, _) => ()
```

The strategy receives the context and the `LexerError` for the unmatched character, so it can choose per character. It is the same `ErrorHandling` type the parser uses, with `LexerError` as its error type. Declare it for `LexerError.Of[MyCtx]` instead to read the context's fields from the error with their types:

```scala
import halotukozak.alpaca.*

// Skip unrecognized characters on the first line, stop on any later one
given ErrorHandling[LexerCtx.Default, LexerError.Of[LexerCtx.Default]] = (_, error) =>
  if error.line > 1 then ErrorHandling.Strategy.Stop else ErrorHandling.Strategy.SkipOne
```

To look past the unmatched character, call `ctx.peek(n)`: it returns at most the next `n` characters of the input, starting with that character. Here a stray character is skipped, but a comment that is never closed stops the lexer:

```scala
import halotukozak.alpaca.*

given ErrorHandling[LexerCtx.Default, LexerError] = (ctx, _) =>
  if ctx.peek(2) == "/*" then ErrorHandling.Strategy.Stop else ErrorHandling.Strategy.SkipOne

val MyLexer = lexer:
  case "/\\*([^*]|\\*+[^*/])*\\*+/" => Token.Ignored
  case "[a-z]+" => Token["WORD"]
  case "\\s+" => Token.Ignored

MyLexer.tokenize("a @ b /* c") match
  case Result.Failure(_, recovered, errors) =>
    println(recovered)             // None: it stopped at the comment
    println(errors.map(_.message)) // List(Unexpected character '@', Unexpected character '/')
  case Result.Success(_, _) => ()
```

### Changing the Context from a Handler

A handler written as a plain lambda gets the context as a value: it can read the fields, but not assign them. Build it with `ErrorHandling.lexer` instead, and `ctx` works as in a rule body. Assigning a field changes the context the lexer goes on with, so the fields can stay `val`s:

```scala
import halotukozak.alpaca.*

case class CountingCtx(line: Line = Line.Start, skipped: Int = 0) extends LexerCtx

given ErrorHandling[CountingCtx, LexerError.Of[CountingCtx]] = ErrorHandling.lexer[CountingCtx]: error =>
  ctx.skipped += 1
  if ctx.peek(2) == "/*" then ErrorHandling.Strategy.Stop else ErrorHandling.Strategy.SkipOne

val CountingLexer = lexer[CountingCtx]:
  case "[a-z]+" => Token["WORD"]
  case "\\s+" => Token.Ignored

CountingLexer.tokenize("a @ b ! c") match
  case Result.Failure(ctx, recovered, errors) =>
    println(ctx.skipped)                               // 2
    println(recovered.map(_.map(_.skipped)))           // Some(List(0, 1, 2))
    println(errors.map(e => (e.unexpected, e.skipped))) // List((@,0), (!,1))
  case Result.Success(_, _) => ()
```

Each error keeps the context from before its handler ran, as a lexeme keeps the context from before its rule. You do not need a field to collect the errors: the `Result.Failure` lists every one, with the context's fields.

Note that the BrainFuck lexer from [Getting Started](getting-started.md) handles unknown characters with a `"." => Token.Ignored` catch-all pattern instead. That is the recommended approach when unknown input is not an error at all, as BrainFuck comments are: a catch-all produces no `LexerError`.
