<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Alpaca IntelliJ Plugin Changelog

## [Unreleased]

## [0.2.1] - 2026-10-06

### Fixed

- Grammars exported by Alpaca 0.3.2 and later failed to load: the plugin rejected the `start`/`end` offsets that exports now carry in `source`.

## [0.2.0] - 2026-09-09

### Added

- Initial release: IDE support for languages defined with the [Alpaca](https://github.com/halotukozak-com/alpaca) lexer/parser library, driven entirely by the grammar data Alpaca exports at compile time (`ALPACA_GRAMMAR_EXPORT_DIR`).
- Syntax highlighting inferred from each token's regex shape.
- A real PSI parser that drives the exported, conflict-resolved LR table; syntax errors surface as ordinary error annotations.
- Structure View mirroring the parsed tree, one entry per grammar rule.
- Code folding for any rule whose text spans more than one line.
- Grammar-driven autocompletion of the fixed-spelling terminals valid at the caret.
- Line comment toggling (`Ctrl+/`) for grammars that ignore a `prefix.*`-shaped rule.
- Settings panel (**Settings | Tools | Alpaca**) for the grammar export directory and per-extension language mappings.

[Unreleased]: https://github.com/halotukozak-com/alpaca/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/halotukozak-com/alpaca/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/halotukozak-com/alpaca/commits/v0.2.0
