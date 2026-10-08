package com.halotukozak.alpaca.plugin.grammar

import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The id of the grammar `[sourceFile].[name]` in the export the alpaca test suite writes to
 * `/tmp/alpaca-grammar-export`. Ids end in the definition's line (`MathTest.CalcLexer@L11`), which
 * any edit above it shifts, so tests look them up by file and name instead of hardcoding them.
 */
fun exportedGrammarId(
    sourceFile: String,
    name: String,
): String {
    val idPattern = Regex("""^${Regex.escape("$sourceFile.$name")}@L\d+(?=\.)""")
    val ids =
        Path
            .of("/tmp/alpaca-grammar-export")
            .listDirectoryEntries()
            .mapNotNull { idPattern.find(it.name)?.value }
            .distinct()
    return ids.singleOrNull() ?: error("expected one exported grammar $sourceFile.$name, found $ids")
}
