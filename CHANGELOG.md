# Changelog

User-facing changes to the Alpaca library. The IntelliJ plugin has its own [changelog](ij-plugin/CHANGELOG.md).

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- A token pattern that can match the empty string is a compile error at its rule.
- `MyLexer.ParserError`: a `ParserError` typed with the lexer's context fields, next to `MyLexer.Lexeme` and `MyLexer.LexerError`.

### Changed

- **Breaking:** `ParserError.expected` is a `List[String | ParserError.EndOfInput]`; the end of the input is `ParserError.EndOfInput` instead of the string `"$"`.
- `ParserError.expected` lists only the terminals that can actually continue the input. LALR(1) state merging used to add terminals that would fail right after a reduction.
- The `Line` and `Column` trackers count every `\n` in a match, so `\r\n` and newlines inside a longer match (such as a block comment) advance the line too. Previously only a match of exactly `"\n"` did.
- Selecting an unknown token name on a `Lexer` throws `NoSuchElementException`.
- Assigning a context field inside a token value (`Token["X"](...)`) is reported as a DSL error at the assignment, not as the compiler's `Reassignment to val`.
- **Breaking:** internal types are no longer accessible: `LazyReader`'s constructor (use `LazyReader.from`), the `LexerException`/`ParserException` constructors, `Terminal`, `NonTerminal`, `Source`.
- **Breaking:** a production written twice is a compile error at the second one (`Production root -> NUM is already defined at line 4`). The later action used to replace the earlier one silently.
- Every duplicate token name, shadowed token, duplicate production and duplicate production name is reported at its own rule, in source order. Only one duplicate token name and the first shadowed token used to be reported.
- Parser macro errors, which the compiler shows at `extends Parser`, end with the line and source of the production they are about: `(at line 11: case ...)`.
- `.List`, `.Option` and `.SeparatedBy` non-terminals are named after their symbols instead of their source offset. The grammar export format version is now 3 (read by the IntelliJ plugin).
- The parameter of `before`/`after` is named `others` (was `second`), and public type parameters have descriptive names (`Result[+Ctx, +Value, +Err]`, `Rule[Value]`, `ErrorHandling[-Ctx, -Err]`, ...). Only named arguments are affected.

### Removed

- **Breaking:** `ParserError.copy`, `ParserError.unapply` and `LexerError.unapply`. Read the fields instead: `case ParserError(unexpected, expected, last)` → `error.unexpected`, `error.expected`, `error.last`.

### Fixed

- Lookaheads are computed past nullable symbols. Grammars where an empty-deriving symbol follows another could reject valid input.
- Distinct productions with the same hash code no longer collapse into one while the parse table is built.
- The same `.List`, `.Option` or `.SeparatedBy` used in several productions is one shared non-terminal. Each use used to get its own copy, which was an unresolvable reduce/reduce conflict.
- The parser's `SkipToNextMatch` skips to the next lexeme listed in `ParserError.expected`. It could stop at a lexeme that only triggered spurious reductions and a second error.

## [1.0.0-RC2] - 2026-10-08

### Added

- `Lexer` (the type returned by `lexer`, formerly `Tokenization`), `Lexeme`, `Production` and `ProductionSelector` are public, nameable types.
- `LexerCtx.peek(n)` returns up to `n` characters of the remaining input.
- `LexerError` and `ParserError` carry the lexer context's fields as typed members (e.g. `error.line`). Declare a lexer handler as `ErrorHandling[MyCtx, LexerError.Of[MyCtx]]` to get typed access.
- `ParserError.last`: the last lexeme before the end of the input, when the error is at the end.

### Changed

- **Breaking:** `LexerCtx.Default.position` is renamed to `column`.
- **Breaking:** a lexeme records the value of each tracked field (`Line`, `Column` and any other field with a `Tracking`) from *before* its match, so `line`/`column` point at the token's start. Other fields are still snapshotted after the rule body. `Column` counts Unicode code points.
- **Breaking:** `LexerError` and `ParserError` no longer have `line`/`column` fields, and their messages contain no positions. Read positions from the context fields instead (`error.line`, or `error.unexpected`'s fields for a parser error).
- **Breaking:** `ParserError.unexpected` is an `Option[Lexeme]`; `None` means the input ended too early.
- **Breaking:** `LexerCtx.remainingText` is replaced by `peek(n)`, and `lastRawMatched` is internal again.
- **Breaking:** the DSL is scoped to where it belongs. `ctx` and `Token` work only inside a lexer rule; `rule`, named productions and token/rule extractors only inside a parser; `production`, `Production(...)`, `before` and `after` only inside `resolutions(...)`. Misuse is a compile error.
- **Breaking:** assigning a lexer context field outside a lexer rule is a compile error instead of a runtime `UnsupportedOperationException`.
- **Breaking:** rules declared with `def` are rejected; declare them as `val`.
- Duplicate production names, production names that are not string literals, and `Production(...)` with rules of another parser are compile errors.
- Tokens may be named `$`, `ε` or `#`; the parser's own end-of-input and empty symbols use internal names. Empty and `_` token names are rejected. The grammar export format version is now 2 (read by the IntelliJ plugin).
- `.List` and `.SeparatedBy` build their lists in linear time instead of quadratic.

### Fixed

- `LazyReader` on files with multi-byte characters (it threw `IndexOutOfBoundsException`), and `tokenize` no longer buffers a whole `LazyReader` input in memory.
- Parsers defined outside the `halotukozak.alpaca` package no longer warn that their `given Tables` is inaccessible (an error under `-Werror`).
- Unsupported lexer rules and productions are reported as errors instead of crashing the macro, and a rejected lexer case no longer loses the lexer's type.

## [1.0.0-RC1] - 2026-10-06

### Added

- `Result[Ctx, A, E]`, returned by both `tokenize` and `parse`: `Result.Success(ctx, value)` or `Result.Failure(ctx, recovered, errors)`, with `toOption`, `toEither`, and `getOrThrow` (throws `LexerException`/`ParserException`).
- `LexerError` and `ParserError`: plain data describing input that was not accepted, with a readable `message`.
- Parser error recovery. `ErrorHandling` is shared by the lexer and the parser: `ErrorHandling[Ctx, LexerError]` / `ErrorHandling[Ctx, ParserError]`, with strategies `SkipOne`, `SkipToNextMatch` and `Stop` (the default for both). Every error is reported, and `recovered` holds the value when the run reached the end after skipping.
- A lexer context field without a default value is a compile error naming the field (it used to crash at the first `tokenize`).
- A parser rule that is not a `rule(...)` call is reported instead of crashing the macro.

### Changed

- **Breaking:** `tokenize` returns a `Result` instead of a `(ctx, lexemes)` tuple, and `parse` returns a `Result` instead of `(ctx, result | Null)`; invalid input no longer throws or yields `null`.

  ```scala
  // before
  val (_, lexemes) = MyLexer.tokenize(input)
  val (_, value) = MyParser.parse(lexemes)
  // after
  val value = MyParser.parse(MyLexer.tokenize(input).getOrThrow).getOrThrow
  ```

- **Breaking:** `ErrorHandling` moved to `halotukozak.alpaca.ErrorHandling` and takes the error as a second type parameter: `(ctx, error) => Strategy`. `IgnoreChar` became `SkipOne`, `IgnoreToken` became `SkipToNextMatch`, and `Throw` was removed: use `Stop` and `getOrThrow`. The lexer default changed from throwing to `Stop`.
- **Breaking:** `Token["NAME"]` is typed with the `Unit` value its lexemes actually carry (it claimed `String`). Use `lexeme.text` for the matched text.
- Lexer shadowing is checked under the real longest-match semantics. A pattern is rejected only when every string it matches is matched by earlier patterns, so reachable orderings like `"a"` before `"ab"` are accepted. Shadowing and duplicate-name errors suggest fixes that work.
- Grammar symbols in conflict and cycle errors are shown as you wrote them, and non-printable characters in token names, patterns and input are escaped in all messages.
- Consumers no longer need `-experimental`; `-Yretain-trees` is still required.

## [0.3.2] - 2026-10-04

### Changed

- Macro errors point at the offending lexer case, production or resolution instead of the whole block. Grammar conflicts are reported at the conflicting production, and resolution cycles at the rule that closes the cycle.
- A `Production(...)` reference that matches several productions (e.g. `Integer -> Num` and `Float -> Num`) is an error listing the candidates; it used to pick one silently.
- A `given Resolutions` the macro cannot read (an alias, a block) is reported at its right-hand side instead of being ignored, which showed up as an unexplained conflict. A given with a `using` clause is now read.

### Fixed

- `Option`, `List` and `SeparatedBy` on tokens produced a grammar that rejected any input containing the token.
- A parser local to a method crashed the compiler under `-Wsafe-init`.
- Alternatives within one case where one is a prefix of another (e.g. `">=" | ">"`) were wrongly rejected as redundant.

## [0.3.1] - 2026-09-09

### Added

- Grammar export: with `ALPACA_GRAMMAR_EXPORT_DIR` set, compilation writes each lexer's tokens and each parser's productions and LALR(1) table as versioned JSON, with source locations. The IntelliJ plugin reads this export.

### Fixed

- The implicit `withDefault`/`Empty` instances that `lexer` needs are accessible outside the library's package (Scala 3.10 would stop finding them).

## [0.3.0] - 2026-09-02

### Added

- Scala.js support, alongside the JVM and Scala Native.
- `Tracking[F]`: a typeclass for context field types that update after every match. `Line` and `Column` are the built-in instances.

### Changed

- **Breaking:** lexer contexts are immutable case classes. Declare fields as plain parameters with defaults. `ctx.count += 1` inside a rule still works: the macro rewrites it into a `copy`.
- **Breaking:** `OnTokenMatch`, `LineTracking` and `PositionTracking` are removed. Give a field a type with a `given Tracking` instead; `LexerCtx.Default` is now `Default(position: Column, line: Line)`.

  ```scala
  // before
  case class Ctx(var line: Int = 1) extends LexerCtx, LineTracking
  // after
  case class Ctx(line: Line = Line.Start) extends LexerCtx
  ```

- **Breaking:** parse tables are built with LALR(1) instead of canonical LR(1). Tables are smaller; a grammar that is LR(1) but not LALR(1) now reports a reduce/reduce conflict.

## [0.2.0] - 2026-08-28

### Added

- Scala Native support.
- `ErrorHandling`, `LazyReader`, `LineTracking`, `OnTokenMatch` and `PositionTracking` are exported from the main package.

### Changed

- **Breaking:** the package is `halotukozak.alpaca` (was `alpaca`) and the Maven group is `com.halotukozak` (was `io.github.halotukozak`). Requires Scala 3.9.0.
- **Breaking:** conflict resolutions are a type class declared after the parser object instead of an overridden `val resolutions` inside it:

  ```scala
  // before
  object CalcParser extends Parser:
    ...
    override val resolutions = Set(production.plus.before(Lexer.PLUS))
  // after
  object CalcParser extends Parser:
    ...
  given Resolutions[CalcParser.type] = resolutions(
    production.plus.before(Lexer.PLUS),
  )
  ```

- **Breaking:** the lexer runs on a DFA from `com.halotukozak::regex` instead of `java.util.regex`. The token chosen is the longest match, with declaration order breaking ties; before, the first matching case won. Invalid patterns fail the build.
- **Breaking:** debug output is enabled by the `ALPACA_DEBUG_DIR` environment variable. The `-Xmacro-settings` options (debug directory, timeout, verbose names, log levels) are gone.

## [0.1.4] - 2026-08-26

### Added

- `LexerCtx.remainingText`: a read-only view of the text still to tokenize, for building messages in a custom `ErrorHandling`.

## [0.1.3] - 2026-08-10

### Added

- `Rule.SeparatedBy[Separator]`: zero or more occurrences delimited by a token or rule, with the separators' values interleaved in the result list (typed via `SepValue`).
- `lexeme.text` is a regular field, and `LexerCtx.lastRawMatched` is public again.

### Changed

- Faster tokenizing and parsing: array-backed parse tables, an array-based parse stack and fewer allocations per lexeme. Lexemes no longer compare by content.

### Fixed

- LR item sets whose items had colliding hash codes lost items, which produced incorrect parse tables without any error.

## [0.1.2] - 2026-04-16

### Added

- With debug output enabled, the conflict-resolution graph is also written as a Mermaid diagram (`conflictResolutions.mmd`).
- Documentation was rewritten: Getting Started, a cookbook, and compiler-theory pages.

### Changed

- **Breaking:** `BetweenStages` is renamed to `OnTokenMatch`.
- **Breaking:** `LexerCtx.text`, `lastLexeme` and `lastRawMatched` are internal. `LexerCtx` extends `Product` itself, so tracking traits no longer need a `Product` self-type.

## [0.1.1] - 2026-03-28

### Changed

- `BetweenStages` is public again, so custom contexts can provide their own instance.
- `Token` and `IgnoredToken` are opaque types.

## [0.1.0] - 2026-03-17

### Changed

- **Breaking:** internal types are hidden: the conflict exceptions, `ShadowException`, `ValidName`'s companion, the `Production` companion and `DebugSettings`.
- Clearer compile errors: an empty lexer, duplicate token names (with a suggested alternative-pattern fix), invalid regexes (with an escaping hint), a production with several cases, a missing `root` rule, and overlapping alternatives within one lexer case.
- A parse error names the unexpected symbol and the symbols expected instead.

### Fixed

- Rule actions with multi-line bodies or returning lambdas.

## [0.0.7] - 2026-03-16

### Fixed

- The published jar contained the debug settings of the machine that built it, so user builds tried to write debug files to CI paths.

## [0.0.6] - 2026-03-16

### Added

- `ErrorHandling[Ctx]`: chooses what the lexer does on unrecognized input: `Throw`, `IgnoreChar`, `IgnoreToken` or `Stop`. The default for `LexerCtx.Default` reports the line and position.

### Changed

- **Breaking:** custom lexer contexts no longer declare `text`; it comes from `LexerCtx`.
- `tokenize` no longer needs an implicit `Empty`.
- Faster tokenizing and parsing.
- Conflict messages suggest `before`/`after` instead of the nonexistent `alwaysBefore`/`alwaysAfter`.

### Fixed

- Production names that are not valid identifiers can be referenced in resolutions.

## [0.0.5] - 2026-02-23

### Added

- Reference documentation for the lexer, lexer context, error recovery, `BetweenStages`, the parser, parser context, conflict resolution and extractors.

## [0.0.4] - 2026-01-11

### Changed

- **Breaking:** debug settings are compiler options instead of a `given DebugSettings`: `-Xmacro-settings:debugDirectory=...,compilationTimeout=...,enableVerboseNames=...`, plus per-level log outputs (`trace=stdout`, `debug=file`, ...).
- `LexerDefinition`, `Token` and `IgnoredToken` are public types in the `alpaca` package.

## [0.0.3] - 2025-12-30

### Changed

- **Breaking:** productions are named with a string prefix and referenced through `production` instead of `@name` and `Production.ofName`:

  ```scala
  // before
  { case (Expr(a), L.PLUS(_), Expr(b)) => a + b }: @name("plus")
  Production.ofName("plus").before(L.PLUS)
  // after
  "plus" { case (Expr(a), L.PLUS(_), Expr(b)) => a + b }
  production.plus.before(L.PLUS)
  ```

- `DebugSettings` gains `verboseNames`, and `lexer` no longer takes it.
- A missing `-Yretain-trees` flag is reported with a clear error.

## [0.0.2] - 2025-12-23

### Added

- Lexemes expose a snapshot of the lexer context and the matched `text` as typed fields (`lexeme.line`, `lexeme.text`).
- Cycles in conflict resolutions are reported as `InconsistentConflictResolution`.
- Macro expansion has a timeout (`DebugSettings.timeout`).

### Changed

- **Breaking:** `Lexem` is renamed to `Lexeme`, and `LexerCtx.lastLexem` to `lastLexeme`.
- **Breaking:** `tokenize` returns a named tuple `(ctx, lexemes)` instead of a list.
- **Breaking:** `parse` infers the result type from `root` and takes no type parameter. `root` and `resolutions` are `val`s.
- **Breaking:** lexer contexts must be case classes (`LexerCtx` requires `Product`).
- **Breaking:** `DebugSettings` is a plain case class (`enabled`, `directory`, `timeout`) instead of being parameterized by singleton types.

## [0.0.1] - 2025-11-26

First release.

- `lexer` macro: map regex patterns to `Token["NAME"]`, `Token["NAME"](value)` or `Token.Ignored`. Patterns are checked at compile time, including for shadowing.
- Lexer contexts: `LexerCtx.Default` tracks position and line; custom contexts extend `LexerCtx` and are available as `ctx` in rules.
- `Parser` with `rule(...)` productions written as pattern matches over tokens and rules, plus `.Option` and `.List` extractors. LR(1) tables are built at compile time, and grammar conflicts are compile errors.
- Conflict resolution with `before`/`after` on tokens, `Production(...)` and named productions (`@name`, `Production.ofName`).
- Parser contexts (`ParserCtx`) and `parse` returning `(ctx, result | Null)`.
- `DebugSettings` to dump the generated tables during compilation.

[Unreleased]: https://github.com/halotukozak-com/alpaca/compare/v1.0.0-RC2...HEAD
[1.0.0-RC2]: https://github.com/halotukozak-com/alpaca/compare/v1.0.0-RC1...v1.0.0-RC2
[1.0.0-RC1]: https://github.com/halotukozak-com/alpaca/compare/v0.3.2...v1.0.0-RC1
[0.3.2]: https://github.com/halotukozak-com/alpaca/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/halotukozak-com/alpaca/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/halotukozak-com/alpaca/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/halotukozak-com/alpaca/compare/v0.1.4...v0.2.0
[0.1.4]: https://github.com/halotukozak-com/alpaca/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/halotukozak-com/alpaca/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/halotukozak-com/alpaca/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/halotukozak-com/alpaca/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/halotukozak-com/alpaca/compare/v0.0.7...v0.1.0
[0.0.7]: https://github.com/halotukozak-com/alpaca/compare/v0.0.6...v0.0.7
[0.0.6]: https://github.com/halotukozak-com/alpaca/compare/v0.0.5...v0.0.6
[0.0.5]: https://github.com/halotukozak-com/alpaca/compare/v0.0.4...v0.0.5
[0.0.4]: https://github.com/halotukozak-com/alpaca/compare/v0.0.3...v0.0.4
[0.0.3]: https://github.com/halotukozak-com/alpaca/compare/v0.0.2...v0.0.3
[0.0.2]: https://github.com/halotukozak-com/alpaca/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/halotukozak-com/alpaca/releases/tag/v0.0.1
