# JSON Parser

This guide builds a JSON parser that handles objects, arrays, strings, numbers, booleans, and null. It demonstrates recursive grammar rules and nested data structures.

**What you'll learn:** recursive rules, separator-delimited lists via explicit recursion, and backtick-quoted token names for punctuation.

## The Lexer

```scala sc-name:json-parser-lexer
import halotukozak.alpaca.*

val JsonLexer = lexer:
  case "\\s+" => Token.Ignored
  case "\\{" => Token["{"]
  case "\\}" => Token["}"]
  case "\\[" => Token["["]
  case "\\]" => Token["]"]
  case ":" => Token[":"]
  case "," => Token[","]
  case x @ ("false" | "true") => Token["Bool"](x.toBoolean)
  case "null" => Token["Null"]
  case x @ """[-+]?\d+(\.\d+)?""" => Token["Number"](x.toDouble)
  case x @ """"(\\.|[^"])*"""" => Token["String"](x.slice(1, x.length - 1))
```

Punctuation tokens (`{`, `}`, `[`, `]`, `:`, `,`) need backtick quoting when accessed in parser rules: ``JsonLexer.`{`(_)``.

## The Parser

JSON is recursive: a `Value` can be an `Object` or `Array`, which contain more `Value`s.

```scala sc-name:json-parser-parser sc-compile-with:json-parser-lexer
import halotukozak.alpaca.*

object JsonParser extends Parser:
  val root: Rule[Any] = rule:
    case Value(value) => value

  val Value: Rule[Any] = rule(
    { case JsonLexer.Null(_) => null },
    { case JsonLexer.Bool(b) => b.value },
    { case JsonLexer.Number(n) => n.value },
    { case JsonLexer.String(s) => s.value },
    { case Object(obj) => obj },
    { case Array(arr) => arr },
  )

  val Object: Rule[Map[String, Any]] = rule(
    { case (JsonLexer.`{`(_), JsonLexer.`}`(_)) => Map.empty[String, Any] },
    { case (JsonLexer.`{`(_), ObjectMembers(members), JsonLexer.`}`(_)) => members.toMap },
  )

  val ObjectMembers: Rule[List[(String, Any)]] = rule(
    { case ObjectMember(member) => scala.List(member) },
    { case (ObjectMembers(members), JsonLexer.`,`(_), ObjectMember(member)) => members :+ member },
  )

  val ObjectMember: Rule[(String, Any)] = rule:
    case (JsonLexer.String(s), JsonLexer.`:`(_), Value(v)) => (s.value, v)

  val Array: Rule[List[Any]] = rule(
    { case (JsonLexer.`[`(_), JsonLexer.`]`(_)) => Nil },
    { case (JsonLexer.`[`(_), ArrayElements(elems), JsonLexer.`]`(_)) => elems },
  )

  val ArrayElements: Rule[List[Any]] = rule(
    { case Value(v) => scala.List(v) },
    { case (ArrayElements(elems), JsonLexer.`,`(_), Value(v)) => elems :+ v },
  )
```

`ObjectMembers` and `ArrayElements` spell out comma-separated lists with explicit left recursion, so the whole grammar is visible. The same lists can be written in one line with `.SeparatedBy` (see [EBNF Extractors: .SeparatedBy](../extractors.md#ebnf-extractors-separatedby)) -- at the cost of the comma lexemes being interleaved into the resulting list, which you then filter out. `.List` covers unseparated sequences (like BrainFuck operations).

## Running It

```scala sc-compile-with:json-parser-parser
val input = """{"name": "Alice", "age": 30, "tags": ["a", "b"]}"""
val lexemes = JsonLexer.tokenize(input).getOrThrow
val result = JsonParser.parse(lexemes).getOrThrow
println(result)
// Map(name -> Alice, age -> 30.0, tags -> List(a, b))
```

No conflict resolution is needed -- the JSON grammar is unambiguous.

## Exercises

- Add typed results (`Rule[JsonValue]` instead of `Rule[Any]`) using a sealed enum
- Support JSON5 features: trailing commas, single-line comments, unquoted keys
