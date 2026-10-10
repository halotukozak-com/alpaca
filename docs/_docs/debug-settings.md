# Debug Settings

When you define a `Parser`, Alpaca can dump the generated grammar tables to disk during compilation -- useful for understanding why a grammar has a conflict, or just for seeing the LR automaton Alpaca built from your rules.

## Enabling debug output

Set the `ALPACA_DEBUG_DIR` environment variable to an absolute directory path before compiling. If it's unset (the default), nothing is written.

```bash
# Mill
ALPACA_DEBUG_DIR=$(pwd)/debug ./mill jvm.compile

# sbt
ALPACA_DEBUG_DIR=$(pwd)/debug sbt compile

# Scala CLI
ALPACA_DEBUG_DIR=$(pwd)/debug scala-cli compile .
```

The directory is created automatically if it doesn't exist.

<details>
<summary>Under the hood</summary>

The directory is read via `sys.env` at macro-expansion time, inside the `parser` macro's implementation. Mill, sbt, and Scala CLI all run compilation in a JVM that inherits the environment of the process that launched it -- but if you're using a persistent build-tool daemon (e.g. Mill's, or an interactive `sbt` shell), the daemon captured its environment when *it* started, not when you last ran a build command. If setting the variable doesn't seem to take effect, restart the daemon (`mill --no-daemon ...` for one-off invocations, or kill the running daemon process) and retry.

</details>

## What gets written

For every `object MyParser extends Parser` in your code, Alpaca writes one file per debug artifact into a `MyParser/` subdirectory of `ALPACA_DEBUG_DIR`:

| File | Contents |
|---|---|
| `productions.dbg` | The grammar's productions, one per line |
| `actionTable.dbg.csv` | Each production with its semantic action (your `case` body), as CSV |
| `parseTable.dbg.csv` | The LALR(1) parse table (state × symbol → shift/reduce), as CSV |
| `conflictResolutions.dbg` | Your `resolutions(...)` conflict-resolution table |
| `conflictResolutions.mmd` | The same conflict-resolution table as a [Mermaid](https://mermaid.js.org/) diagram -- paste it into a Mermaid live editor or a Markdown file that renders Mermaid to visualize precedence/associativity relationships |

Debug output is parser-specific; lexer definitions don't currently write anything here.

## Notes

- Files are overwritten (not appended) on each compilation of the same parser.
- Writing happens unconditionally whenever `ALPACA_DEBUG_DIR` is set -- there's no separate opt-in per parser.
- This only affects compile-time behavior; it has no runtime cost or effect on the compiled artifact.
