# Lexer Error Recovery

When the input contains text that matches no pattern, the lexer reports it as a `LexerError` and an error-handling strategy decides whether to stop or skip it and go on. This page covers those runtime errors. Mistakes in the lexer definition itself, such as a pattern that can never match or an invalid regex, are compile errors, listed in [Compile Errors](compile-errors.md#lexer).

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

val Lexer = lexer:
  case "[a-z]+" => Token["WORD"]
  case "\\s+" => Token.Ignored

Lexer.tokenize("abc @def") match
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

val Lexer = lexer:
  case "[a-z]+" => Token["WORD"]
  case "\\s+" => Token.Ignored

Lexer.tokenize("abc @def") match
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

val Lexer = lexer:
  case "/\\*([^*]|\\*+[^*/])*\\*+/" => Token.Ignored
  case "[a-z]+" => Token["WORD"]
  case "\\s+" => Token.Ignored

Lexer.tokenize("a @ b /* c") match
  case Result.Failure(_, recovered, errors) =>
    println(recovered)             // None: it stopped at the comment
    println(errors.map(_.message)) // List(Unexpected character '@', Unexpected character '/')
  case Result.Success(_, _) => ()
```

Note that the BrainFuck lexer from [Getting Started](getting-started.md) handles unknown characters with a `"." => Token.Ignored` catch-all pattern instead. That is the recommended approach when unknown input is not an error at all, as BrainFuck comments are: a catch-all produces no `LexerError`.
