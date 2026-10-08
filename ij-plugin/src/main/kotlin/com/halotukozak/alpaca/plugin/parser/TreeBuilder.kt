package com.halotukozak.alpaca.plugin.parser

/**
 * The minimal tree-building surface [AlpacaLrDriver] needs: a real [com.intellij.lang.PsiBuilder]
 * adapter in production, an in-memory fake in tests.
 *
 * [M] mirrors `PsiBuilder.Marker`: an opaque handle to a not-yet-resolved position. A marker
 * resolves to a composite node via [done]. It can also vanish via [drop], in which case its
 * would-be children rejoin whatever ends up enclosing them. [precede] wraps everything from a
 * marker's own start up to the current position in a new, independent marker. Bottom-up
 * (shift-reduce) parsing needs that: a reduction only recognizes "this span should have been one
 * node" after the span has already been consumed.
 */
interface TreeBuilder<M> {
    /** The current lookahead token's terminal name, as it appears in the exported grammar, or null at end of input. */
    fun currentTerminal(): String?

    /** The current lookahead token's raw source text, or `"<eof>"` at end of input. Distinct from
     *  [currentTerminal] because that's often a token's *pattern* (e.g. `\)`), not user-facing text. */
    fun currentTokenText(): String

    fun advance()

    /** Marks the current position, to later be resolved via [done]/[drop] or superseded via [precede]. */
    fun mark(): M

    /** Resolves [marker] as a composite node named [name], spanning from its start to the current position. */
    fun done(
        marker: M,
        name: String,
    )

    /** Resolves [marker] without producing a node of its own. */
    fun drop(marker: M)

    /** Returns a new marker starting at [marker]'s own start and extending to the current position. */
    fun precede(marker: M): M

    fun error(message: String)
}
