# Tooling

Alpaca's macros already compute everything there is to know about your grammar at compile time — the token patterns, the productions, the resolved LR table. Two environment variables let you pull that information out, for two different audiences:

| Variable | Writes | For |
|---|---|---|
| `ALPACA_DEBUG_DIR` | The constructed parse tables, action table, and conflict-resolution table | You, to understand or debug a grammar |
| `ALPACA_GRAMMAR_EXPORT_DIR` | The grammar's *shape* (tokens, productions, table) as JSON | Tools that consume grammars, chiefly the IntelliJ plugin |

Both are read from the environment at macro-expansion time, do nothing when unset, and have no runtime cost or effect on the compiled artifact. Both work with any build tool that runs the Scala 3 compiler — the linked pages show Mill, sbt, and Scala CLI.

- **[Debug Settings](debug-settings.md)** — dump the LR automaton and conflict-resolution tables to disk during compilation, to see why a grammar has a conflict or what automaton Alpaca built.
- **[IntelliJ Plugin](ide-plugin.md)** — turn any Alpaca-defined language into a real custom language in the IDE, with syntax highlighting, real parsing, autocompletion, structure view, code folding, formatting, and more — no per-language plugin code.
