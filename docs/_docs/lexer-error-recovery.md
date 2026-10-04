# Lexer Error Recovery

The Alpaca lexer provides two layers of error feedback: compile-time validation that catches many problems before your program runs, and runtime error strategies for input that does not match any pattern.

<details>
<summary>Under the hood: compile-time validation</summary>

The `lexer` macro validates token definitions at compile time. Pattern shadowing, invalid regex syntax, and unsupported guards are caught during compilation. The macro performs pairwise regex inclusion checks using Alpaca's own `regex` library (`SubsetChecker`) to ensure every pattern is reachable.

</details>

## Compile-Time Errors

### Shadowed Patterns

A shadowing error occurs when one pattern can never match because an earlier pattern always matches first. If every string that pattern B can match is also matched by pattern A, and A appears before B, then B is unreachable:

```scala sc:fail
import halotukozak.alpaca.*

// This does NOT compile -- WORD is shadowed by ID
val Lexer = lexer:
  case "[A-Za-z]+" => Token["ID"]            // general: any letters
  case "[A-Za-z][A-Za-z0-9]*" => Token["WORD"]  // error: shadowed by ID
```

The compiler reports:

```
Token "WORD" can never match: every input it matches is already matched by "ID",
which is tried first because it's defined earlier.
Consider reordering the cases so "WORD" comes first, or merging them into one case with
alternatives, e.g.: case x @ ("ID" | "WORD") => Token[x]
```

The fix: move the more specific pattern before the more general one, or remove the duplicate.

### Invalid Regex

Malformed Java regex patterns -- unmatched parentheses, invalid quantifiers, bad character class syntax -- produce a compile-time error. The message identifies the pattern and includes the underlying regex engine error.

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

Patterns are tried in the order they appear. The first match wins. The general rule: **more specific patterns before more general ones.**

In the BrainFuck lexer, this matters for the print command vs the catch-all:

```scala
import halotukozak.alpaca.*

// RIGHT -- literal dot before catch-all dot
val BrainLexer = lexer:
  case "\\." => Token["print"]   // specific: literal dot (BF print)
  case "." => Token.Ignored      // general: any character (catch-all)
```

If you reverse the order, `"."` shadows `"\\."` and you get a shadowing compile error.

The same applies to keywords vs identifiers. Function names in the extended BrainFuck lexer must come after command tokens:

```scala
import halotukozak.alpaca.*

// RIGHT -- single-char commands before the general name pattern
val BrainLexer = lexer:
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  // ... other single-char commands ...
  case name @ "[A-Za-z]+" => Token["functionName"](name)  // general: after commands
  case "." => Token.Ignored
```

## Runtime Error Handling

When `tokenize()` hits a character that matches no pattern, the lexer consults the `ErrorHandling` strategy for the context type.

### Default Behavior

The default strategy throws a `RuntimeException`:

```
Unexpected character at line 1, position 5: '@'
```

With `LexerCtx.Default`, the error message includes line and position. With `LexerCtx.Empty` or a custom context without `Column`/`Line` fields, it shows only the character.

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
- **Error position is only available with tracking fields.** Without a `Column` or `Line` field (both present in `LexerCtx.Default`), the error message shows only the character, not its location.
