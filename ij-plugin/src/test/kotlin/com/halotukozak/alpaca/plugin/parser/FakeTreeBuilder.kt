package com.halotukozak.alpaca.plugin.parser

/** A resolved tree node, as reconstructed by [FakeTreeBuilder.buildTree]. */
sealed interface FakeNode {
    data class Leaf(
        val terminal: String,
        val text: String,
    ) : FakeNode

    data class Composite(
        val name: String,
        val children: List<FakeNode>,
    ) : FakeNode
}

/**
 * An in-memory stand-in for [com.intellij.lang.PsiBuilder], letting [AlpacaLrDriver] be
 * unit-tested without a full IntelliJ platform test fixture.
 *
 * Mirrors the real implementation's actual model (verified against `PsiBuilderImpl`/
 * `MarkerProduction` in JetBrains/intellij-community): markers are entries in one flat,
 * order-sensitive list, [mark] appends, [precede] inserts immediately before its target,
 * exactly like the real `addMarker`/`addBefore`, rather than a naive "current open parent"
 * stack, which does not correctly model [drop] (dropping does not bound where a marker's
 * *children* end; only [done] does, at the current position). The final tree is reconstructed
 * from these (start, end) ranges by interval nesting, once parsing is done.
 */
class FakeTreeBuilder(
    private val tokens: List<Pair<String, String>>,
) : TreeBuilder<FakeTreeBuilder.Marker> {
    class Rec(
        val startLexeme: Int,
    ) {
        var endLexeme: Int = -1
        var name: String? = null
        var dropped = false
    }

    /** Acts as the flat "production array": array order is the nesting priority for same-start ties. */
    private val recs = mutableListOf<Rec>()
    private var pos = 0

    inner class Marker(
        val rec: Rec,
    )

    override fun currentTerminal(): String? = tokens.getOrNull(pos)?.first

    override fun currentTokenText(): String = tokens.getOrNull(pos)?.second ?: "<eof>"

    override fun advance() {
        pos++
    }

    override fun mark(): Marker {
        val rec = Rec(pos)
        recs.add(rec)
        return Marker(rec)
    }

    override fun done(
        marker: Marker,
        name: String,
    ) {
        marker.rec.name = name
        marker.rec.endLexeme = pos
    }

    override fun drop(marker: Marker) {
        marker.rec.dropped = true
    }

    override fun precede(marker: Marker): Marker {
        val rec = Rec(marker.rec.startLexeme)
        recs.add(recs.indexOf(marker.rec), rec)
        return Marker(rec)
    }

    override fun error(message: String) {
        errors.add(message to pos)
    }

    val errors = mutableListOf<Pair<String, Int>>()

    /** Reconstructs the nested tree from the surviving (non-dropped) marker ranges plus the leaf tokens. */
    fun buildTree(): List<FakeNode> {
        val items =
            recs.withIndex().filter { !it.value.dropped }.map { (arrayIndex, rec) -> CompositeItem(rec, arrayIndex) } +
                tokens.mapIndexed { index, (terminal, text) -> LeafItem(terminal, text, index) }
        val sorted = items.sortedWith(compareBy({ it.start }, { it.priority }))

        val stack = mutableListOf(Frame(null))
        for (item in sorted) {
            while (stack.size > 1 && stack.last().item!!.end <= item.start) {
                finish(stack.removeAt(stack.size - 1), stack.last())
            }
            when (item) {
                is LeafItem -> stack.last().children.add(FakeNode.Leaf(item.terminal, item.text))
                is CompositeItem -> stack.add(Frame(item))
            }
        }
        while (stack.size > 1) finish(stack.removeAt(stack.size - 1), stack.last())
        return stack[0].children
    }

    private fun finish(
        frame: Frame,
        parent: Frame,
    ) {
        val item = frame.item as CompositeItem
        parent.children.add(FakeNode.Composite(item.rec.name ?: kotlin.error("marker was never done()"), frame.children))
    }

    private class Frame(
        val item: Item?,
    ) {
        val children = mutableListOf<FakeNode>()
    }

    private sealed interface Item {
        val start: Int
        val end: Int
        val priority: Int
    }

    private class CompositeItem(
        val rec: Rec,
        arrayIndex: Int,
    ) : Item {
        override val start = rec.startLexeme
        override val end = rec.endLexeme
        override val priority = arrayIndex
    }

    private class LeafItem(
        val terminal: String,
        val text: String,
        index: Int,
    ) : Item {
        override val start = index
        override val end = index + 1
        override val priority = Int.MAX_VALUE
    }
}
