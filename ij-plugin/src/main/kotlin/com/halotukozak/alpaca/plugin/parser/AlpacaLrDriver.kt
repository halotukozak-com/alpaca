package com.halotukozak.alpaca.plugin.parser

import com.halotukozak.alpaca.plugin.grammar.ActionSpec
import com.halotukozak.alpaca.plugin.grammar.ParseTableSpec
import com.halotukozak.alpaca.plugin.grammar.SymbolSpec

/**
 * A generic, grammar-agnostic LR parser: drives a [TreeBuilder] using [table], Alpaca's already
 * conflict-resolved parse table (currently LALR(1); see `ParseTable` on the Scala side), producing
 * one composite node per reduced nonterminal. The table alone decides every shift/reduce/goto
 * choice, including precedence and associativity, since those were already settled when the table
 * was built. This driver only replays the table, so it's unaffected by which LR variant produced it.
 *
 * Building a top-down PSI tree from a bottom-up parse needs a marker per still-unreduced stack
 * symbol, not just one per production. A reduction's first RHS symbol is often a plain terminal
 * (`sin` in `Expr -> sin ( Expr )`), so there's no earlier nonterminal marker to piggyback on the
 * way a recursive-descent parser gets for free from left recursion. [TreeBuilder.precede] makes
 * marking every shift affordable: an unresolved marker costs nothing until it's actually turned
 * into a node, or dropped without one.
 */
class AlpacaLrDriver(
    private val table: List<Map<SymbolSpec, ActionSpec>>,
    private val endOfInput: String,
    private val augmentedStart: String,
) {
    companion object {
        /** Builds a driver from an exported `.table.json` (see [com.halotukozak.alpaca.plugin.grammar.ParserTableFile]). */
        fun forTable(spec: ParseTableSpec): AlpacaLrDriver =
            AlpacaLrDriver(spec.states.map { row -> row.associate { it.symbol to it.action } }, spec.endOfInput, spec.start)
    }

    fun <M> parse(builder: TreeBuilder<M>) {
        val stateStack = mutableListOf(0)
        val markerStack = mutableListOf<StackEntry<M>?>(null) // sentinel for state 0; never touched

        while (true) {
            val state = stateStack.last()
            val terminal = builder.currentTerminal() ?: endOfInput
            val action = table[state][SymbolSpec("terminal", terminal)]

            if (action == null) {
                builder.error("Unexpected token '${builder.currentTokenText()}'")
                if (terminal == endOfInput) {
                    // Incomplete input (still typing, or a genuine syntax error): whatever's left on the
                    // stack (bare shifted terminals that never got reduced) would otherwise stay
                    // unresolved forever, which PsiBuilder rejects as an unbalanced tree.
                    drainUnresolvedMarkers(builder, markerStack)
                    return
                }
                val errorMarker = builder.mark()
                builder.advance()
                builder.drop(errorMarker)
                continue
            }

            when (action) {
                is ActionSpec.Shift -> {
                    val marker = builder.mark()
                    builder.advance()
                    stateStack.add(action.state)
                    markerStack.add(StackEntry(marker, resolved = false))
                }

                is ActionSpec.Reduce -> {
                    val production = action.production
                    // reducing the augmented start production means the input was fully and successfully parsed
                    if (production.lhs == augmentedStart) return

                    val rhsSize = production.rhs.size
                    val reducedMarker =
                        if (rhsSize == 0) {
                            // Epsilon production: nothing to wrap, so mark and immediately close a zero-width node.
                            builder.mark().also { builder.done(it, production.lhs) }
                        } else {
                            // Popped in right-to-left order: poppedEntries[0] is the rightmost RHS symbol,
                            // poppedEntries[rhsSize - 1] is the leftmost.
                            val poppedEntries =
                                (1..rhsSize).map {
                                    stateStack.removeAt(stateStack.size - 1)
                                    markerStack.removeAt(markerStack.size - 1)!!
                                }
                            for (i in 0 until rhsSize - 1) {
                                val entry = poppedEntries[i]
                                if (!entry.resolved) builder.drop(entry.marker)
                            }
                            val leftmost = poppedEntries[rhsSize - 1]
                            val outer = builder.precede(leftmost.marker)
                            if (!leftmost.resolved) builder.drop(leftmost.marker)
                            builder.done(outer, production.lhs)
                            outer
                        }

                    val gotoState = goto(stateStack.last(), production.lhs)
                    stateStack.add(gotoState)
                    markerStack.add(StackEntry(reducedMarker, resolved = true))
                }
            }
        }
    }

    /** Resolves every still-open marker (bare shifted terminals that never got reduced), innermost
     *  first, so an incomplete parse still leaves PsiBuilder with a fully balanced tree. */
    private fun <M> drainUnresolvedMarkers(
        builder: TreeBuilder<M>,
        markerStack: MutableList<StackEntry<M>?>,
    ) {
        for (i in markerStack.indices.reversed()) {
            val entry = markerStack[i] ?: continue // the sentinel for state 0
            if (!entry.resolved) builder.drop(entry.marker)
        }
    }

    private fun goto(
        state: Int,
        nonterminalName: String,
    ): Int {
        val action = table[state][SymbolSpec("nonterminal", nonterminalName)]
        return (action as ActionSpec.Shift).state
    }

    /** [resolved] is false for a marker still awaiting resolution (a bare shifted terminal),
     *  true for one already closed as a composite node by an earlier reduce. */
    private data class StackEntry<M>(
        val marker: M,
        val resolved: Boolean,
    )
}
