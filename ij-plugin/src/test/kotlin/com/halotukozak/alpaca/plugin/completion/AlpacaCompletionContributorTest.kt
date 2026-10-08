package com.halotukozak.alpaca.plugin.completion

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val PARSER_ID = exportedGrammarId("MathTest", "MathParser")

/**
 * Exercises the actual registered [AlpacaCompletionContributor] end to end: real editor, real
 * caret, real completion invocation. [AlpacaCompletionEngineTest] already covers the suggestion
 * logic in isolation; this instead catches wiring mistakes that wouldn't, such as a typo'd
 * extension point name, wrong caret/offset handling, or a prefix matcher that isn't filtering.
 */
class AlpacaCompletionContributorTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = PARSER_ID))
        runWriteAction { AlpacaFileTypeRegistrar.ensureRegistered("calc", LEXER_ID) }
    }

    fun `test suggests literal terminals valid at the caret`() {
        myFixture.configureByText("test.calc", "1 + <caret>")
        myFixture.completeBasic()

        val suggestions = myFixture.lookupElementStrings!!
        assertTrue("sin" in suggestions)
        assertTrue("(" in suggestions)
        assertTrue(")" !in suggestions)
    }

    fun `test filters suggestions by the word already being typed`() {
        myFixture.configureByText("test.calc", "1 + si<caret>")
        myFixture.completeBasic()

        val suggestions = myFixture.lookupElementStrings!!
        assertTrue("sin" in suggestions)
        assertTrue("sinh" in suggestions)
        assertTrue("cos" !in suggestions)
    }
}
