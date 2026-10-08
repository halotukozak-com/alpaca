package com.halotukozak.alpaca.plugin.lexer

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.lexer.Lexer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")

/**
 * [AlpacaSyntaxHighlighterFactory] is the entry point the platform actually calls per file, so
 * these tests exercise the grammar-resolution + fallback wiring end to end, on top of the plain
 * lexing already covered by [AlpacaLexerTest] and [AlpacaSyntaxHighlighterTest].
 */
class AlpacaSyntaxHighlighterFactoryTest : BasePlatformTestCase() {
    private val factory = AlpacaSyntaxHighlighterFactory()

    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations = mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID))
    }

    private fun tokenTypesOf(
        text: String,
        lexer: Lexer,
    ): List<String> {
        lexer.start(text, 0, text.length, 0)
        val types = mutableListOf<String>()
        while (lexer.tokenType != null) {
            types += lexer.tokenType.toString()
            lexer.advance()
        }
        return types
    }

    fun `test resolves the mapped grammar for a file with an associated extension`() {
        val file = myFixture.configureByText("test.calc", "1 + 2").virtualFile
        val highlighter = factory.getSyntaxHighlighter(project, file)

        assertEquals(
            listOf("int", "[ \t\r\n]+", "\\+", "[ \t\r\n]+", "int"),
            tokenTypesOf("1 + 2", highlighter.highlightingLexer),
        )
    }

    fun `test falls back to an unresolved empty grammar for a file with no association`() {
        val file = myFixture.configureByText("test.txt", "x").virtualFile
        val highlighter = factory.getSyntaxHighlighter(project, file)

        assertEquals(listOf("ALPACA_BAD_CHARACTER"), tokenTypesOf("x", highlighter.highlightingLexer))
    }

    fun `test falls back to an unresolved empty grammar when project or file is null`() {
        val highlighter = factory.getSyntaxHighlighter(null, null)

        assertEquals(listOf("ALPACA_BAD_CHARACTER"), tokenTypesOf("x", highlighter.highlightingLexer))
    }
}
