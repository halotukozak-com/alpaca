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

When `tokenize()` hits input that matches no pattern, it records a `LexError` and consults the `ErrorHandling` strategy for the context type to decide how to go on. Either way, `tokenize()` does not throw: it returns a `Result.Failure` listing the errors.

### Default Behavior

By default the lexer stops at the first unmatched character. The `LexError` names it, and its `message` gives the line and column when the context has `line` and `position` fields (as `LexerCtx.Default` does):

```
Unexpected character '@' at line 1, column 5
```

`getOrThrow` throws the errors as a `LexerException`; match on the result to handle them without an exception:

```scala
import halotukozak.alpaca.*

val Lexer = lexer:
  case "[a-z]+" => Token["WORD"]
  case "\\s+" => Token.Ignored

Lexer.tokenize("abc @def") match
  case Result.Success(_, lexemes) => println(lexemes.size)
  case Result.Failure(_, _, errors) =>
    errors.foreach(error => println(error.message)) // Unexpected character '@' at line 1, column 5
```

### Error Handling Strategies

You can provide a custom `ErrorHandling` instance for your context type. Every strategy records the unmatched input as a `LexError`; they differ in how tokenizing goes on:

| Strategy | Behavior |
|----------|----------|
| `Stop` (default) | Stop at the unmatched character; the failure has no `recovered` lexemes |
| `IgnoreChar` | Skip the single unmatched character and continue |
| `IgnoreToken` | Skip to the next successful match and continue; one `LexError` covers the whole skipped run |

With `IgnoreChar` or `IgnoreToken` the lexer reaches the end of the input, so the `Result.Failure` also carries the lexemes it collected in `recovered`:

```scala
import halotukozak.alpaca.*

// Skip unrecognized characters, but keep a record of them
given ErrorHandling[LexerCtx.Default] = _ => ErrorHandling.Strategy.IgnoreChar

val Lexer = lexer:
  case "[a-z]+" => Token["WORD"]
  case "\\s+" => Token.Ignored

Lexer.tokenize("abc @def") match
  case Result.Failure(_, recovered, errors) =>
    println(recovered.map(_.map(_.name))) // Some(List(WORD, WORD))
    println(errors.map(_.message))        // List(Unexpected character '@' at line 1, column 5)
  case Result.Success(_, _) => ()
```

The strategy receives the context, so it can choose per character, e.g. from `ctx.remainingText.charAt(0)`.

Note that the BrainFuck lexer from [Getting Started](getting-started.md) handles unknown characters with a `"." => Token.Ignored` catch-all pattern instead. That is the recommended approach when unknown input is not an error at all, as BrainFuck comments are: a catch-all produces no `LexError`.

## Limitations

- **No skip-and-continue by default.** The default strategy stops at the first unmatched character. Use a custom `ErrorHandling` or a catch-all pattern for resilience.
- **Guards are not supported.** Pattern guards in lexer rules are a compile-time error. Move conditions into rule bodies.
- **Error positions come from fields named `line` and `position`.** A context that tracks them under other names gets `LexError`s without a line or column.
