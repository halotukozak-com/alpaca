package com.halotukozak.alpaca.plugin.editing

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val PARSER_ID = exportedGrammarId("MathTest", "MathParser")

/**
 * Regression coverage for [AlpacaBraceHighlighter.matchBrace]: it must match against the editor
 * document, not `psiFile.text`, or it can hand the platform a [TextRange] past the document's
 * current end -- exactly the crash `BackgroundHighlighter` hit mid-edit ("Invalid offsets:
 * start=210; end=211; document length=210"), because `psiFile.text` can still reflect a longer,
 * pre-edit version of the file while the corresponding PSI commit hasn't run yet.
 */
class AlpacaBraceHighlighterTest : BasePlatformTestCase() {
    private val highlighter = AlpacaBraceHighlighter()

    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = PARSER_ID))
        runWriteAction { AlpacaFileTypeRegistrar.ensureRegistered("calc", LEXER_ID) }
    }

    fun `test matches a paren pair once the document is committed`() {
        val file = myFixture.configureByText("test.calc", "(1+2)")

        val match = highlighter.matchBrace(file, 0)

        assertEquals(TextRange(0, 1), match?.first)
        assertEquals(TextRange(4, 5), match?.second)
    }

    fun `test never matches past the document end while an edit is uncommitted`() {
        val file = myFixture.configureByText("test.calc", "(1+2)")
        val document = myFixture.editor.document

        // Shrink the document directly, without committing PSI: psiFile.text (and the PSI tree)
        // still reflects the old, longer text -- the same staleness BackgroundHighlighter can see
        // mid-keystroke, before the commit that normally follows a real edit.
        WriteCommandAction.runWriteCommandAction(project) { document.deleteString(4, 5) }
        assertFalse(PsiDocumentManager.getInstance(project).isCommitted(document))

        val match = highlighter.matchBrace(file, document.textLength)

        assertTrue((match?.first?.endOffset ?: 0) <= document.textLength)
        assertTrue((match?.second?.endOffset ?: 0) <= document.textLength)
    }
}
