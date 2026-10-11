# Compile Errors

Alpaca checks your lexers, parsers and conflict resolutions while your code compiles, so a mistake in a definition is a compile error rather than a surprise at runtime. This page lists each error the `lexer` and `Parser` macros report, with a small example that triggers it and the fix.

Errors in a lexer are reported at the rule that causes them. Errors in a parser are reported at its `extends Parser` line, followed by `(at line N: ...)` naming the line and source of the rule or production at fault; the messages below leave that suffix out.

## Lexer

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

A pattern can also be dead because several earlier patterns cover it together, though none does alone:

```scala sc:fail
import halotukozak.alpaca.*

val Lexer = lexer:
  case "a" => Token["A"]
  case "b" => Token["B"]
  case "[ab]" => Token["AB"]  // error: covered by A and B together
```

```
Token "AB" can never match: every input it matches is also matched by "A" or "B",
which are defined earlier, so one of them always wins.
"AB" is redundant: remove it, or narrow "A" and "B" so they no longer cover it.
```

A pattern is reported only when it really can never win -- an earlier pattern matching just a *prefix* of it (`"a"` before `"ab"`) is fine. When one pattern's language contains the other's in the wrong order, e.g. `"[0-9]+(\\.[0-9]+)?"` before `"[0-9]+"`, give the later pattern inputs of its own -- e.g. `"[0-9]+\\.[0-9]+"` before `"[0-9]+"` -- or use a single pattern. See [How a Token Is Chosen](lexer.md#how-a-token-is-chosen) for which pattern wins where two can match, and [Shadowing Detection](theory/lexer-fa.md#shadowing-detection) for how the check works.

<details>
<summary>Under the hood: how the lexer is checked</summary>

The `lexer` macro parses every pattern with Alpaca's own [`regex`](https://github.com/halotukozak-com/regex) library and checks it against the ones declared before it -- each on its own, and together -- so that every pattern can produce a token.

</details>

### Invalid Regex

Patterns are parsed by Alpaca's own [`regex`](https://github.com/halotukozak-com/regex) library, not by `java.util.regex`. A malformed pattern -- unmatched parentheses, an invalid quantifier, bad character class syntax -- or a construct the library does not support produces an error naming the token and the position in the pattern:

```scala sc:fail
import halotukozak.alpaca.*

val Lexer = lexer:
  case "(?<=a)b" => Token["T"] // error
```

```
Invalid regex pattern for token "T": unsupported regex feature `lookbehind` (at position 3 in "(?<=a)b")
```

Lookbehind, backreferences like `\\1`, the word boundary `\\b`, and lazy (`*?`) and possessive (`*+`) quantifiers are rejected this way.

### Empty Matches

A pattern that can match the empty string, such as `"a*"` or `"[0-9]*"`, would let the lexer match without moving forward. It is an error at its rule, for ignored tokens too:

```scala sc:fail
import halotukozak.alpaca.*

val Lexer = lexer:
  case "a*" => Token["A"] // error
```

```
Token "A" can match the empty string ("a*"); a token must consume at least one character
```

Require at least one character, e.g. `"a+"` instead of `"a*"`.

### Duplicate Token Names

Each token name can be defined by only one rule:

```scala sc:fail
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\[" => Token["bracket"]
  case "\\]" => Token["bracket"] // error
```

```
Token name "bracket" is defined 2 times. Combine the patterns into a single case using alternatives: case "\\[" | "\\]" => ...
```

Combine the patterns as the message suggests, or give each rule its own name.

### Rule Bodies Must End With a Token

A rule body can run any code, but its last expression must be the `Token[...]` or `Token.Ignored` call itself, not a value holding one:

```scala sc:fail
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\+" =>
    val token = Token["inc"] // error
    token
```

```
A lexer rule must end with `Token["NAME"]`, `Token["NAME"](value)` or `Token.Ignored`, written directly as its last expression
```

### Invalid Token Names

A token name can't be empty or `_`:

```scala sc:fail
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case "\\+" => Token[""] // error
```

```
Invalid token name: it is empty
```

`Token["_"]` reports `Invalid token name: _`.

### Patterns and Names Must Be Literals

The macro reads patterns and token names while it compiles the lexer, so they must be string literals written in the rule. A pattern held in a `val` is not one:

```scala sc:fail
import halotukozak.alpaca.*

val Plus = "\\+"

val BrainLexer = lexer:
  case Plus => Token["inc"] // error
```

```
A lexer rule must match a regex string literal or alternatives of them, as in `case "[0-9]+"` or `case "a" | "b"`
```

The same goes for each alternative of a rule, which reports `Each alternative of a lexer rule must be a regex string literal`, and for a token name taken from anything but a string literal or the bound match (`Token[x.type]` for `case x @ "..."`):

```
A token name must be a string literal, as in `Token["NAME"]`, or the type of the bound match, as in `case x @ "regex" => Token[x.type]`
```

### Context Changes Inside a Token Value

Assign context fields in the rule body, before the `Token[...]` call, not inside the value passed to it:

```scala sc:fail
import halotukozak.alpaca.*

case class BrainLexContext(squareBrackets: Int = 0) extends LexerCtx

val BrainLexer = lexer[BrainLexContext]: // warn // warn
  case "\\[" => Token["jumpForward"]({ ctx.squareBrackets += 1; ctx.squareBrackets }) // error
```

```
Assign context fields before `Token[...]`, not inside its value
```

Move the assignment into the body: `ctx.squareBrackets += 1` on its own line, then `Token["jumpForward"](ctx.squareBrackets)`.

### Guards

Lexer rules can't have guards:

```scala sc:fail
import halotukozak.alpaca.*

case class BrainLexContext(squareBrackets: Int = 0) extends LexerCtx

val BrainLexer = lexer[BrainLexContext]:
  case "\\]" if ctx.squareBrackets > 0 => Token["jumpBack"] // error
```

```
Guards are not supported yet
```

Check the condition in the rule body instead, as the bracket-counting lexer in [Lexer Context](lexer-context.md#custom-context) does with `require`.

### `ctx` and `Token` Outside a Lexer Rule

`ctx`, `Token[...]` and `Token.Ignored` exist only inside the rules of a `lexer` block:

```scala sc:fail
import halotukozak.alpaca.*

val inc = Token["inc"] // error
```

```
`ctx` and `Token` can only be used inside a lexer rule
```

## Contexts

A lexer or parser context must be a case class whose fields all have default values, because the macro builds the initial context from those defaults (see [The LexerCtx Trait](lexer-context.md#the-lexerctx-trait) and [Parser Context](parser-context.md)). A field without one is an error:

```scala sc:fail
import halotukozak.alpaca.*

case class BrainLexContext(squareBrackets: Int) extends LexerCtx

val BrainLexer = lexer[BrainLexContext]: // error // error
  case "\\[" => Token["jumpForward"]
```

```
Field `squareBrackets` of BrainLexContext has no default value. Every field of a lexer or parser context needs one, so that the initial context can be built.
```

A context field named like a public member of a lexeme or lexer error, such as `name`, `value` or `text`, would hide it, so it is an error too (see [The LexerCtx Trait](lexer-context.md#the-lexerctx-trait)):

```scala sc:fail
import halotukozak.alpaca.*

case class BrainLexContext(name: String = "") extends LexerCtx

val BrainLexer = lexer[BrainLexContext]: // error
  case "\\[" => Token["jumpForward"]
```

```
Context field `name` clashes with `Lexeme.name`; rename it
```

A parser context that isn't a case class reports `BrainParserCtx should be a case class.` A lexer context that isn't one fails earlier, with the compiler's own `No given instance of type scala.deriving.Mirror.ProductOf[...]` error, which ends with `... is not a generic product because it is not a case class`.

## Parser

```scala sc-hidden sc-name:ce-brain-lexer
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case ">" => Token["next"]
  case "<" => Token["prev"]
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case "\\[" => Token["jumpForward"]
  case "\\]" => Token["jumpBack"]
  case count @ "[0-9]+" => Token["repeat"](count.toInt)
  case "." => Token.Ignored
  case "\n" => Token.Ignored
```

### Missing `-Yretain-trees`

The parser macro reads the source of your rules, which the compiler keeps only with the `-Yretain-trees` flag. Without it, every parser fails to compile with:

```
Cannot read the definition of rule root. Enable -Yretain-trees compiler flag
```

Add the flag to your build, as [Getting Started](getting-started.md#project-setup) shows.

### Rules Must Be `val`s Built With `rule`

A rule must be a `val` whose right-hand side is a `rule(...)` call. A `def` reports:

```scala sc-compile-with:ce-brain-lexer sc:fail
object BrainParser extends Parser: // error
  def Operation: Rule[Int] = rule:
    case BrainLexer.inc(_) => 1
  val root: Rule[Int] = rule:
    case Operation(op) => op
```

```
Rule Operation must be declared with `val`; parameterized rules aren't supported yet
```

A `val` that isn't defined with `rule(...)`, for example one that refers to another rule, reports ``Cannot read the productions of rule Operation: define it with a `rule(...)` call.``

### Production Patterns

Each production is a `{ case ... }` block with exactly one case, which matches a token or rule extractor, or a tuple of them:

```scala sc-compile-with:ce-brain-lexer sc:fail
object BrainParser extends Parser: // error
  val root: Rule[Int] = rule(
    {
      case BrainLexer.inc(_) => 1
      case BrainLexer.dec(_) => -1
    },
  )
```

```
Each production must have exactly one case. Split multiple cases into separate productions:
  rule(
    { case (a(x)) => ... },
    { case (b(y)) => ... }
  )
```

Anything else in a pattern reports one of:

```
Each element of a production's pattern must be a token or rule extractor, as in `MyLexer.NUM(n)`, `MyLexer.PLUS(_)`, `Expr(e)` or `Expr.List(es)`
A production must match a token or rule extractor, or a tuple of them, as in `case (Expr(a), MyLexer.PLUS(_), Expr(b))`
```

Productions can't have guards either (`Guards are not supported yet`): match the input in the pattern, and check anything else in the action. And a production name must be a string literal written in the rule (`"plus" { case ... }`), not a value held in a `val`:

```
A production name must be a string literal, as in `"plus" { case ... }`
```

### Duplicate Productions

Two productions with the same symbols would share one action, so writing the same production twice is an error, at the second one:

```scala sc-compile-with:ce-brain-lexer sc:fail
object BrainParser extends Parser: // error
  val root: Rule[Int] = rule(
    { case BrainLexer.inc(_) => 1 },
    { case BrainLexer.inc(_) => 2 },
  )
```

```
Production root -> inc is already defined at line 3
```

The same goes for production names: each name can be used once per parser, across all its rules, even if no resolution refers to it:

```
Production name 'op' is already used by Operation -> inc (op); give each production its own name
```

### Missing Root Rule

Every parser needs a `root` rule, its start symbol:

```scala sc-compile-with:ce-brain-lexer sc:fail
object BrainParser extends Parser: // error
  val Operation: Rule[Int] = rule:
    case BrainLexer.inc(_) => 1
```

```
No root rule defined in BrainParser. Define a root rule: val root: Rule[Any] = rule { ... }
```

### `SeparatedBy` Separators

The separator of `.SeparatedBy[Separator]` must be a token type, like `BrainLexer.next.type`, or a rule's type, like `Sep.type`:

```scala sc-compile-with:ce-brain-lexer sc:fail
object BrainParser extends Parser: // error
  val root: Rule[List[Any]] = rule:
    case Operation.SeparatedBy[Int](ops) => ops // warn // warn
  val Operation: Rule[Int] = rule:
    case BrainLexer.inc(_) => 1
```

```
SeparatedBy separator must be a Token or Rule type, but got: scala.Int
```

### `rule` and Extractors Outside a Parser

`rule`, named productions and the token and rule extractors can only be used in the body of an `object ... extends Parser`:

```scala sc-compile-with:ce-brain-lexer sc:fail
val Operation: Rule[Int] = rule:
  case BrainLexer.inc(_) => 1 // error // error // error // error
```

```
`rule`, named productions, and token and rule extractors can only be used inside a parser definition
```

### Conflicts

A grammar the LALR(1) table can't decide is reported as a shift/reduce or reduce/reduce conflict. [Reading the Error Messages](conflict-resolution.md#reading-the-error-messages) walks through one, and [Conflict Resolution](conflict-resolution.md) shows how to resolve it.

## Conflict Resolutions

```scala sc-hidden sc-name:ce-calc
import halotukozak.alpaca.*

val CalcLexer = lexer:
  case num @ "[0-9]+" => Token["NUMBER"](num.toInt)
  case "\\+" => Token["PLUS"]
  case "-" => Token["MINUS"]
  case "\\s+" => Token.Ignored
```

The examples below use a `CalcParser` whose `Expr` rule has a production named `plus`. The parser macro reports these errors at the `extends Parser` line. A cycle in the resolutions is covered in [Conflict Cycle Detection](conflict-resolution.md#conflict-cycle-detection).

### Productions That Don't Exist

`Production(symbols*)` must name the right-hand side of exactly one production of the parser:

```scala sc-compile-with:ce-calc sc:fail
import halotukozak.alpaca.Production as P

object CalcParser extends Parser: // error
  val Expr: Rule[Int] = rule(
    "plus" { case (Expr(a), CalcLexer.PLUS(_), CalcLexer.NUMBER(b)) => a + b.value },
    { case CalcLexer.NUMBER(n) => n.value },
  )
  val root = rule:
    case Expr(e) => e

given Resolutions[CalcParser.type] = resolutions(
  P(CalcParser.Expr, CalcLexer.MINUS, CalcLexer.NUMBER).before(CalcLexer.PLUS),
)
```

```
Production with RHS 'Expr MINUS NUMBER' not found
```

When several productions have that right-hand side, it reports `Production with RHS '...' is ambiguous, it matches:` followed by the candidates: name the one you mean and refer to it with `production.<name>`. A rule of another parser in `Production(...)` reports ``Rule Expr belongs to another parser, OtherParser; `Production(...)` in the resolutions of CalcParser can only refer to CalcParser's rules``, and a rule passed through a `val` alias reports ``someRule is not declared in a parser; `Production(...)` in the resolutions of CalcParser can only refer to CalcParser's rules directly, as in `Production(CalcParser.<rule>, ...)` ``.

A misspelled `production.<name>` is the compiler's own error, `value <name> is not a member of halotukozak.alpaca.ProductionSelector{...}`, which lists the names that exist. Two production names that are the same Scala name once encoded, like `"-"` and `"$minus"`, can't be told apart: ``Production names '-', '$minus' are the same Scala name, so `production.<name>` cannot tell them apart; rename all but one``.

### How Resolutions Must Be Written

The macro reads the resolutions from the source of the `given`, so the `given` must be a direct `resolutions(...)` call:

```scala sc-compile-with:ce-calc sc:fail
object CalcParser extends Parser: // error
  val Expr: Rule[Int] = rule(
    "plus" { case (Expr(a), CalcLexer.PLUS(_), CalcLexer.NUMBER(b)) => a + b.value },
    { case CalcLexer.NUMBER(n) => n.value },
  )
  val root = rule:
    case Expr(e) => e

val calcResolutions: Resolutions[CalcParser.type] = resolutions(
  production.plus.before(CalcLexer.PLUS),
)

given Resolutions[CalcParser.type] = calcResolutions
```

```
Cannot read the conflict resolutions of CalcParser.
Define them directly with a call to `resolutions`, e.g.:
  given Resolutions[CalcParser.type] = resolutions(...)
```

Likewise, each argument of `resolutions(...)` must be a single `x.before(...)` or `x.after(...)` call, not a block that computes one: ``Each conflict resolution must be a direct `x.before(...)` or `x.after(...)` call``.

Inside the parser object, a `given Resolutions` that uses `production.<name>` must be the last member, after every rule; anywhere earlier it reports `Define resolutions as the last field of the parser.` (see [Where resolutions Live](conflict-resolution.md#where-resolutions-live)).

### `production` and `before`/`after` Outside `resolutions`

`production`, `Production(...)`, `before` and `after` can only be used in the arguments of `resolutions(...)`:

```scala sc:fail
import halotukozak.alpaca.*

val plus = production.plus // error
```

```
`production`, `Production(...)`, `before` and `after` can only be used inside resolutions(...)
```
