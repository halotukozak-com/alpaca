# Between Stages

The Alpaca lexer and parser are two independent compilation stages connected by a single data contract: the `Lexeme`.
When you call `tokenize()`, the lexer matches tokens against the input, runs its post-match update after each match, and collects the results into a `List[Lexeme]`.
When you call `parse()`, the parser consumes that list.
The post-match update is responsible for applying tracking-field and rule-body context changes and constructing each lexeme with its context snapshot (the text cursor has already advanced past the match by then).

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

The `lexer` macro derives the per-token update from the context's case fields. There is no hook to override; instead you compose behaviour from smaller pieces:

- **Tracking fragments** — a field whose type provides a `given Tracking[F]` advances automatically after every match. `Column` and `Line` are built in; you can define your own (see [Lexer Context](lexer-context.md#the-post-match-update)).
- **Rule bodies** — assignments like `ctx.squareBrackets += 1` are rewritten into a functional `copy`, so a field with no `Tracking` changes only where a rule says so.
- **Post-tokenization checks** — read the final `ctx` returned by `tokenize()` for anything that only makes sense once the whole input is consumed.

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

1. The lexer finds the longest prefix of the remaining input that any rule pattern matches; if several patterns match equally long text, the one declared first wins. If no pattern matches, the input is reported as a `LexerError` and the context's `ErrorHandling` strategy decides whether tokenizing stops or skips it (by default it stops, and `tokenize()` returns a `Result.Failure`; `getOrThrow` on it throws a `LexerException`).
2. The text cursor (`ctx.text`) advances past the matched string and the matched text is recorded in `ctx.lastRawMatched`.
3. Each tracked field's `Tracking` update runs, producing a fresh context via one functional `copy`.
4. The rule body's context changes (`ctx.field = ...`) are applied, again as a `copy`.
5. For a `DefinedToken`, a `Lexeme` is built from the token name, value, and a snapshot of the context's case fields, with `text` set to the matched string. `Token.Ignored` (and text skipped by the `SkipOne`/`SkipToNextMatch` error strategies) still run steps 2–4 but emit no `Lexeme` — they are invisible to the parser.
6. This repeats until the entire input is consumed. `tokenize()` then returns a `Result` with the final context state and the complete lexeme list (or, if some input matched no token, the `LexerError`s).
7. `parse(lexemes)` runs the parser grammar over the list.

The `Lexeme` list is immutable after `tokenize()` returns. The parser does not alter the lexeme data.

See [Lexer](lexer.md) for lexer definition, [Lexer Context](lexer-context.md) for custom contexts and tracking fragments, and [Parser](parser.md) for grammar rules.
