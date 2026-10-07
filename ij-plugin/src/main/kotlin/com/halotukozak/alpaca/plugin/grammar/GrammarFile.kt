package com.halotukozak.alpaca.plugin.grammar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * The export-format version this plugin build understands. Bumped only when the *shape* of an
 * exported `.tokens.json`/`.productions.json`/`.table.json` file, or the meaning of its contents, changes -- kept independently in
 * sync with the alpaca library's own `JsonExport.ExportFormatVersion` constant, since the two ship
 * on separate release schedules.
 */
const val CURRENT_EXPORT_FORMAT_VERSION: Int = 2

/** The envelope every export file is wrapped in: `{"version": ..., "context": ...}`. `version`
 *  defaults to 0 so a file with no `version` key at all (written before this envelope existed)
 *  decodes as version 0 rather than failing outright. */
@Serializable
private data class ExportEnvelope<T>(
    val version: Int = 0,
    val context: T,
)

/** Just the `version` key, decoded leniently (unknown keys, i.e. `context`, ignored) so the found
 *  version number is available even when `context`'s own shape can't be decoded as expected. */
@Serializable
private data class VersionOnly(
    val version: Int = 0,
)

private val LENIENT_JSON = Json { ignoreUnknownKeys = true }

/** The result of reading one versioned export file: either its (compatible-version) payload, or
 *  the version actually found, when it doesn't match [CURRENT_EXPORT_FORMAT_VERSION]. */
sealed interface VersionedExport<out T> {
    data class Compatible<T>(
        val value: T,
    ) : VersionedExport<T>

    data class Incompatible(
        val foundVersion: Int,
    ) : VersionedExport<Nothing>
}

/** Decodes [text] as a [CURRENT_EXPORT_FORMAT_VERSION]-shaped [ExportEnvelope]'s `context`, or
 *  reports whatever version it actually found -- 0 for a pre-envelope file (a bare JSON array/
 *  object with no `version` key at all), since that shape doesn't decode as an envelope either. */
private inline fun <reified T> readVersioned(text: String): VersionedExport<T> {
    val foundVersion = runCatching { LENIENT_JSON.decodeFromString<VersionOnly>(text).version }.getOrDefault(0)
    if (foundVersion != CURRENT_EXPORT_FORMAT_VERSION) return VersionedExport.Incompatible(foundVersion)
    return VersionedExport.Compatible(Json.decodeFromString<ExportEnvelope<T>>(text).context)
}

/** The location of a lexer/parser rule as written in the grammar's own source, exported nested
 *  under a `"source"` key (see `Source` in the alpaca library). Productions synthesized from EBNF
 *  sugar (`List`/`Option`/`SeparatedBy`) point at the pattern they come from; absent/`null` only in
 *  exports from library versions that left them without a source. `start`/`end` are the character
 *  offsets of the declaration in [file]; absent in exports written before the library recorded them. */
@Serializable
data class SourceLocation
    // Keeps the `(line, file)` constructor in the bytecode: callers compiled before `start`/`end`
    // existed (including ones restored from the Gradle build cache) still link.
    @JvmOverloads
    constructor(
        val line: Int,
        val file: String,
        val start: Int? = null,
        val end: Int? = null,
    )

/**
 * One token rule as exported by Alpaca's `lexer{...}` macro (see
 * `ALPACA_GRAMMAR_EXPORT_DIR` in the alpaca library): a name, the regex
 * pattern that matches it, and whether matches are dropped from the lexeme
 * stream.
 */
@Serializable
data class TokenSpec(
    val name: String,
    val pattern: String,
    val ignored: Boolean,
    val source: SourceLocation? = null,
)

/** Reads a `<lexer>.tokens.json` file written by Alpaca's compile-time grammar export. */
object LexerGrammarFile {
    const val SUFFIX = ".tokens.json"

    fun read(path: Path): VersionedExport<List<TokenSpec>> = readVersioned(Files.readString(path))
}

/** A single lexer's exported grammar, identified by its export file name (sans the `.tokens.json` suffix). */
data class LexerGrammar(
    val id: String,
    val tokens: List<TokenSpec>,
)

/** One grammar symbol referenced from a production's right-hand side. */
@Serializable
data class SymbolSpec(
    val kind: String,
    val name: String,
)

/**
 * One production rule as exported by Alpaca's parser `Tables[Ctx]` macro: its
 * left-hand side nonterminal, right-hand side symbols (empty for an epsilon
 * production), and the optional name it was defined with.
 */
@Serializable
data class ProductionSpec(
    val lhs: String,
    val rhs: List<SymbolSpec>,
    val name: String?,
    val source: SourceLocation? = null,
)

/** Reads a `<parser>.productions.json` file written by Alpaca's compile-time grammar export. */
object ParserGrammarFile {
    const val SUFFIX = ".productions.json"

    fun read(path: Path): VersionedExport<List<ProductionSpec>> = readVersioned(Files.readString(path))
}

/**
 * A shift-or-reduce action for one (state, symbol) cell of an exported LALR(1) parse table.
 * The `@SerialName`s match the `"type"` discriminator Alpaca's export writes.
 */
@Serializable
sealed interface ActionSpec {
    @Serializable
    @SerialName("shift")
    data class Shift(
        val state: Int,
    ) : ActionSpec

    @Serializable
    @SerialName("reduce")
    data class Reduce(
        val production: ProductionSpec,
    ) : ActionSpec
}

/** One (symbol, action) cell in an exported LALR(1) table state's row. */
@Serializable
data class TableEntry(
    val symbol: SymbolSpec,
    val action: ActionSpec,
)

/**
 * A parser's already conflict-resolved LALR(1) table: one row of (symbol, action) entries per state
 * (dense, consecutive state ids starting at 0, matching the row's index in [states]), plus the
 * names the parser gives the end of the input ([endOfInput]) and its augmented start symbol ([start]).
 */
@Serializable
data class ParseTableSpec(
    val endOfInput: String,
    val start: String,
    val states: List<List<TableEntry>>,
)

/** Reads a `<parser>.table.json` file written by Alpaca's compile-time grammar export. */
object ParserTableFile {
    const val SUFFIX = ".table.json"

    fun read(path: Path): VersionedExport<ParseTableSpec> = readVersioned(Files.readString(path))
}

/**
 * A single parser's exported grammar, identified by its export file name (sans the
 * `.productions.json` suffix). [table] is null when no matching `.table.json` was found
 * alongside the productions (e.g. an export written before the table export was added).
 */
data class ParserGrammar(
    val id: String,
    val productions: List<ProductionSpec>,
    val table: ParseTableSpec? = null,
)

/** One export file whose `version` didn't match [CURRENT_EXPORT_FORMAT_VERSION], excluded from the
 *  scan's [ExportedGrammars.lexers]/[ExportedGrammars.parsers] the same way a grammar with no
 *  Settings association would be: silently absent, with this record explaining why. */
data class IncompatibleExport(
    val fileName: String,
    val foundVersion: Int,
)

/** All grammars found in an `ALPACA_GRAMMAR_EXPORT_DIR` directory, lexers and parsers scanned
 *  separately; [incompatible] lists any export file whose format version this plugin build
 *  doesn't understand. */
data class ExportedGrammars(
    val lexers: List<LexerGrammar>,
    val parsers: List<ParserGrammar>,
    val incompatible: List<IncompatibleExport> = emptyList(),
)

/**
 * Scans an `ALPACA_GRAMMAR_EXPORT_DIR` directory for `*.tokens.json` and
 * `*.productions.json` files written by Alpaca's compile-time grammar
 * export, one per `lexer{...}`/parser call site.
 */
object GrammarDirectory {
    fun scan(dir: Path): ExportedGrammars {
        if (!Files.isDirectory(dir)) return ExportedGrammars(emptyList(), emptyList())

        val incompatible = mutableListOf<IncompatibleExport>()

        val lexers =
            Files.list(dir).use { entries ->
                entries
                    .asSequence()
                    .filter { it.fileName.toString().endsWith(LexerGrammarFile.SUFFIX) }
                    .mapNotNull { path ->
                        val id = path.fileName.toString().removeSuffix(LexerGrammarFile.SUFFIX)
                        when (val result = LexerGrammarFile.read(path)) {
                            is VersionedExport.Compatible -> LexerGrammar(id, result.value)
                            is VersionedExport.Incompatible -> {
                                incompatible += IncompatibleExport(path.fileName.toString(), result.foundVersion)
                                null
                            }
                        }
                    }.sortedBy { it.id }
                    .toList()
            }

        val tablesById =
            Files
                .list(dir)
                .use { entries ->
                    entries
                        .asSequence()
                        .filter { it.fileName.toString().endsWith(ParserTableFile.SUFFIX) }
                        .mapNotNull { path ->
                            val id = path.fileName.toString().removeSuffix(ParserTableFile.SUFFIX)
                            when (val result = ParserTableFile.read(path)) {
                                is VersionedExport.Compatible -> id to result.value
                                is VersionedExport.Incompatible -> {
                                    incompatible += IncompatibleExport(path.fileName.toString(), result.foundVersion)
                                    null
                                }
                            }
                        }.toList()
                }.toMap()

        val parsers =
            Files.list(dir).use { entries ->
                entries
                    .asSequence()
                    .filter { it.fileName.toString().endsWith(ParserGrammarFile.SUFFIX) }
                    .mapNotNull { path ->
                        val id = path.fileName.toString().removeSuffix(ParserGrammarFile.SUFFIX)
                        when (val result = ParserGrammarFile.read(path)) {
                            is VersionedExport.Compatible ->
                                ParserGrammar(id, productions = result.value, table = tablesById[id])
                            is VersionedExport.Incompatible -> {
                                incompatible += IncompatibleExport(path.fileName.toString(), result.foundVersion)
                                null
                            }
                        }
                    }.sortedBy { it.id }
                    .toList()
            }

        return ExportedGrammars(lexers, parsers, incompatible)
    }
}
