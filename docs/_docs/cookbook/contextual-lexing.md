# Contextual Lexing

This recipe makes BrainFuck> point at its own mistakes. The lexer tracks bracket depth and source positions, the parser keeps a registry of defined functions, and a call to an undefined function is reported with the line and column where it appears.

**What you'll learn:** how lexer context, the lexemes it produces, and parser context work together. Each piece has a reference page, linked as we go.

## The Lexer

The lexer context counts open parentheses and tracks `line` and `column` (see [Lexer Context](../lexer-context.md) for custom contexts and the built-in tracking fields):

```scala sc-name:cl-lexer
import halotukozak.alpaca.*

case class BrainLexContext(
  brackets: Int = 0,
  line: Line = Line.Start,
  column: Column = Column.Start,
) extends LexerCtx

val BrainLexer = lexer[BrainLexContext]:
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "\\(" =>
    ctx.brackets += 1
    Token["functionOpen"]
  case "\\)" =>
    require(ctx.brackets > 0, "Mismatched brackets")
    ctx.brackets -= 1
    Token["functionClose"]
  case "!" => Token["functionCall"]
  case "\\s+" => Token.Ignored
```

`brackets` changes only where a rule body assigns it. `line` and `column` advance on their own after every match, and each lexeme records where its token starts.

## The Parser

The parser context collects defined function names and the errors found so far (see [Parser Context](../parser-context.md)). The position of an error comes from the lexeme the rule binds, not from the parser context (see [Lexeme Bindings](../extractors.md#lexeme-bindings)):

```scala sc-name:cl-parser sc-compile-with:cl-lexer
import halotukozak.alpaca.*
import scala.collection.mutable

enum BrainAST:
  case Root(ops: List[BrainAST])
  case FunctionDef(name: String, ops: List[BrainAST])
  case FunctionCall(name: String)
  case Inc, Dec

case class BrainParserCtx(
  functions: mutable.Set[String] = mutable.Set.empty,
  errors: mutable.ListBuffer[String] = mutable.ListBuffer.empty,
) extends ParserCtx

object BrainParser extends Parser[BrainParserCtx]:
  val root: Rule[BrainAST] = rule:
    case Operation.List(ops) => BrainAST.Root(ops)

  val Operation: Rule[BrainAST] = rule(
    { case BrainLexer.inc(_) => BrainAST.Inc },
    { case BrainLexer.dec(_) => BrainAST.Dec },
    { case FunctionDef(fdef) => fdef },
    { case FunctionCall(call) => call },
  )

  val FunctionDef: Rule[BrainAST] = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionOpen(_),
          Operation.List(ops), BrainLexer.functionClose(_)) =>
      if !ctx.functions.add(name.value) then
        ctx.errors.addOne(s"${name.line}:${name.column}: function ${name.value} is already defined")
      BrainAST.FunctionDef(name.value, ops)

  val FunctionCall: Rule[BrainAST] = rule:
    case (BrainLexer.functionName(name), BrainLexer.functionCall(_)) =>
      if !ctx.functions.contains(name.value) then
        ctx.errors.addOne(s"${name.line}:${name.column}: function ${name.value} is not defined")
      BrainAST.FunctionCall(name.value)
```

Collecting errors instead of throwing lets one run report all of them.

## Running It

Check the final lexer context once the whole input is consumed, then read the errors from the final parser context:

```scala sc-compile-with:cl-parser
val program =
  """foo(++)
    |foo!
    |  bar!""".stripMargin

val lexed = BrainLexer.tokenize(program)
require(lexed.ctx.brackets == 0, "Mismatched brackets")

val parsed = BrainParser.parse(lexed.getOrThrow)
parsed.ctx.errors.foreach(println)
// 3:3: function bar is not defined
```

## Going Further

- Add your own tracked field, such as an indentation level: [Lexer Context](../lexer-context.md#the-post-match-update).
- Skip input no pattern matches instead of stopping: [Lexer Error Recovery](../lexer-error-recovery.md#error-handling-strategies).
- Recover from syntax errors in the parser: [Error Recovery](../parser.md#error-recovery).
- Follow the data from input to parse result: [Between Stages](../on-token-match.md#data-flow-summary).
