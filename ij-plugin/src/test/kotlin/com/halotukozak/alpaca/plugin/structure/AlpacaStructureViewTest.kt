package com.halotukozak.alpaca.plugin.structure

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val PARSER_ID = exportedGrammarId("MathTest", "MathParser")

/**
 * Exercises [AlpacaStructureViewFactory]/[AlpacaStructureViewModel]/[AlpacaStructureViewElement]
 * against a real PSI tree, built by the real [com.halotukozak.alpaca.plugin.parser.AlpacaFileElementType]
 * from the same exported `MathParser` grammar [com.halotukozak.alpaca.plugin.parser.AlpacaLrDriverTest]
 * uses, this is what a full platform test fixture buys over that lighter-weight test: an actual
 * [com.intellij.psi.PsiFile] with real composite [com.intellij.psi.PsiElement]s to build a structure
 * view model from.
 */
class AlpacaStructureViewTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = PARSER_ID))
        runWriteAction { AlpacaFileTypeRegistrar.ensureRegistered("calc", LEXER_ID) }
    }

    private fun rootElementFor(text: String): StructureViewTreeElement {
        val file = myFixture.configureByText("test.calc", text)
        return AlpacaStructureViewModel(file, null).root
    }

    fun `test file node is presented by its name`() {
        val root = rootElementFor("sin(1)")
        assertEquals("test.calc", root.presentation.presentableText)
    }

    fun `test nests root and Expr nonterminal nodes exactly like the parsed tree`() {
        // root -> Expr -> sin ( Expr -> int ): only composite (nonterminal) nodes are structure view
        // elements, so the leaf tokens ("sin", "(", ")", "1") don't add extra levels of their own.
        val root = rootElementFor("sin(1)")

        val rootProduction = root.children.single()
        assertEquals("root: sin(1)", rootProduction.presentation.presentableText)

        val outerExpr = rootProduction.children.single()
        assertEquals("Expr: sin(1)", outerExpr.presentation.presentableText)

        val innerExpr = outerExpr.children.single()
        assertEquals("Expr: 1", innerExpr.presentation.presentableText)
        assertEquals(0, innerExpr.children.size)
    }

    fun `test truncates long node text so entries stay short`() {
        val root = rootElementFor("1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9 + 10 + 11 + 12")
        val rootProduction = root.children.single()

        assertTrue(rootProduction.presentation.presentableText!!.endsWith("…"))
    }
}
