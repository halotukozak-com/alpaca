# Parser Context

Parser context lets you carry mutable state through parsing reductions. Stateless parsers use `ParserCtx.Empty` by default; custom contexts carry domain-specific state like symbol tables, function registries, or error accumulators.

<details>
<summary>Under the hood: context threading</summary>

When you define `Parser[Ctx]`, `Ctx` must extend `ParserCtx`, and the compiler derives an `Empty[Ctx]` instance from its constructor defaults -- which only works for a case class whose fields all have default values. At runtime, `parse()` calls that `Empty[Ctx]` once to create the initial context, and the same object is passed to every rule reduction in that call.

</details>

## ParserCtx.Empty (Default)

When you extend `Parser` without a type parameter, the parser uses `ParserCtx.Empty`. No context definition is needed:

```scala sc-name:brain-defs sc-hidden
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "\\(" => Token["functionOpen"]
  case "\\)" => Token["functionClose"]
  case "!" => Token["functionCall"]
  case "\\s+" => Token.Ignored

enum BrainAST:
  case Root(ops: List[BrainAST])
  case FunctionDef(name: String, ops: List[BrainAST])
  case FunctionCall(name: String)
  case Inc, Dec
```

```scala sc-compile-with:brain-defs
import halotukozak.alpaca.*

object BrainParser extends Parser:    // uses ParserCtx.Empty
  val root: Rule[BrainAST] = rule:
    case Operation.List(stmts) => BrainAST.Root(stmts)

  val Operation: Rule[BrainAST] = rule(
    { case BrainLexer.inc(_) => BrainAST.Inc },
    { case BrainLexer.dec(_) => BrainAST.Dec },
    // ...
  )
```

## Custom Parser Context

The BrainFuck> extension adds function definitions and calls. To track which functions have been defined (so we can reject calls to undefined functions), we use a custom parser context:

```scala
import halotukozak.alpaca.*
import scala.collection.mutable

case class BrainParserCtx(
  functions: mutable.Set[String] = mutable.Set.empty,
) extends ParserCtx
```

Three rules apply:

1. **Must be a `case class`** -- the initial context is built by the derived `Empty[Ctx]`, which only exists for case classes (otherwise: `... should be a case class.`).
2. **All fields must have default values** -- `Empty[Ctx]` constructs the initial context from constructor defaults.
3. **Mutable collections are `val`; other mutable fields are `var`** -- mutate the collection contents, not the reference.

## Accessing Context in Rule Bodies

The `ctx` identifier is available inside every `rule { case ... }` body, typed as your specific `ParserCtx` subtype:

```scala sc-name:brain-parser-ctx-example sc-compile-with:brain-defs
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
    { case BrainLexer.inc(_) => BrainAST.Inc },
    { case BrainLexer.dec(_) => BrainAST.Dec },
    { case FunctionDef(fdef) => fdef },
    { case FunctionCall(call) => call },
    // ... other operations
  )
```

`FunctionDef` adds the function name to `ctx.functions`. `FunctionCall` checks that the name exists. Both see the same context object.

## Shared State Across Reductions

`ctx` is one object shared across all rule executions during a single `parse()` call. Mutations made in one rule body are visible to all subsequent reductions:

```scala sc-compile-with:brain-parser-ctx-example
// Parsing "foo(+++)foo!":
val lexemes = BrainLexer.tokenize("foo(+++)foo!").getOrThrow
val finalCtx = BrainParser.parse(lexemes).ctx
// 1. FunctionDef reduced "foo(+++)": ctx.functions.add("foo")
// 2. FunctionCall reduced "foo!", observing the mutation from step 1:
finalCtx.functions.contains("foo")  // true
```

The initial context is created once per `parse()` call. There is no per-rule copy -- mutations accumulate.

## Positional Info from Lexemes, Not ctx

`ParserCtx` and `LexerCtx` are independent: the parser context has no `text`, `column`, or `line`. Positions come from the lexemes a rule binds -- `name.column` and `name.line` after `BrainLexer.functionName(name)`, when the lexer context tracks them (see [Lexeme Bindings](extractors.md#lexeme-bindings)).

See [Parser](parser.md) for grammar rules and EBNF operators.
