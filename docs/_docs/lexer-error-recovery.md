# Lexer Error Recovery

The Alpaca lexer provides two layers of error feedback: compile-time validation that catches many problems before your program runs, and runtime error strategies for input that does not match any pattern.

<details>
<summary>Under the hood: compile-time validation</summary>

The `lexer` macro validates token definitions at compile time. Pattern shadowing, invalid or unsupported regex syntax, and guards are caught during compilation. The macro performs pairwise regex inclusion checks using Alpaca's own `regex` library (`SubsetChecker`) to ensure every pattern is reachable.

</details>

## Compile-Time Errors

### Shadowed Patterns

The lexer uses **longest match**: at each position it takes the longest text any pattern matches, and when several patterns match equally long text, the one declared first wins. A shadowing error occurs when a pattern can never win. The classic case is a keyword declared after a general identifier pattern -- `if` is matched equally long by both, so the identifier always takes it:

```scala sc:fail
import halotukozak.alpaca.*

val Lexer = lexer:
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

A pattern is reported only when it really can never win -- an earlier pattern matching just a *prefix* of it (`"a"` before `"ab"`) is fine. When one pattern's language contains the other's in the wrong order, e.g. `"[0-9]+(\\.[0-9]+)?"` before `"[0-9]+"`, give the later pattern inputs of its own -- e.g. `"[0-9]+\\.[0-9]+"` before `"[0-9]+"` -- or use a single pattern. See [Shadowing Detection](theory/lexer-fa.md#shadowing-detection) for the details.

### Invalid Regex

Patterns use Java-style regex syntax, but they are parsed by Alpaca's own [`regex`](https://github.com/halotukozak/regex) library, not by `java.util.regex`. Malformed patterns -- unmatched parentheses, invalid quantifiers, bad character class syntax -- and the few constructs the library does not support (for example lookbehind `(?<=...)`, lazy quantifiers like `*?`, possessive quantifiers like `++`, and `\\p{...}` classes) produce a compile-time error naming the token and the position in the pattern:

```
Invalid regex pattern for token "T": unsupported regex feature `lookbehind` (at position 3 in "(?<=a)b")
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

## Pattern Ordering

The lexer takes the longest match, and on a tie the pattern declared first wins. The general rule: **more specific patterns before more general ones** -- the order only matters where two patterns can match the same text.

In the BrainFuck lexer, this matters for the print command vs the catch-all:

```scala
import halotukozak.alpaca.*

// RIGHT -- literal dot before catch-all dot
val BrainLexer = lexer:
  case "\\." => Token["print"]   // specific: literal dot (BF print)
  case "." => Token.Ignored      // general: any character (catch-all)
```

If you reverse the order, `"."` shadows `"\\."` and you get a shadowing compile error.

Patterns that can never match the same text can go in any order. In the extended BrainFuck lexer, the function-name pattern `"[A-Za-z]+"` and the single-character commands never overlap, so their relative order does not matter -- only the `"."` catch-all has to stay last:

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  // ... other single-char commands ...
  case name @ "[A-Za-z]+" => Token["functionName"](name)  // no overlap with the commands
  case "." => Token.Ignored                                // catch-all: must come last
```

## Runtime Error Handling

When `tokenize()` hits a character that matches no pattern, the lexer consults the `ErrorHandling` strategy for the context type.

### Default Behavior

The default strategy throws a `RuntimeException`:

```
Unexpected character at line 1, position 5: '@'
```

Only `LexerCtx.Default` comes with an `ErrorHandling` that reports line and position. Every other context -- `LexerCtx.Empty` and custom contexts alike, even ones that declare `Column`/`Line` fields -- falls back to a handler that shows only the character (`Unexpected character: '@'`), unless you provide your own `ErrorHandling` as shown below.

### Error Handling Strategies

You can provide a custom `ErrorHandling` instance for your context type. Four strategies are available:

| Strategy | Behavior |
|----------|----------|
| `Throw(ex)` | Throw the given exception, aborting tokenization immediately |
| `IgnoreChar` | Skip the single unmatched character and continue |
| `IgnoreToken` | Skip to the next successful match and continue |
| `Stop` | Stop tokenization gracefully, returning lexemes collected so far |

```scala
import halotukozak.alpaca.*

case class BrainLexContext(
  squareBrackets: Int = 0,
  position: Column = Column.Start,
  line: Line = Line.Start,
) extends LexerCtx

// Custom error handler: report position and throw
given ErrorHandling[BrainLexContext] = ctx =>
  ErrorHandling.Strategy.Throw:
    RuntimeException(s"Unexpected character at line ${ctx.line}, position ${ctx.position}: '${ctx.remainingText.charAt(0)}'")
```

To silently skip unknown characters (useful for BrainFuck where non-command characters are comments):

```scala
import halotukozak.alpaca.*

// Skip unrecognized characters instead of throwing
given ErrorHandling[LexerCtx.Default] = _ =>
  ErrorHandling.Strategy.IgnoreChar
```

Note that the BrainFuck lexer from [Getting Started](getting-started.md) already handles this more explicitly with a `"." => Token.Ignored` catch-all pattern, which is the recommended approach when you want to ignore unknown input.

## Limitations

- **No skip-and-continue by default.** The default strategy aborts on the first unmatched character. Use a custom `ErrorHandling` or a catch-all pattern for resilience.
- **Guards are not supported.** Pattern guards in lexer rules are a compile-time error. Move conditions into rule bodies.
- **Error position is only reported by `LexerCtx.Default`'s handler.** For any other context, define an `ErrorHandling` that reads your `Column`/`Line` fields, as in the `BrainLexContext` example above.
