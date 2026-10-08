package com.halotukozak.alpaca.plugin.editing

import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.halotukozak.alpaca.plugin.settings.GrammarAssociation
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private val JSON_LEXER_ID = exportedGrammarId("JsonTest", "JsonLexer")
private val JSON_PARSER_ID = exportedGrammarId("JsonTest", "JsonE2EParser")
private val CALC_LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
private val CALC_PARSER_ID = exportedGrammarId("MathTest", "MathParser")

/**
 * `JsonTest`'s grammar has a `"(\\.|[^"])*"` String rule; `MathTest`'s has no string-shaped rule
 * at all. Exercises [AlpacaQuoteHandler] through the real typed-quote editor action against both,
 * with no grammar-specific code.
 */
class AlpacaQuoteHandlerTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val settings = AlpacaSettingsState.getInstance(project)
        settings.exportDirectory = "/tmp/alpaca-grammar-export"
        settings.associations =
            mutableListOf(
                GrammarAssociation(extension = "json", lexerGrammarId = JSON_LEXER_ID, parserGrammarId = JSON_PARSER_ID),
                GrammarAssociation(extension = "calc", lexerGrammarId = CALC_LEXER_ID, parserGrammarId = CALC_PARSER_ID),
            )
        runWriteAction {
            AlpacaFileTypeRegistrar.ensureRegistered("json", JSON_LEXER_ID)
            AlpacaFileTypeRegistrar.ensureRegistered("calc", CALC_LEXER_ID)
        }
    }

    private fun typeQuote(
        fileName: String,
        text: String,
    ): String {
        myFixture.configureByText(fileName, text)
        myFixture.type('"')
        return myFixture.editor.document.text
    }

    fun `test typing a quote auto-inserts the closing quote`() {
        assertEquals("[\"\"]", typeQuote("test.json", "[<caret>]"))
        assertEquals("caret sits between the inserted pair", 2, myFixture.editor.caretModel.offset)
    }

    fun `test typing a quote over the existing closing quote steps past it`() {
        assertEquals("[\"\"]", typeQuote("test.json", "[\"<caret>\"]"))
        assertEquals(3, myFixture.editor.caretModel.offset)
    }

    fun `test no auto-pair for a grammar without string tokens`() {
        // MathTest has no string-shaped rule -> quoteChars is empty -> the handler is inert and
        // the lone quote is just inserted as typed.
        assertEquals("(\")", typeQuote("test.calc", "(<caret>)"))
    }

    fun `test typing a quote in the middle of a string just inserts it`() {
        // Caret is inside the literal but not on either boundary: neither an opening nor a closing
        // quote, and the char under the caret isn't a quote, so nothing special happens.
        assertEquals("[\"fo\"o\"]", typeQuote("test.json", "[\"fo<caret>o\"]"))
    }

    fun `test typing a quote at the very end of the file still auto-pairs`() {
        // No token to the right of the caret at all -- exercises the "offset is past the end of
        // the document" guard in startsStringLiteral for the lookahead at the typed position.
        assertEquals("\"\"", typeQuote("test.json", "<caret>"))
    }

    fun `test typing an opening quote before a stray closing quote completes the literal`() {
        // The typed quote plus the pre-existing trailing quote together already form a *closed*
        // literal ("abc") -- hasNonClosedLiteral's scan must recognize that and skip past it
        // rather than reporting the line as having an unclosed string, so no second closing quote
        // gets inserted.
        assertEquals("[\"abc\"]", typeQuote("test.json", "[<caret>abc\"]"))
    }

    fun `test isInsideLiteral reports true only inside a string token`() {
        myFixture.configureByText("test.json", "[\"abc\"]")
        val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(0)
        val handler = AlpacaQuoteHandler()

        assertFalse(handler.isInsideLiteral(iterator)) // positioned on "["
        iterator.advance()
        assertTrue(handler.isInsideLiteral(iterator)) // positioned on "abc"
    }
}
