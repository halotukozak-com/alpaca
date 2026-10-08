package com.halotukozak.alpaca.plugin.documentation

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val PARSER_ID = exportedGrammarId("MathTest", "MathParser")

/**
 * Exercises [AlpacaDocumentationProvider] against MathParser's real exported grammar: a leaf shows
 * its lexer rule (name + pattern); a composite (reachable via a Structure View selection, not just
 * hovering a token -- see [getCustomDocumentationElement][AlpacaDocumentationProvider.getCustomDocumentationElement])
 * lists every production alternative exported for its nonterminal.
 */
class AlpacaDocumentationProviderTest : BasePlatformTestCase() {
    private val provider = AlpacaDocumentationProvider()

    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = PARSER_ID))
        runWriteAction { AlpacaFileTypeRegistrar.ensureRegistered("calc", LEXER_ID) }
    }

    fun `test a leaf shows its token name and pattern`() {
        val file = myFixture.configureByText("test.calc", "pi + 1")
        val leaf = file.findElementAt(0)!!

        val doc = provider.generateDoc(leaf, leaf)

        assertNotNull(doc)
        assertTrue(doc!!.contains("token pi"))
        assertTrue(doc.contains("Pattern: <code>pi</code>"))
        assertFalse(doc.contains("Ignored"))
    }

    fun `test an ignored token is flagged as ignored`() {
        val file = myFixture.configureByText("test.calc", "# a comment\n1")
        val leaf = file.findElementAt(0)!!

        val doc = provider.generateDoc(leaf, leaf)

        assertNotNull(doc)
        assertTrue(doc!!.contains("Ignored by the parser"))
    }

    fun `test a composite lists every production alternative for its nonterminal`() {
        val file = myFixture.configureByText("test.calc", "1 + 2")
        val expr = file.findElementAt(0)!!.parent // the literal-wrapping Expr around "1"

        val doc = provider.generateDoc(expr, expr)

        assertNotNull(doc)
        // Every alternative shares the same "Expr" element type (see AlpacaLrDriver), so hovering
        // any Expr node lists the *entire* rule, not just the alternative that produced this one.
        // Quotes come back HTML-escaped (&#39;) since generateDoc's output is rendered as HTML.
        assertTrue(doc!!.contains("Expr &rarr; Expr &#39;+&#39; Expr <i>(plus)</i>"))
        assertTrue(doc.contains("<i>(sin)</i>"))
    }

    fun `test a grammar with no parser association shows leaf docs but no composite doc`() {
        // With no parser configured, AlpacaFileElementType never runs the LR driver, so there's no
        // Expr wrapper at all -- the file itself is the leaf's only "composite" ancestor. Either
        // way, resolved.parserGrammar is null, so compositeDoc has nothing to list.
        AlpacaSettingsState.getInstance(project).associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = ""))
        val file = myFixture.configureByText("test.calc", "pi")
        val leaf = file.findElementAt(0)!!

        assertNotNull(provider.generateDoc(leaf, leaf))
        assertNull(provider.generateDoc(file, file))
    }

    fun `test no documentation outside an Alpaca language`() {
        val file = myFixture.configureByText("plain.txt", "pi")
        val leaf = file.findElementAt(0)!!

        assertNull(provider.generateDoc(leaf, leaf))
    }

    fun `test the platform can actually find a documentation target at a token`() {
        // Regression test for the real bug: Alpaca-defined languages have no PsiReference and no
        // PsiNamedElement, so TargetElementUtil's default resolution finds nothing at a caret
        // offset -- Quick Documentation did nothing at all on hover/Ctrl+Q, even though
        // generateDoc(leaf, leaf) called directly (the rest of this test class) worked fine.
        // Exercises the platform's real target-resolution path, which is what caught this.
        myFixture.configureByText("test.calc", "pi")
        myFixture.editor.caretModel.moveToOffset(0)

        val target = DocumentationManager.getInstance(project).findTargetElement(myFixture.editor, myFixture.file)

        assertNotNull(target)
        assertEquals("pi", target!!.text)
    }

    fun `test getCustomDocumentationElement returns null outside an Alpaca language`() {
        val file = myFixture.configureByText("plain.txt", "pi")
        val leaf = file.findElementAt(0)!!

        assertNull(provider.getCustomDocumentationElement(myFixture.editor, file, leaf, 0))
    }
}
