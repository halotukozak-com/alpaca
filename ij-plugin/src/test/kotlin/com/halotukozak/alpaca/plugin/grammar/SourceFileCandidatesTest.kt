package com.halotukozak.alpaca.plugin.grammar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class SourceFileCandidatesTest {
    private val projectBase = Path.of("/work/alpaca").toAbsolutePath()
    private val contentRoot = Path.of("/work/alpaca/test").toAbsolutePath()

    @Test
    fun `resolves a relative path against each root in order`() {
        assertEquals(
            listOf(
                projectBase.resolve("test/src/Grammar.scala"),
                contentRoot.resolve("test/src/Grammar.scala"),
            ),
            sourceFileCandidates("test/src/Grammar.scala", listOf(projectBase, contentRoot)),
        )
    }

    @Test
    fun `drops a root that resolves to the same file as an earlier one`() {
        assertEquals(
            listOf(projectBase.resolve("src/Grammar.scala")),
            sourceFileCandidates("src/Grammar.scala", listOf(projectBase, projectBase.resolve("."))),
        )
    }

    @Test
    fun `keeps an absolute path as it is`() {
        val absolute = Path.of("/elsewhere/Grammar.scala").toAbsolutePath()

        assertEquals(listOf(absolute), sourceFileCandidates(absolute.toString(), listOf(projectBase)))
    }

    @Test
    fun `has no candidates for a relative path without roots`() {
        assertEquals(emptyList<Path>(), sourceFileCandidates("src/Grammar.scala", emptyList()))
    }
}
