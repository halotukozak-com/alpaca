package com.halotukozak.alpaca.plugin.completion

import com.halotukozak.alpaca.plugin.grammar.ActionSpec
import com.halotukozak.alpaca.plugin.grammar.SymbolSpec
import com.halotukozak.alpaca.plugin.grammar.TableEntry
import com.halotukozak.alpaca.plugin.grammar.TokenSpec
import com.halotukozak.alpaca.plugin.grammar.literalTextOf
import com.halotukozak.alpaca.plugin.lexer.ALPACA_BAD_CHARACTER
import com.halotukozak.alpaca.plugin.lexer.AlpacaLexer
import com.halotukozak.alpaca.plugin.lexer.AlpacaTokenTypes

private const val AUGMENTED_START_NAME = "S'\$\$synthetic\$\$"

/** Bounds the reduce chain a single hypothetical lookahead can trigger. Generous relative to any
 *  real grammar's production count; just guards against a malformed table looping forever. */
private const val MAX_REDUCE_CHAIN = 10_000

/**
 * Suggests which literal-text terminals are syntactically valid immediately after [prefixText],
 * using the same exported LR table [com.halotukozak.alpaca.plugin.parser.AlpacaLrDriver] drives.
 * Replays [prefixText]'s own tokens through it to find the current parser state, then tests every
 * literal-pattern terminal in the grammar as a hypothetical next token: if shifting it, after
 * whatever reductions its lookahead would trigger, doesn't hit an error, it's offered. Regex-class
 * terminals (numbers, identifiers, ...) have no fixed spelling to offer, so only terminals whose
 * pattern denotes exactly one literal string (see [literalTextOf]) are ever candidates.
 *
 * Callers are expected to pass [prefixText] with any partially-typed trailing word already
 * stripped, so every token this lexes is treated as complete.
 */
class AlpacaCompletionEngine(
    rows: List<List<TableEntry>>,
) {
    private val table: List<Map<SymbolSpec, ActionSpec>> = rows.map { row -> row.associate { it.symbol to it.action } }

    fun suggestNextLiterals(
        lexerId: String,
        tokenSpecs: List<TokenSpec>,
        prefixText: String,
    ): List<String> {
        val stack = replayStates(lexerId, tokenSpecs, prefixText) ?: return emptyList()
        return tokenSpecs
            .asSequence()
            .mapNotNull { spec -> literalTextOf(spec.pattern)?.takeIf { canEventuallyShift(spec.name, stack) } }
            .distinct()
            .toList()
    }

    /** Lexes [prefixText] with the grammar's own lexer and replays every non-ignored token through
     *  [table], returning the resulting state stack. Null if [prefixText] itself doesn't lex or
     *  parse cleanly, in which case there's nothing meaningful to suggest. */
    private fun replayStates(
        lexerId: String,
        tokenSpecs: List<TokenSpec>,
        prefixText: String,
    ): List<Int>? {
        val specByType = tokenSpecs.associateBy { AlpacaTokenTypes.forName(lexerId, it.name) }
        val lexer = AlpacaLexer(lexerId, tokenSpecs)
        lexer.start(prefixText, 0, prefixText.length, 0)

        tailrec fun loop(stack: List<Int>): List<Int>? {
            val type = lexer.tokenType ?: return stack
            if (type == ALPACA_BAD_CHARACTER) return null
            val spec = specByType.getValue(type)
            val nextStack = if (spec.ignored) stack else stackAfterShifting(spec.name, stack) ?: return null
            lexer.advance()
            return loop(nextStack)
        }
        return loop(listOf(0))
    }

    private fun canEventuallyShift(
        terminalName: String,
        stack: List<Int>,
    ): Boolean = stackAfterShifting(terminalName, stack) != null

    /** Applies whatever chain of reductions [table] demands for [terminalName] as lookahead from
     *  [stack], then shifts it. Returns the resulting stack, or null if [terminalName] triggers an
     *  error (or the augmented accept production, which expects nothing more) from [stack]. */
    private fun stackAfterShifting(
        terminalName: String,
        stack: List<Int>,
    ): List<Int>? {
        val terminalSymbol = SymbolSpec("terminal", terminalName)

        tailrec fun loop(
            s: List<Int>,
            chainLength: Int,
        ): List<Int>? {
            if (chainLength >= MAX_REDUCE_CHAIN) return null
            return when (val action = table[s.last()][terminalSymbol]) {
                is ActionSpec.Shift -> s + action.state
                is ActionSpec.Reduce -> {
                    val production = action.production
                    if (production.lhs == AUGMENTED_START_NAME) return null
                    val reduced = if (production.rhs.isEmpty()) s else s.dropLast(production.rhs.size)
                    val gotoState =
                        (table[reduced.last()][SymbolSpec("nonterminal", production.lhs)] as? ActionSpec.Shift)?.state
                            ?: return null
                    loop(reduced + gotoState, chainLength + 1)
                }
                null -> null
            }
        }
        return loop(stack, 0)
    }
}
