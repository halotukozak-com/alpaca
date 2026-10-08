package com.halotukozak.alpaca.plugin.todo

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.lexer.AlpacaTokenTypes
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.openapi.application.runWriteAction
import com.intellij.psi.search.PsiTodoSearchHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val CALC_LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val CALC_PARSER_ID = exportedGrammarId("MathTest", "MathParser")
private val BRAIN_LEXER_ID = exportedGrammarId("BrainLexer", "BrainLexer")
private val BRAIN_PARSER_ID = exportedGrammarId("BrainParser", "BrainParser")

/**
 * `MathTest`'s grammar has a `#.*` ignored rule (a line comment by shape); `BrainLexer`'s only
 * ignored rules are `.` and `\n` (not comment-shaped). Exercises [AlpacaIndexPatternBuilder]
 * against both, with no grammar-specific code.
 */
class AlpacaIndexPatternBuilderTest : BasePlatformTestCase() {
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

    private fun todoTexts(
        fileName: String,
        text: String,
    ): List<String> {
        val file = myFixture.configureByText(fileName, text)
        return PsiTodoSearchHelper
            .getInstance(project)
            .findTodoItemsLight(file)
            .map { file.text.substring(it.textRange.startOffset, it.textRange.endOffset) }
    }

    fun `test finds a TODO inside a hash comment`() {
        assertEquals(listOf("TODO fix precedence"), todoTexts("test.calc", "1 + 2 # TODO fix precedence"))
    }

    fun `test finds a marker written tight against the comment prefix`() {
        // getCommentStartDelta strips the leading "#" so "#TODO" still matches.
        assertEquals(listOf("TODO"), todoTexts("test.calc", "1 #TODO"))
    }

    fun `test also matches the built-in FIXME pattern`() {
        assertEquals(listOf("FIXME later"), todoTexts("test.calc", "# FIXME later"))
    }

    fun `test a marker outside any comment is not a TODO`() {
        assertEquals(emptyList<String>(), todoTexts("test.calc", "1 + 2"))
    }

    fun `test a grammar with no comment-shaped rule reports nothing`() {
        // BrainLexer's ignored rules ("." and "\n") are not "prefix.*" -- getCommentTokenSet
        // returns null, so there is no comment span for the scanner to look inside.
        assertEquals(emptyList<String>(), todoTexts("test.bf", "+++ TODO"))
    }

    fun `test reports the prefix length as the comment start delta`() {
        val builder = AlpacaIndexPatternBuilder()
        val file = myFixture.configureByText("test.calc", "# TODO")
        // Populates the builder's per-type prefix map; "#.*" is MathTest's line-comment rule.
        builder.getCommentTokenSet(file)
        val hashComment = AlpacaTokenTypes.forName(CALC_LEXER_ID, "#.*")

        assertEquals(1, builder.getCommentStartDelta(hashComment))
        assertEquals(1, builder.getCommentStartDelta(hashComment, "# TODO"))
        assertEquals(0, builder.getCommentStartDelta(hashComment, "no hash here"))
    }

    fun `test contributes nothing to a non-Alpaca file`() {
        val builder = AlpacaIndexPatternBuilder()
        val file = myFixture.configureByText("plain.txt", "# TODO")

        assertNull(builder.getIndexingLexer(file))
        assertNull(builder.getCommentTokenSet(file))
    }
}
