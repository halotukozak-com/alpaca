package com.halotukozak.alpaca.plugin.formatting

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val PARSER_ID = exportedGrammarId("MathTest", "MathParser")

/**
 * Exercises the real "Reformat Code" editor action against [AlpacaFormattingModelBuilder],
 * registered for the real `MathParser` grammar (see the exported `MathTest.CalcLexer@L<line>.tokens.json`
 * for its token shapes).
 */
class AlpacaFormattingModelBuilderTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(GrammarAssociation(extension = "calc", lexerGrammarId = LEXER_ID, parserGrammarId = PARSER_ID))
        runWriteAction { AlpacaFileTypeRegistrar.ensureRegistered("calc", LEXER_ID) }
    }

    private fun reformat(text: String): String {
        myFixture.configureByText("test.calc", text)
        myFixture.performEditorAction("ReformatCode")
        return myFixture.editor.document.text
    }

    fun `test leaves an operator's own spacing untouched`() {
        // Nothing classifies "+" -- there is no generic "one space between tokens" fallback, so
        // whatever spacing the user typed around an unclassified token survives verbatim.
        assertEquals("1+2", reformat("1+2"))
        assertEquals("1    +    2", reformat("1    +    2"))
    }

    fun `test removes space just inside a paren pair`() {
        assertEquals("(1 + 2)", reformat("( 1 + 2 )"))
    }

    fun `test removes space before a comma but keeps whatever was after it`() {
        // The " " between atan2 and "(" is unclassified and survives; the " " before the comma is
        // removed; the "  " after the comma is unclassified and survives as-is.
        assertEquals("atan2 (1,  1)", reformat("atan2 ( 1 ,  1 )"))
    }

    fun `test indents a wrapped paren group by one level and keeps the wrap`() {
        // "tan(" (no space) is left alone, same as any other unclassified gap -- only the
        // parens' own interior spacing and the wrapped Expr's indent are touched.
        val result =
            reformat(
                """
                tan(
                (1 + 2) * (3 - 4)
                )
                """.trimIndent(),
            )

        assertEquals(
            """
            tan(
                (1 + 2) * (3 - 4)
            )
            """.trimIndent(),
            result,
        )
    }

    fun `test pressing Enter inside a paren group indents the new line one level`() {
        // Drives AlpacaBlock.getChildAttributes: the caret sits between "(" and ")", so the fresh
        // line is opened at one indent level -- the same span logic ReformatCode uses.
        myFixture.configureByText("test.calc", "sin(<caret>)")
        myFixture.type("\n")

        assertEquals("sin(\n    \n)", myFixture.editor.document.text)
    }

    fun `test pressing Enter outside any bracket stays at column zero`() {
        myFixture.configureByText("test.calc", "1 + 2<caret>")
        myFixture.type("\n")

        assertEquals("1 + 2\n", myFixture.editor.document.text)
    }

    fun `test an unresolved grammar leaves the file untouched`() {
        // The extension is still registered as an Alpaca file type, but no association names a
        // grammar for it -- the same situation a stale or mistyped Settings entry would leave.
        // FormattingRoles.of("<unresolved>", emptyList()) classifies nothing, so every gap is
        // left alone; this only confirms that resolving no grammar doesn't crash the formatter.
        AlpacaSettingsState.getInstance(project).associations = mutableListOf()

        assertEquals("( 1 , 1 )", reformat("( 1 , 1 )"))
    }
}
