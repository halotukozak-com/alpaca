# Tokens and Lexemes

A lexer transforms raw source text into a sequence of structured tokens — the first stage of
compilation. Before writing a lexer, it helps to understand the formal vocabulary: what a token
class is, how individual matches relate to it, and what a lexeme carries.

## Terminal Symbols

In formal grammar, a *terminal symbol* is an atomic element that cannot be broken down further.
It is the end of the line for derivation — terminals represent the actual characters or strings
that appear in source text. In a lexer, each token class acts as a terminal: it names a category
of strings, and no lexer-level expansion applies below it.

See [Context-Free Grammars](cfg.md) for how terminals fit into production rules.

## Token Classes vs Token Instances

It is useful to distinguish three levels:

**Token class** — defines a category of strings by a regular expression. For example, the
`NUMBER` class matches any string of the form `[0-9]+(\.[0-9]+)?`: the integers `"3"`, `"42"`,
and the decimals `"3.14"`, `"0.5"`.

**Token instance** — a specific string found in the input that belongs to a token class. When
the lexer scans `"3 + 4"`, it finds three token instances: the string `"3"` (a NUMBER), the
string `"+"` (a PLUS), and the string `"4"` (another NUMBER).

**Lexeme** — the full record of a token instance: the token class, the matched text, and its
position in the source. Parsing `"3 + 4"` produces three lexemes:

- `NUMBER("3", pos=0)`
- `PLUS("+", pos=2)`
- `NUMBER("4", pos=4)`

The word *lexeme* is used throughout this documentation to mean this complete record.

## Alpaca's Lexeme Type

In Alpaca, each matched token is represented as a `Lexeme[Name, Value]`. A lexeme carries:

- `name` — the token class name string, e.g., `"NUMBER"` or `"PLUS"`
- `value` — the extracted value with its Scala type, e.g., `3.14: Double` for a `Token["NUMBER"](num.toDouble)`,
  or `()` for a plain `Token["PLUS"]`
- `text` — the matched characters, e.g., `"3.14"` or `"+"`
- a snapshot of the lexer context's fields at match time, accessible as typed fields (e.g., `.column`, `.line` with
  `LexerCtx.Default`)

The tokenization output for a simple expression illustrates this:

```scala sc-hidden sc-name:tokens-brain-lexer
import halotukozak.alpaca.*

val BrainLexer = lexer:
  case ">" => Token["next"]
  case "<" => Token["prev"]
  case "\\+" => Token["inc"]
  case "-" => Token["dec"]
  case "\\." => Token["print"]
  case "," => Token["read"]
  case "\\[" => Token["jumpForward"]
  case "\\]" => Token["jumpBack"]
  case name @ "[A-Za-z]+" => Token["functionName"](name)
  case "\\(" => Token["functionOpen"]
  case "\\)" => Token["functionClose"]
  case "!" => Token["functionCall"]
  case "." => Token.Ignored
  case "\n" => Token.Ignored
```

```scala sc-compile-with:tokens-brain-lexer
import halotukozak.alpaca.*

val lexemes = BrainLexer.tokenize("foo(++)").getOrThrow
// lexemes: List[Lexeme] =
//   functionName("foo"), functionOpen, inc, inc, functionClose
//
// Each Lexeme carries:
//   .name     — token class name (e.g., "functionName")
//   .value    — extracted value  (e.g., "foo": String)
//   .column   — column at end of match
//   .line     — line number at end of match
```

Input matched as `Token.Ignored` — such as whitespace or other non-command characters — does not produce a lexeme and disappears from the stream.

## BrainLexer Token Class Table

The `BrainLexer` running example defines these token classes:

| Token Class    | Regex Pattern | Value Type | Example Match        |
|----------------|---------------|------------|----------------------|
| `next`         | `>`           | `Unit`     | `">"`                |
| `prev`         | `<`           | `Unit`     | `"<"`                |
| `inc`          | `\+`          | `Unit`     | `"+"`                |
| `dec`          | `-`           | `Unit`     | `"-"`                |
| `print`        | `\.`          | `Unit`     | `"."`                |
| `read`         | `,`           | `Unit`     | `","`                |
| `jumpForward`  | `\[`          | `Unit`     | `"["`                |
| `jumpBack`     | `\]`          | `Unit`     | `"]"`                |
| `functionName` | `[A-Za-z]+`   | `String`   | `"foo"` → `"foo"`   |
| `functionOpen` | `\(`          | `Unit`     | `"("`                |
| `functionClose`| `\)`          | `Unit`     | `")"`                |
| `functionCall` | `!`           | `Unit`     | `"!"`                |

`functionName` is the only value-bearing token: the `@` binding captures the matched text and passes it to `Token["functionName"](name)`. The other tokens use `Token["NAME"]` without a value argument — they carry `Unit`. Their presence in the stream is enough; the matched text is accessible via `lexeme.text` from the context snapshot if needed.

## Cross-links

- See [Lexer](../lexer.md) for the full `lexer` DSL reference and all token forms.
- See [The Lexer: Regex to Finite Automata](lexer-fa.md) for how regex patterns define token
  classes formally.
- Next: [The Lexer: Regex to Finite Automata](lexer-fa.md) — how these token patterns are compiled
