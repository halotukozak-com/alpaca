# IntelliJ Plugin

An Alpaca-defined language is just Scala code: there's no separate grammar file, no code generation step, nothing to point an IDE at. The Alpaca IntelliJ plugin (`ij-plugin/` in this repo) bridges that gap. It reads the grammar data your `lexer{...}`/`parser` macros already compute, exported to disk at compile time, and turns it into a real, working custom language in the IDE for any language you define, without writing a single line of plugin code yourself.

## Features

- **Syntax highlighting**: inferred from each token's regex shape (a bare word is a keyword, a pattern with `0-9`/`\d` is a number, a single punctuation character is an operator or bracket, ...).
- **A real parser**: the IDE drives your grammar's exact, already conflict-resolved LR table (the same one Alpaca's own runtime parser uses) to build an actual syntax tree, not just colored tokens. Syntax errors show up as ordinary red squiggles.
- **Structure View**: mirrors the parsed tree, one entry per grammar rule, labeled with its name and a snippet of its own text.
- **Code folding**: any rule whose text spans more than one line becomes a foldable region.
- **Autocompletion**: suggests every keyword/operator/bracket (anything with a fixed spelling) that's syntactically valid at the caret, computed by replaying the file up to the caret through the parse table.
- **Comment toggling** (`Ctrl+/`): if your grammar ignores a rule shaped like `prefix.*` (e.g. `#.*` or `//.*`), that prefix becomes the line-comment marker.
- **Reformat Code**: spaces and indents around your grammar's brackets, commas, semicolons, and dots, however they're spelled.
- **Brace matching**: the bracket pair at the caret is highlighted, with brackets recognized from the grammar.
- **Highlight occurrences** of the identifier under the caret, everywhere it appears in the file.
- **Pair-quote insertion** for grammars whose strings are delimited by `"` or `'`.
- **TODO markers** in comments feed the TODO tool window and the commit check.
- **Breadcrumbs** showing the caret's nesting path through the parsed tree.
- **Quick Documentation** (hover / `Ctrl+Q`): a token's pattern, or a nonterminal's full set of production alternatives.
- **Alpaca Grammar tool window**: browse the current file's whole grammar -- every token and every production alternative -- in one place.

None of this is per-language code: the plugin discovers grammars dynamically from what's exported, so adding a new `lexer{...}`/`parser` to your project doesn't require touching the plugin at all. It just needs Settings pointed at the right export.

<details>
<summary>Under the hood</summary>

Every feature above is generic because it only ever looks at the exported grammar: a token's regex *pattern* (for highlighting, completion, comment detection, brackets, and quotes), the productions (for Quick Documentation and the tool window), and the shape of the parsed tree itself (for the Structure View, folding, breadcrumbs, and formatting, which only care that a node is composite and where it starts/ends). Nothing about a specific grammar's meaning is hardcoded.

The parser is a hand-written shift-reduce driver that follows the exported table exactly like Alpaca's own `Parser.unsafeParse` does, except it builds `PsiBuilder` markers instead of your semantic AST. See [Conflict Resolution](conflict-resolution.md) for how that table gets its shift/reduce decisions in the first place.

</details>

## Exporting your grammar

Set the `ALPACA_GRAMMAR_EXPORT_DIR` environment variable to an absolute directory path before compiling. If it's unset (the default), nothing is written, and there is no runtime cost or effect on the compiled artifact either way.

```bash
# Mill
ALPACA_GRAMMAR_EXPORT_DIR=$(pwd)/export ./mill jvm.compile

# sbt
ALPACA_GRAMMAR_EXPORT_DIR=$(pwd)/export sbt compile

# Scala CLI
ALPACA_GRAMMAR_EXPORT_DIR=$(pwd)/export scala-cli compile .
```

The variable is read from the environment (`sys.env`) at macro-expansion time, so any tool that shells out to the Scala 3 compiler behaves the same way. If you use a persistent build daemon — Mill's, or an interactive `sbt` shell — it captured its environment when it started: set the variable before launching the daemon, or restart it (`mill --no-daemon ...`, a fresh `sbt` session) after setting it.

For every `lexer{...}`/`object MyParser extends Parser`, Alpaca writes:

| File | Contents |
|---|---|
| `<name>.tokens.json` | The lexer's token names, patterns, and `ignored` flags |
| `<name>.productions.json` | The parser's raw productions (left-hand side, right-hand side, optional name) |
| `<name>.table.json` | The parser's resolved LR table: what the IDE plugin actually drives |

Each file is a JSON envelope `{"version": ..., "context": ...}`: `context` holds the data above, and `version` is the export *format* version (bumped only when the file shape changes, not on every Alpaca release). If the plugin finds a version it doesn't understand -- typically an export written by an older or newer Alpaca -- it shows an "Alpaca grammar export format mismatch" notification instead of misreading the file; rebuilding the project or updating the plugin fixes it.

`<name>` identifies *where* the declaration lives, not just its own name: `<source-file>.<declaration-name>@L<line>`, e.g. a `val BrainLexer = lexer[...]` on line 12 of `Brainfuck.scala` exports as `Brainfuck.BrainLexer@L12`. This disambiguates two grammars that happen to share a name (a common `val Lexer = lexer[...]` reused across multiple test files, say) once they land in the same export directory.

This env var is independent from `ALPACA_DEBUG_DIR` (see [Debug Settings](debug-settings.md)): one exports grammar *shape* for tooling to consume, the other dumps the constructed tables for you to read.

## Installing the plugin

Install **Alpaca** from the JetBrains Marketplace: in your IDE, open **Settings | Plugins | Marketplace**, search for "Alpaca", and click **Install**. It targets IDE builds 2025.2 and newer.

### From source

To build the current `main` yourself:

```bash
cd ij-plugin
./gradlew buildPlugin
```

This produces a zip under `ij-plugin/build/distributions/`. Install it via **Settings | Plugins | ⚙️ | Install Plugin from Disk...** in your IDE.

To try it out without installing anything permanently, run `./gradlew runIde` instead: it launches a disposable sandbox instance with the plugin already loaded.

Maintainers: see [`ij-plugin/RELEASING.md`](https://github.com/halotukozak-com/alpaca/blob/main/ij-plugin/RELEASING.md) for the publishing workflow.

## Configuring

Open **Settings | Tools | Alpaca**:

- **Grammar export directory**: where the plugin looks for the `.tokens.json`/`.productions.json`/`.table.json` files above. Leave empty to fall back to the `ALPACA_GRAMMAR_EXPORT_DIR` environment variable.
- **Language mappings**: one row per file extension you want the IDE to recognize:

  | Column | Meaning |
  |---|---|
  | Extension | File extension to associate, without the dot (e.g. `bf`) |
  | Lexer grammar id | The exported lexer's `<file>.<name>@L<line>` id |
  | Parser grammar id (optional) | The exported parser's id; leave blank to get highlighting only, with no real parsing |

## Notes

- Block comments aren't supported: there's no regex shape as reliable as `prefix.*` to recognize a comment *pair* from.
- Completion only ever suggests literal-text terminals; a token whose pattern is a regex class (an `int` or an identifier rule, say) has no fixed spelling to offer.
- Rename, Find Usages, and Go to Declaration aren't available: Alpaca's export describes a grammar's *shape*, not symbol references, so the plugin has nothing to resolve against.
- The whole file is re-parsed on every edit. Fine for the grammars Alpaca is typically used for; not something you'd want for a multi-thousand-line file.
