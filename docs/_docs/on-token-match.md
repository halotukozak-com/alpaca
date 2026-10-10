# Between Stages

The Alpaca lexer and parser are two independent compilation stages connected by a single data contract: the `Lexeme`.
When you call `tokenize()`, the lexer matches tokens against the input, runs its post-match update after each match, and collects the results into a `List[Lexeme]`.
When you call `parse()`, the parser consumes that list.

Most programs need nothing more than this:

```scala sc-name:otm-brainfuck
import halotukozak.alpaca.*

case class BrainLexContext(
  squareBrackets: Int = 0,
) extends LexerCtx

val BrainLexer = lexer[BrainLexContext]:
  case ">" => Token["next"]
  case "<" => Token["prev"]
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case "\\." => Token["print"]
  case "\\[" =>
    ctx.squareBrackets += 1
    Token["jumpForward"]
  case "\\]" =>
    require(ctx.squareBrackets > 0, "Mismatched brackets")
    ctx.squareBrackets -= 1
    Token["jumpBack"]

enum BrainAST:
  case Root(ops: List[BrainAST])
  case While(ops: List[BrainAST])
  case Next, Prev, Inc, Dec, Print

object BrainParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)

  val While: Rule[BrainAST] = rule:
    case (BrainLexer.jumpForward(_), Operation.List(stmts), BrainLexer.jumpBack(_)) =>
      BrainAST.While(stmts)

  val Operation: Rule[BrainAST] = rule(
    { case BrainLexer.next(_) => BrainAST.Next },
    { case BrainLexer.prev(_) => BrainAST.Prev },
    { case BrainLexer.inc(_) => BrainAST.Inc },
    { case BrainLexer.dec(_) => BrainAST.Dec },
    { case BrainLexer.print(_) => BrainAST.Print },
    { case While(whl) => whl },
  )
```

```scala sc-compile-with:otm-brainfuck
val lexed = BrainLexer.tokenize("[>+<-]")
val lexemes = lexed.getOrThrow
val ast = BrainParser.parse(lexemes).getOrThrow
```

This page explains what is inside those lexemes, how the pipeline advances the context, and how the data flows between stages.

## Connecting Lexer Output to Parser Input

The `tokenize()` method returns a `Result` holding the final context (`ctx`) and the lexemes (`getOrThrow`):

```scala sc-compile-with:otm-brainfuck
val lexed = BrainLexer.tokenize("++[>+<-].")
val lexemes = lexed.getOrThrow

// lexed.ctx holds the final lexer context state
// lexemes holds the matched tokens (Token.Ignored entries are excluded)

val ast = BrainParser.parse(lexemes).getOrThrow
```

The end of the list is the end of the input. You don't add an end-of-input marker yourself.

The final context (the result's `ctx`) is useful for post-tokenization checks. For example, the BrainFuck lexer tracks bracket depth — after tokenization, you can verify all brackets are balanced:

```scala sc-compile-with:otm-brainfuck
val lexed = BrainLexer.tokenize("[>+<-]")
val lexemes = lexed.getOrThrow
require(lexed.ctx.squareBrackets == 0, "Mismatched brackets")
val ast = BrainParser.parse(lexemes).getOrThrow
```

## The Post-Match Update

After every match, the lexer advances the tracked fields (such as `line` and `column`), applies the rule body's context changes, and records the lexeme. [The Post-Match Update](lexer-context.md#the-post-match-update) in Lexer Context explains the order and how to track fields of your own. There is no hook to override: anything that only makes sense once the whole input is consumed belongs in a check on the final `ctx`, as above.

For per-token side effects that live outside the context entirely — writing to an external log, emitting metrics — put the effect in the rule body itself. It runs once per match, in match order.

```scala sc-compile-with:otm-brainfuck
val LoggingLexer = lexer[BrainLexContext]:
  case "\\[" =>
    ctx.squareBrackets += 1
    println(s"open at depth ${ctx.squareBrackets}")
    Token["jumpForward"]
  case "\\]" =>
    ctx.squareBrackets -= 1
    Token["jumpBack"]
```

## Data Flow Summary

Each call to `tokenize()` follows this sequence:

1. The lexer takes the longest match at the current position (see [How a Token Is Chosen](lexer.md#how-a-token-is-chosen)). Input that no pattern matches becomes a `LexerError`, and the context's `ErrorHandling` strategy decides whether tokenizing stops or skips it (see [Error Recovery](lexer-error-recovery.md#runtime-error-handling)).
2. The post-match update runs. For a named token it produces a `Lexeme` with the token name, value, matched `text` and a snapshot of the context's fields. `Token.Ignored` and skipped input update the context but produce no lexeme, so the parser never sees them.
3. This repeats until the entire input is consumed. `tokenize()` then returns a `Result` with the final context and the lexeme list (or, if some input matched no token, the `LexerError`s).
4. `parse(lexemes)` runs the parser grammar over the list.

The `Lexeme` list is immutable after `tokenize()` returns. The parser does not alter the lexeme data.

See [Lexer](lexer.md) for lexer definition, [Lexer Context](lexer-context.md) for custom contexts and tracking fragments, and [Parser](parser.md) for grammar rules.
