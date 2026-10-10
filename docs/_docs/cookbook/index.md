# Cookbook

Self-contained examples that put the reference pages to work. The first two build a complete language end to end — lexer, parser, and evaluation. The third is a focused recipe on top of BrainFuck>.

- **[Expression Evaluator](expression-evaluator.md)** — a math evaluator with arithmetic, exponentiation, unary minus, parentheses, constants, and functions. Focus: operator precedence and the `before`/`after` conflict-resolution DSL.
- **[JSON Parser](json-parser.md)** — objects, arrays, strings, numbers, booleans, and null. Focus: recursive rules, separator-delimited lists, backtick-quoted token names.
- **[Contextual Lexing](contextual-lexing.md)** — errors that point at their source: bracket depth and positions tracked by the lexer, reported by the parser. Focus: custom `LexerCtx`/`ParserCtx` working together.

New to Alpaca? Start with [Getting Started](../getting-started.md), which walks through the BrainFuck interpreter step by step.
