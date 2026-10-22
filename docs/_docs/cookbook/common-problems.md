# Common Problems

The problems people hit most often when writing an Alpaca lexer or parser. Each one starts with a piece of the message (or the behaviour) you see, so you can search the page for it, then says why it happens and shows a fix. The linked pages explain each topic in depth.

## Lexer

### "can never match"

**Why:** at each position the lexer takes the longest match, and on a tie the pattern declared first wins. A keyword declared after a general identifier pattern always loses the tie, so the compiler rejects it.

**Fix:** declare the keyword first. Longest match still lexes `iffy` as one identifier.

```scala
import halotukozak.alpaca.*

val MyLexer = lexer:
  case "if" => Token["IF"]
  case id @ "[a-z]+" => Token["ID"](id)
  case "\\s+" => Token.Ignored
```

See [Shadowed Patterns](../lexer-error-recovery.md#shadowed-patterns).

### "can match the empty string"

**Why:** a pattern like `"a*"` could match without consuming input, so the lexer would never move on.

**Fix:** require at least one character.

```scala
import halotukozak.alpaca.*

val MyLexer = lexer:
  case "a+" => Token["A"]
```

See [Empty Matches](../lexer-error-recovery.md#empty-matches).

### "Invalid regex pattern" ... "unsupported regex feature"

**Why:** patterns are parsed by Alpaca's own regex library, which doesn't support some Java constructs, such as lookbehind, backreferences, and lazy (`*?`) or possessive (`*+`) quantifiers.

**Fix:** rewrite the pattern without them. A block comment is usually written with a lazy `".*?"`. Spell out what may appear inside it instead:

```scala
import halotukozak.alpaca.*

val MyLexer = lexer:
  case "/\\*([^*]|\\*+[^*/])*\\*+/" => Token.Ignored // instead of "/\\*.*?\\*/"
  case "[a-z]+" => Token["WORD"]
  case "\\s+" => Token.Ignored
```

See [Invalid Regex](../lexer-error-recovery.md#invalid-regex).

### "is defined 2 times"

**Why:** each token name can be defined by only one rule.

**Fix:** combine the patterns into one rule with alternatives, or give each rule its own name.

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\[" | "\\]" => Token["bracket"]
```

The parser has the same rule: a production written twice is "already defined", and a production name can be used once per parser.

### "Guards are not supported yet"

**Why:** neither lexer rules nor parser productions can have an `if` guard.

**Fix:** check the condition in the body instead.

```scala
import halotukozak.alpaca.*

case class BrainLexContext(squareBrackets: Int = 0) extends LexerCtx

val BrainLexer = lexer[BrainLexContext]:
  case "\\[" =>
    ctx.squareBrackets += 1
    Token["jumpForward"]
  case "\\]" =>
    require(ctx.squareBrackets > 0, "Mismatched brackets")
    ctx.squareBrackets -= 1
    Token["jumpBack"]
```

See [Guards Not Supported](../lexer-error-recovery.md#guards-not-supported).

### "can only be used inside a lexer rule"

**Why:** `Token[...]` and `Token.Ignored` exist only in the body of a `case` in a `lexer` block, and `ctx` only there or in a handler built with `ErrorHandling.forLexer` or `ErrorHandling.forParser` (see [Changing the Context from a Handler](../lexer-error-recovery.md#changing-the-context-from-a-handler)). To keep a token around, keep its lexer and refer to it as `MyLexer.NAME`.

**Fix:** use them inside a rule.

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\+" => Token["inc"]

val inc = BrainLexer.inc
```

The parser's equivalent, "can only be used inside a parser definition", means `rule` or an extractor was used outside an `object ... extends Parser`.

### A pattern declared first doesn't win

**Why:** the lexer takes the **longest** match, not the first pattern that matches. Declaration order only decides between matches of the same length. So `"<"` declared before `"<="` still lexes `<=` as one token.

```scala
import halotukozak.alpaca.*

val MyLexer = lexer:
  case "<" => Token["LT"]
  case "<=" => Token["LE"]
  case "\\s+" => Token.Ignored

val names = MyLexer.tokenize("< <=").getOrThrow.map(_.name) // List(LT, LE)
```

See [How a Token Is Chosen](../lexer.md#how-a-token-is-chosen).

### `tokenize` throws instead of returning a `Result.Failure`

**Why:** only input that matches no pattern becomes a `LexerError`. An exception thrown in a rule body, such as a failed `require`, propagates out of `tokenize`.

**Fix:** catch it, or record the problem in the context and check it after tokenizing:

```scala
import halotukozak.alpaca.*

case class BrainLexContext(squareBrackets: Int = 0, strayBrackets: Int = 0) extends LexerCtx

val BrainLexer = lexer[BrainLexContext]:
  case "\\[" =>
    ctx.squareBrackets += 1
    Token["jumpForward"]
  case "\\]" =>
    if ctx.squareBrackets > 0 then ctx.squareBrackets -= 1
    else ctx.strayBrackets += 1
    Token["jumpBack"]

val stray = BrainLexer.tokenize("[]]").ctx.strayBrackets // 1
```

See [Runtime Error Handling](../lexer-error-recovery.md#runtime-error-handling).

## Parser

### "Enable -Yretain-trees compiler flag"

**Why:** the parser macro reads the source of your rules, which the compiler keeps only with `-Yretain-trees`.

**Fix:** add the flag to your build, e.g. in Mill `def scalacOptions = Seq("-Yretain-trees")`. See [Installation](../index.md#installation) for sbt and Scala CLI.

### "must be declared with `val`" or "define it with a `rule(...)` call"

**Why:** the parser macro reads each rule from a `val` whose right-hand side is a `rule(...)` call. A `def`, or a `val` that only refers to another rule, can't be read.

**Fix:** declare every rule as a `val` built with `rule`.

```scala
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]

object BrainParser extends Parser:
  val Operation: Rule[Int] = rule(
    { case BrainLexer.inc(_) => 1 },
    { case BrainLexer.dec(_) => -1 },
  )
  val root: Rule[Int] = rule:
    case Operation(op) => op
```

See [Parser](../parser.md).

### "Shift" ... "vs Reduce"

**Why:** the grammar is ambiguous for an LR(1) parser: after `1 + 2`, on another `+` it can't tell whether to reduce `1 + 2` first or shift the `+`.

**Fix:** name the production and say which wins in a `given Resolutions`:

```scala
import halotukozak.alpaca.*

val CalcLexer = lexer:
  case num @ "[0-9]+" => Token["NUMBER"](num.toInt)
  case "\\+" => Token["PLUS"]
  case "\\s+" => Token.Ignored

object CalcParser extends Parser:
  val Expr: Rule[Int] = rule(
    "plus" { case (Expr(a), CalcLexer.PLUS(_), Expr(b)) => a + b },
    { case CalcLexer.NUMBER(n) => n.value },
  )
  val root: Rule[Int] = rule:
    case Expr(e) => e

given Resolutions[CalcParser.type] = resolutions(
  production.plus.before(CalcLexer.PLUS), // reduce 1 + 2 first: + is left-associative
)
```

See [Conflict Resolution](../conflict-resolution.md).
