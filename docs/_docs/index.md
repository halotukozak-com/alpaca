# Alpaca

A type-safe lexer and parser library for Scala 3, featuring compile-time validation and a pattern-matching DSL.

## Features

- **Type-safe lexer and parser** — catch errors at compile time with Scala 3's type system
- **Pattern-matching DSL** — define lexers and parsers using intuitive `case` syntax
- **Compile-time validation** — regex patterns and grammar rules are checked during compilation
- **Macro-based code generation** — Scala 3 macros generate efficient tokenizers and parse tables
- **Context-aware** — lexical and parsing contexts with type-safe state management
- **LALR(1) parsing** — automatic parse table generation with conflict detection
- **Cross-platform** — runs on the JVM, Scala.js, and Scala Native

## Installation

### Mill

Add Alpaca as a dependency in your `build.mill`:

```mill
//| mill-version: 1.1.10
//| mill-jvm-version: 21

import mill._
import mill.scalalib._

object myproject extends ScalaModule {
  def scalaVersion = "3.9.0"

  def scalacOptions = Seq("-Yretain-trees")

  def mvnDeps = Seq(
    mvn"com.halotukozak::alpaca::1.0.0-RC2"
  )
}
```

### SBT

Add Alpaca to your `build.sbt`:

```sbt
libraryDependencies += "com.halotukozak" %% "alpaca" % "1.0.0-RC2"
```

Make sure you're using Scala 3.9.0 or later and enable the required compiler flag (Alpaca 0.3.2 and earlier also need `-experimental`):

```sbt
scalaVersion := "3.9.0"
scalacOptions ++= Seq("-Yretain-trees")
```

### Scala CLI

Use Alpaca directly in your Scala CLI scripts:

```scala
//> using scala "3.9.0"
//> using dep "com.halotukozak::alpaca:1.0.0-RC2"
//> using options "-Yretain-trees"

import halotukozak.alpaca.*

// Your code here
```

## Quick Start

### Creating a Lexer

Define a lexer using pattern matching with regex patterns:

```scala sc-name:index-quickstart-lexer
import halotukozak.alpaca.*

val MyLexer = lexer:
  case num @ "[0-9]+" => Token["NUM"](num.toDouble)
  case "\\+" => Token["PLUS"]
  case "-" => Token["MINUS"]
  case "\\*" => Token["STAR"]
  case "/" => Token["SLASH"]
  case "\\(" => Token["LP"]
  case "\\)" => Token["RP"]
  case "\\s+" => Token.Ignored
```

### Creating a Parser

Define a parser by extending the `Parser` class and defining grammar rules:

```scala sc-name:index-quickstart-parser sc-compile-with:index-quickstart-lexer
import halotukozak.alpaca.*

object MyParser extends Parser:
  val root: Rule[Double] = rule { case Expr(e) => e }

  val Expr: Rule[Double] = rule(
    { case (Expr(l), MyLexer.PLUS(_), Term(r)) => l + r },
    { case (Expr(l), MyLexer.MINUS(_), Term(r)) => l - r },
    { case Term(t) => t }
  )

  val Term: Rule[Double] = rule(
    { case (Term(l), MyLexer.STAR(_), Factor(r)) => l * r },
    { case (Term(l), MyLexer.SLASH(_), Factor(r)) => l / r },
    { case Factor(f) => f }
  )

  val Factor: Rule[Double] = rule(
    { case MyLexer.NUM(n) => n.value },
    { case (MyLexer.LP(_), Expr(e), MyLexer.RP(_)) => e }
  )
```

### Parsing Input

```scala sc-compile-with:index-quickstart-parser
import halotukozak.alpaca.*

val input = "2 + 3 * 4"
val lexemes = MyLexer.tokenize(input).getOrThrow
val result = MyParser.parse(lexemes).getOrThrow
println(result) // 14.0
```

## What's Next

- **[Getting Started](getting-started.md)** — build a BrainFuck interpreter step by step
- **[Lexer](lexer.md)** — the full lexer DSL reference
- **[Parser](parser.md)** — grammar rules, EBNF operators, conflict resolution
- **[Theory](theory/index.md)** — formal foundations: finite automata, LR parsing, parse tables

## Benchmarks

Runtime benchmarks are **not** run automatically in CI on push or pull requests. They can be triggered manually:

- **GitHub Actions** — go to *Actions > Runtime Benchmark > Run workflow* and select the branch.
- **Locally** — run all benchmarks (JMH + Python) from the repository root:

  ```bash
  ./mill benchmarks.runAll
  ```

  Or run individual JMH suites directly:

  ```bash
  ./mill benchmarks.alpaca.runJmh
  ./mill benchmarks.fastparse.runJmh
  ```

  Results are written to `benchmarks/outputs/`.

## Building from Source

### Prerequisites

- JDK 21 or later
- Mill 1.1.10 or later

### Build Commands

The build defines separate `jvm`, `js`, and `native` modules; commands below target `jvm` (swap in
`js`/`native` to build for those platforms):

```bash
# Compile the project
./mill jvm.compile

# Run tests
./mill jvm.test

# Generate documentation
./mill jvm.docJar

# Run test coverage
./mill jvm.scoverage.htmlReport
```

## Thesis

This project was developed as a Bachelor's Thesis. The full text is available in
the [thesis.pdf](https://github.com/halotukozak-com/alpaca/blob/main/thesis.pdf) file. The LaTeX source files are on
the `thesis` [branch](https://github.com/halotukozak-com/alpaca/tree/thesis). The thesis is written in Polish
and does not represent the current state of the project.

## Contributing

Contributions are welcome. Please feel free to submit a Pull Request.

### `internal` is part of the binary contract

The `halotukozak.alpaca.internal` package is not user API, but it is part of the binary contract. The lexer and parser
macros expand into code that lives in users' binaries and calls `internal` symbols directly:

- the `Lexer` constructor (the class every generated lexer extends), `DefinedToken`/`IgnoredToken`,
  `TokenInfo` and `Printable.apply`;
- `Production`, `NEL` and the grammar symbols, `ParseAction`, the `ParseTable` encoding, `ActionTable`,
  `RevertedArray`, the `ParserExtractors` actions and the `Parser` constructor;
- every `@publicInBinary` member.

A MiMa or TASTy-MiMa report on an `internal` class is therefore a real break for already-compiled users, not a false
positive. Don't filter it out. Changing these symbols incompatibly needs a major version.

## Authors

Created by [halotukozak](https://github.com/halotukozak) and [Corvette653](https://github.com/Corvette653)

---

Made with ❤️ and coffee
