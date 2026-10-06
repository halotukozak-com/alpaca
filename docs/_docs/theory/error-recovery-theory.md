# Error Recovery Theory

When a lexer or parser encounters invalid input, it must decide what to do. A naïve approach — stop immediately — is fine for batch compilation but frustrating for interactive tools and IDEs. This page covers the theory behind error recovery and how Alpaca currently handles it.

## Types of Errors

**Lexical errors** — the lexer encounters a character that matches no token pattern. Example: an unexpected character in a strict lexer with no catch-all rule.

**Syntactic errors** — the parser encounters a token sequence that matches no grammar rule. Example: an expression grammar receiving `1 + * 2`.

**Semantic errors** — the input is syntactically valid but violates a semantic rule. Example: calling an undefined function in BrainFuck> (`bar!` without a prior `bar(...)` definition).

Note: with the standard BrainFuck lexer used elsewhere in these docs, non-command characters are typically ignored via `case "." => Token.Ignored`, and an unmatched `]` is rejected during lexing if bracket tracking is enabled. The examples above are generic illustrations, not statements about that specific lexer configuration.

Each level has different recovery strategies.

## Lexical Error Recovery

### Strategy: Fail Fast (Default)

The simplest approach: stop at the first unmatched character and report it. This is Alpaca's default behavior (`ErrorHandling.Strategy.Stop`): `tokenize` returns a `Result.Failure` with a `LexerError` such as

```
Unexpected character '@' at line 1, column 5
```

### Strategy: Skip and Continue

Skip the unmatched character and resume tokenization from the next position. Alpaca supports this via `ErrorHandling.Strategy.SkipOne` (or `SkipToNextMatch`, which skips the whole unmatched run). The skipped character never reaches the parser, but it is not lost silently: it is reported as a `LexerError`, and the lexemes collected around it are the failure's `recovered` value.

### Strategy: Catch-All Token

Add a low-priority pattern that matches any single character. The BrainFuck lexer uses this approach: `case "." => Token.Ignored` catches everything that isn't a BrainFuck command.

Alternatively, emit an `ERROR` token and let the parser decide what to do:

```scala
import halotukozak.alpaca.*

val ErrorTokenLexer = lexer:
  case "[a-zA-Z]+" => Token["ID"]
  case "." => Token["ERROR"]
```

## Syntactic Error Recovery

Syntactic error recovery is more complex. The parser has a state stack and must somehow get back to a valid state to continue parsing.

### Panic Mode

The most common strategy. When the parser encounters an error:

1. Pop states from the stack until a state is found that has a valid action for a designated "synchronization" token (e.g., `;`, `}`, EOF)
2. Discard input tokens until the synchronization token is found
3. Resume parsing from the synchronized state

Panic mode is simple and reliable but can skip large chunks of input.

### Phrase-Level Recovery

More targeted than panic mode. The parser recognizes common error patterns and inserts or deletes specific tokens:

- Missing semicolon → insert a synthetic `;`
- Extra closing brace → delete it and continue
- Missing operand → insert a synthetic error operand

This produces better error messages but requires hand-written recovery rules for each error pattern.

### Error Productions

Add explicit grammar rules that match erroneous input:

```
Stmt → error ;
```

The `error` pseudo-terminal matches any sequence of tokens until a recovery point (the `;`). This approach is used by yacc/bison and gives the grammar author control over recovery behavior.

## What Alpaca Currently Supports

### Lexer

Alpaca's lexer stops at the first unmatched character by default (`ErrorHandling.Strategy.Stop`), or, with another `ErrorHandling` strategy, skips the character (`SkipOne`) or the whole unmatched run (`SkipToNextMatch`) and goes on. Either way each unmatched piece of input is reported as a `LexerError` (see [Error Recovery](../lexer-error-recovery.md#error-handling-strategies)).

### Parser

Alpaca's parser has basic error recovery:

- On a parse table miss (no action for the current state and token), the parser records a `ParserError` carrying the unexpected lexeme and the token names that were expected, with a message like `Unexpected PLUS "+" at line 1, column 5. Expected one of: NUMBER`, and `parse()` returns a `Result.Failure` listing the errors
- The context's `ErrorHandling` (the same type the lexer uses) decides what happens next: `Stop` (the default) ends parsing there, `SkipOne` skips the unexpected lexeme, and `SkipToNextMatch` skips ahead to the next lexeme the parser can accept -- a simple form of panic mode that fixes extra tokens but not missing ones (see [Parser](../parser.md#error-recovery))
- No phrase-level recovery or error productions

### Semantic

Semantic error handling is up to your code. The BrainFuck interpreter uses `require()` in rule bodies to validate constraints:

```scala sc-hidden sc-name:ert-semantic-setup
import halotukozak.alpaca.*
import scala.collection.mutable

val BrainLexer = lexer:
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "!" => Token["functionCall"]
  case "\\s+" => Token.Ignored

enum BrainAST:
  case FunctionCall(name: String)

case class BrainParserCtx(
  functions: mutable.Set[String] = mutable.Set.empty,
) extends ParserCtx
```

```scala sc-compile-with:ert-semantic-setup
object BrainParser extends Parser[BrainParserCtx]:
  val root: Rule[BrainAST] = rule:
    case FunctionCall(fc) => fc

  val FunctionCall: Rule[BrainAST] = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionCall(_)) =>
      require(ctx.functions.contains(name.value), s"Function ${name.value} is not defined")
      BrainAST.FunctionCall(name.value)
```

`require` throws an `IllegalArgumentException`, and an exception thrown in a rule body is not turned into a `ParserError`: it propagates out of `parse()`, and the `Result` is never returned. To report semantic errors together with syntactic ones, accumulate them in the parser context instead of throwing, and read them from the result's `ctx`.

## Cross-links

- See [Error Recovery](../lexer-error-recovery.md) for the lexer error handling API.
- See [Parser](../parser.md) for the `parse()` return type and what happens on invalid input.
- See [Parser Context](../parser-context.md) for accumulating semantic errors in a custom context.
