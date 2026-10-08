package com.halotukozak.alpaca.plugin.editing

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.openapi.application.runWriteAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val PARSER_ID = exportedGrammarId("MathTest", "MathParser")

/**
 * Every `Expr` alternative in MathParser's grammar reduces to the same `Expr` element type (see
 * [com.halotukozak.alpaca.plugin.parser.AlpacaLrDriver], which names composites after the
 * production's LHS, not its per-alternative label) -- so a crumb chain naming just the nonterminal
 * would repeat "Expr" at every level. [AlpacaBreadcrumbsProvider.getElementInfo] instead labels
 * each crumb with the *production alternative* that built it (`plus`, `sin`), recovered via
 * [com.halotukozak.alpaca.plugin.grammar.matchedAlternativeName]; the nonterminal is only the
 * fallback for an alternative with no name (`Expr -> int` is unnamed).
 */
class AlpacaBreadcrumbsProviderTest : BasePlatformTestCase() {
    private val provider = AlpacaBreadcrumbsProvider()

    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = PARSER_ID))
        runWriteAction { AlpacaFileTypeRegistrar.ensureRegistered("calc", LEXER_ID) }
    }

    /** Accepted crumb labels from the caret marked by `<caret>` in [text], innermost first. */
    private fun crumbs(text: String): List<String> {
        val file = myFixture.configureByText("test.calc", text)
        val crumbs = mutableListOf<String>()
        var element: PsiElement? = file.findElementAt(myFixture.caretOffset)
        while (element != null && element !is PsiFile) {
            if (provider.acceptElement(element)) crumbs += provider.getElementInfo(element)
            element = provider.getParent(element)
        }
        return crumbs
    }

    fun `test skips the root's pure pass-through unit production`() {
        // root -> Expr is a unit production (same text range as its Expr child): filtered. The
        // literal-wrapping Expr around a single "int" token is not a unit production of another
        // composite (the terminal is a leaf, invisible to PsiElement#getChildren), so it stays.
        // Expr -> int is unnamed, so the crumb falls back to the nonterminal.
        assertEquals(listOf("Expr"), crumbs("<caret>42"))
    }

    fun `test labels each crumb with its production alternative, innermost first`() {
        val result = crumbs("sin(<caret>1 + 2)")

        // The literal falls back to "Expr" (Expr -> int is unnamed); the sum and the call each
        // have a named alternative, so they're distinguishable from one another and from the
        // literal -- unlike before, when every level here was indistinguishably "Expr".
        assertEquals(listOf("Expr", "plus", "sin"), result)
    }

    fun `test a crumb is just the label, no source snippet`() {
        val result = crumbs("sin(<caret>1 + 222222222222222222)")

        assertEquals(listOf("Expr", "plus", "sin"), result)
    }

    fun `test no crumbs when the file's grammar has no parser association`() {
        AlpacaSettingsState.getInstance(project).associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = ""))

        // With no parser configured, AlpacaFileElementType never runs the LR driver: there's no
        // Expr wrapper at all, just the bare token directly under the file -- nothing for
        // acceptElement to keep.
        assertEquals(emptyList<String>(), crumbs("<caret>1"))
    }
}
