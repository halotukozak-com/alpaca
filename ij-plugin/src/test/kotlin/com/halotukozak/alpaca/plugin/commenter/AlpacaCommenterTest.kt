package com.halotukozak.alpaca.plugin.commenter

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val PARSER_ID = exportedGrammarId("MathTest", "MathParser")

/**
 * Exercises the real "Comment with Line Comment" editor action (Ctrl+/) against [AlpacaCommenter],
 * registered for the real `MathParser` grammar, whose only `ignored` rule shaped like a line
 * comment is `#.*` (see the exported `MathTest.CalcLexer@L<line>.tokens.json`).
 */
class AlpacaCommenterTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = PARSER_ID))
        runWriteAction { AlpacaFileTypeRegistrar.ensureRegistered("calc", LEXER_ID) }
    }

    private fun toggleLineComment() = myFixture.performEditorAction("CommentByLineComment")

    fun `test comments an uncommented line`() {
        myFixture.configureByText("test.calc", "1 + 2<caret>")
        toggleLineComment()

        myFixture.checkResult("# 1 + 2")
    }

    fun `test uncomments an already-commented line`() {
        myFixture.configureByText("test.calc", "# 1 + 2<caret>")
        toggleLineComment()

        myFixture.checkResult("1 + 2")
    }

    fun `test round-trips back to the original text`() {
        myFixture.configureByText("test.calc", "1 + 2<caret>")
        toggleLineComment()
        toggleLineComment()

        myFixture.checkResult("1 + 2")
    }
}
