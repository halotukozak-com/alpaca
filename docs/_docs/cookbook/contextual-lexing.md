# Contextual Lexing

This guide covers stateful tokenization: tracking nesting depth, maintaining counters, passing information from the lexer to the parser, and handling errors gracefully.

**What you'll learn:** custom `LexerCtx`, `ParserCtx`, tracking fragments, `ErrorHandling` strategies, and how lexer context flows into parser rules.

## Tracking State During Lexing

The BrainFuck> lexer tracks bracket depth to catch mismatched brackets at lex time:

```scala sc-name:BrainLexer
import halotukozak.alpaca.*

case class BrainLexContext(
  brackets: Int = 0,
  squareBrackets: Int = 0,
) extends LexerCtx

val BrainLexer = lexer[BrainLexContext]:
  case "\\[" =>
    ctx.squareBrackets += 1
    Token["jumpForward"]
  case "\\]" =>
    require(ctx.squareBrackets > 0, "Mismatched brackets")
    ctx.squareBrackets -= 1
    Token["jumpBack"]
  case "\\(" =>
    ctx.brackets += 1
    Token["functionOpen"]
  case "\\)" =>
    require(ctx.brackets > 0, "Mismatched brackets")
    ctx.brackets -= 1
    Token["functionClose"]
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "!" => Token["functionCall"]
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case ">" => Token["next"]
  case "<" => Token["prev"]
  case "\\." => Token["print"]
  case "," => Token["read"]
  case "." => Token.Ignored
  case "\n" => Token.Ignored
```

After tokenization, check the final context:

```scala sc-compile-with:BrainLexer
val lexed = BrainLexer.tokenize("foo(+++)foo!")
val lexemes = lexed.getOrThrow
require(lexed.ctx.squareBrackets == 0 && lexed.ctx.brackets == 0, "Mismatched brackets")
```

## Accessing Lexer Context in the Parser

Every `Lexeme` carries a snapshot of the lexer context at match time. Inside parser rules, use the binding to access positional info:

```scala sc-hidden sc-name:ctx-brainast sc-compile-with:BrainLexer
enum BrainAST:
  case Root(ops: List[BrainAST])
  case FunctionDef(name: String, ops: List[BrainAST])
  case FunctionCall(name: String)
```

```scala sc-compile-with:ctx-brainast
import halotukozak.alpaca.*

object BrainParser extends Parser:
  val root: Rule[BrainAST] = rule:
    case FunctionCall(fc) => fc

  val FunctionCall: Rule[BrainAST] = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionCall(_)) =>
      // name.value: String -- the function name
      // name.position: Int -- 1-based column within the current line (if the context has a Column field)
      // name.line: Int -- line number (if the context has a Line field)
      BrainAST.FunctionCall(name.value)
```

To get position and line numbers, add `Column` and `Line` fields to your context:

```scala
import halotukozak.alpaca.*

case class BrainLexContext(
  brackets: Int = 0,
  squareBrackets: Int = 0,
  position: Column = Column.Start,
  line: Line = Line.Start,
) extends LexerCtx
```

## Parser-Level Context

`ParserCtx` is for state that evolves during parsing -- symbol tables, function registries, type environments. The BrainFuck> parser uses it to track defined functions:

```scala sc-compile-with:ctx-brainast
import halotukozak.alpaca.*
import scala.collection.mutable

case class BrainParserCtx(
  functions: mutable.Set[String] = mutable.Set.empty,
) extends ParserCtx
object BrainParser extends Parser[BrainParserCtx]:
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)

  val FunctionDef: Rule[BrainAST] = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionOpen(_),
          Operation.List(ops), BrainLexer.functionClose(_)) =>
      require(ctx.functions.add(name.value), s"Function ${name.value} is already defined")
      BrainAST.FunctionDef(name.value, ops)

  val FunctionCall: Rule[BrainAST] = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionCall(_)) =>
      require(ctx.functions.contains(name.value), s"Function ${name.value} is not defined")
      BrainAST.FunctionCall(name.value)

  val Operation: Rule[BrainAST] = rule(
    { case FunctionDef(fdef) => fdef },
    { case FunctionCall(call) => call },
    // ... other alternatives
  )
```

`ctx` is shared across all reductions in a single `parse()` call. A function defined in `FunctionDef` is immediately visible in `FunctionCall`.

## Error Handling Strategies

By default, the lexer stops at unmatched input and `tokenize()` returns a `Result.Failure` listing it as a `LexerError`. You can customize this with an `ErrorHandling` instance:

```scala sc-compile-with:BrainLexer
// skip unrecognized characters (each is still reported as a LexerError)
given ErrorHandling[BrainLexContext, LexerError] = (_, _) => ErrorHandling.Strategy.SkipOne
```

`SkipToNextMatch` skips the whole unmatched run at once, and `Stop` is the default; see [Error Handling Strategies](../lexer-error-recovery.md#error-handling-strategies) for all three.

An alternative to custom `ErrorHandling` is a catch-all pattern at the end of your lexer:

```scala
import halotukozak.alpaca.*

val LenientLexer = lexer:
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case x @ "." =>
    println(s"Unexpected character: $x")
    Token.Ignored   // skip and continue
```

This is simpler and often sufficient. The BrainFuck lexer uses this approach -- `"." => Token.Ignored` catches all non-command characters.

See [Between Stages](../on-token-match.md#data-flow-summary) for the full sequence from input to parse result.
