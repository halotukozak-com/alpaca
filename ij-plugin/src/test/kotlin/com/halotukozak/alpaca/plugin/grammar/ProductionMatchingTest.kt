package com.halotukozak.alpaca.plugin.grammar

import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val PARSER_ID = exportedGrammarId("MathTest", "MathParser")

/**
 * Exercises [matchedAlternativeName] directly against MathParser's real exported grammar, ahead
 * of any UI surface using it (currently [com.halotukozak.alpaca.plugin.editing.AlpacaBreadcrumbsProvider]).
 */
class ProductionMatchingTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = PARSER_ID))
        runWriteAction { AlpacaFileTypeRegistrar.ensureRegistered("calc", LEXER_ID) }
    }

    /**
     * Matches the top-level `Expr` parsed from [text]: `root -> Expr` is always a unit production
     * (unnamed, same text range as its child), so `root`'s one composite child is the actual
     * outermost expression -- e.g. for `"1 + 2"` this is the `plus` node, not the `int`-literal
     * node the first token's own PSI parent would give (that leaf is nested one level deeper).
     */
    private fun matchOutermost(text: String): String? {
        val file = myFixture.configureByText("test.calc", text)
        val resolved = resolveGrammarForFile(project, file.virtualFile)!!
        val root = file.children.single()
        val outermostExpr = root.children.single()
        return matchedAlternativeName(outermostExpr, resolved)
    }

    fun `test matches a binary operator alternative`() {
        assertEquals("plus", matchOutermost("1 + 2"))
    }

    fun `test matches a function-call alternative`() {
        assertEquals("sin", matchOutermost("sin(1)"))
    }

    fun `test returns null for an unnamed alternative`() {
        // Expr -> int has no name in MathParser's grammar.
        assertNull(matchOutermost("42"))
    }

    fun `test returns null when the file's grammar has no parser association`() {
        AlpacaSettingsState.getInstance(project).associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = ""))
        val file = myFixture.configureByText("test.calc", "1")
        val resolved = resolveGrammarForFile(project, file.virtualFile)!!

        assertNull(matchedAlternativeName(file.findElementAt(0)!!.parent, resolved))
    }
}
