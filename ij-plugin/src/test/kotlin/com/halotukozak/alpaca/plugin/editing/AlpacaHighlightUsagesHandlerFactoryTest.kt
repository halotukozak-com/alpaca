package com.halotukozak.alpaca.plugin.editing

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val CALC_LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val CALC_PARSER_ID = exportedGrammarId("MathTest", "MathParser")
private val BRAIN_LEXER_ID = exportedGrammarId("BrainLexer", "BrainLexer")
private val BRAIN_PARSER_ID = exportedGrammarId("BrainParser", "BrainParser")

/**
 * Exercises [AlpacaHighlightUsagesHandlerFactory] end to end -- through the platform's own
 * `findTarget` (caret -> leaf) -- against two unrelated grammars, with no code specific to either.
 */
class AlpacaHighlightUsagesHandlerFactoryTest : BasePlatformTestCase() {
    private val factory = AlpacaHighlightUsagesHandlerFactory()

    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(
                GrammarAssociation(extension = "calc", lexerGrammarId = CALC_LEXER_ID, parserGrammarId = CALC_PARSER_ID),
                GrammarAssociation(extension = "bf", lexerGrammarId = BRAIN_LEXER_ID, parserGrammarId = BRAIN_PARSER_ID),
            )
        runWriteAction {
            AlpacaFileTypeRegistrar.ensureRegistered("calc", CALC_LEXER_ID)
            AlpacaFileTypeRegistrar.ensureRegistered("bf", BRAIN_LEXER_ID)
        }
    }

    /** The token texts the handler would highlight for the caret marked by `<caret>` in [text]. */
    private fun highlightedTexts(
        fileName: String,
        text: String,
    ): List<String>? {
        myFixture.configureByText(fileName, text)
        val handler = factory.createHighlightUsagesHandler(myFixture.editor, myFixture.file) ?: return null
        // Drives the same getTargets -> selectTargets -> computeUsages pipeline the platform runs,
        // not just computeUsages in isolation.
        handler.highlightUsages()
        val document = myFixture.editor.document.charsSequence
        return handler.readUsages.map { document.substring(it.startOffset, it.endOffset) }
    }

    fun `test highlights every occurrence of the keyword under the caret`() {
        assertEquals(listOf("pi", "pi"), highlightedTexts("test.calc", "<caret>pi + pi * e"))
    }

    fun `test a single occurrence still highlights itself`() {
        assertEquals(listOf("tau"), highlightedTexts("test.calc", "1 + t<caret>au"))
    }

    fun `test does not cross token types`() {
        // "e" the constant and "atan2" are different tokens; caret on the standalone "e" must not
        // drag in the "e" that is only a letter inside "atan2".
        assertEquals(listOf("e", "e", "e"), highlightedTexts("test.calc", "atan2 (<caret>e, e) + e - pi"))
    }

    fun `test no handler for a punctuation token`() {
        assertNull(highlightedTexts("test.calc", "1 <caret>+ 2"))
    }

    fun `test no handler for a numeric token`() {
        assertNull(highlightedTexts("test.calc", "4<caret>2 + 42"))
    }

    fun `test the same factory highlights identifiers in a different grammar`() {
        assertEquals(listOf("foo", "foo"), highlightedTexts("test.bf", "<caret>foo(+++)foo!"))
    }

    fun `test no handler outside an Alpaca language`() {
        assertNull(highlightedTexts("plain.txt", "<caret>name + name"))
    }
}
