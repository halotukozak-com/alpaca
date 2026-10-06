# Cookbook

Complete, self-contained examples that put the reference pages to work. Each recipe builds one working language end to end — lexer, parser, and evaluation — and calls out the Alpaca features it leans on.

- **[Expression Evaluator](expression-evaluator.md)** — a math evaluator with arithmetic, exponentiation, unary minus, parentheses, constants, and functions. Focus: operator precedence and the `before`/`after` conflict-resolution DSL.
- **[JSON Parser](json-parser.md)** — objects, arrays, strings, numbers, booleans, and null. Focus: recursive rules, separator-delimited lists, backtick-quoted token names.
- **[Contextual Lexing](contextual-lexing.md)** — stateful tokenization: nesting depth, counters, lexer-to-parser hand-off, graceful errors. Focus: custom `LexerCtx`/`ParserCtx`, tracking fragments, `ErrorHandling`.

New to Alpaca? Start with [Getting Started](../getting-started.md), which walks through the BrainFuck interpreter step by step.
