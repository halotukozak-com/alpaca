package com.halotukozak.alpaca.plugin.folding

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val PARSER_ID = exportedGrammarId("MathTest", "MathParser")

/**
 * Exercises [AlpacaFoldingBuilder] against a real PSI tree built by the real
 * [com.halotukozak.alpaca.plugin.parser.AlpacaFileElementType], the same way
 * [com.halotukozak.alpaca.plugin.structure.AlpacaStructureViewTest] does for the structure view.
 */
class AlpacaFoldingBuilderTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = PARSER_ID))
        runWriteAction { AlpacaFileTypeRegistrar.ensureRegistered("calc", LEXER_ID) }
    }

    private fun foldRegionsFor(text: String): List<Pair<String, String>> {
        val file = myFixture.configureByText("test.calc", text)
        val document = myFixture.getDocument(file)
        val descriptors = AlpacaFoldingBuilder().buildFoldRegions(file, document, false)
        return descriptors.map { it.range.substring(text) to it.placeholderText!! }
    }

    private fun String.substring(range: com.intellij.openapi.util.TextRange) = range.substring(this)

    fun `test does not fold anything for a single-line expression`() {
        assertEquals(emptyList<Any>(), foldRegionsFor("sin(1)"))
    }

    fun `test folds a composite node whose text spans multiple lines`() {
        val regions = foldRegionsFor("sin(\n1\n)")

        // Both the `root` wrapper and its `Expr` child span the whole (multi-line) input verbatim:
        // a unit-production chain over the same range. Only the outer one gets a region.
        assertEquals(listOf("sin(\n1\n)" to "..."), regions)
    }

    fun `test only folds the nodes that actually cross a line break`() {
        // The outer sum spans two lines; its right operand ("2") does not, so only the outer sum
        // (and, by the same unit-production collapsing as above, nothing else) gets a region.
        val regions = foldRegionsFor("1 +\n2")

        assertEquals(listOf("1 +\n2" to "..."), regions)
    }
}
