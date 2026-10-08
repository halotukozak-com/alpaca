package com.halotukozak.alpaca.plugin.formatting

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val LEXER_ID = exportedGrammarId("BrainLexer", "BrainLexer")
private val PARSER_ID = exportedGrammarId("BrainParser", "BrainParser")

/**
 * The same [AlpacaFormattingModelBuilder] against a completely different grammar -- Brainfuck
 * (plus named functions), whose only bracket kind is `[`/`]` and which has no comma, semicolon,
 * or dot tokens at all -- with no code specific to either grammar. Complements
 * [AlpacaFormattingModelBuilderTest], which uses `MathParser`'s `(`/`)`, `,`.
 */
class AlpacaFormattingModelBuilderBrainfuckTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(GrammarAssociation(extension = "bf", lexerGrammarId = LEXER_ID, parserGrammarId = PARSER_ID))
        runWriteAction { AlpacaFileTypeRegistrar.ensureRegistered("bf", LEXER_ID) }
    }

    private fun reformat(text: String): String {
        myFixture.configureByText("test.bf", text)
        myFixture.performEditorAction("ReformatCode")
        return myFixture.editor.document.text
    }

    fun `test a single-operation loop needs no spacing changes`() {
        assertEquals("[+]", reformat("[+]"))
    }

    fun `test indents a wrapped loop body by one level`() {
        val result = reformat("[\n+\n]")

        assertEquals("[\n    +\n]", result)
    }

    fun `test leaves consecutive operations exactly as typed`() {
        // None of `+`/`-`/`>`/`<` is a bracket/comma/semicolon/dot, and there is no generic
        // fallback spacing rule, so Brainfuck's own tight-packed convention survives untouched.
        assertEquals("+++", reformat("+++"))
    }
}
