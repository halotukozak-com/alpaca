package com.halotukozak.alpaca.plugin.completion

import com.halotukozak.alpaca.plugin.grammar.GrammarDirectory
import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Drives [AlpacaCompletionEngine] against the real, already conflict-resolved `MathParser` table
 * exported by the Alpaca repo's own test suite (see [com.halotukozak.alpaca.plugin.parser.AlpacaLrDriverTest]
 * for the same real-grammar setup), checking that the suggestions it offers at a few real
 * positions match what's actually syntactically valid there, not the full grammar's exact literal
 * set (which would make this test as fragile as the grammar itself).
 */
class AlpacaCompletionEngineTest {
    companion object {
        private val GRAMMAR_ID = exportedGrammarId("MathTest", "MathParser")
        private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
    }

    private fun suggest(prefixText: String): List<String> {
        val dir = Path.of("/tmp/alpaca-grammar-export")
        val grammars = GrammarDirectory.scan(dir)
        val lexerGrammar = grammars.lexers.first { it.id == LEXER_ID }
        val parserGrammar = grammars.parsers.first { it.id == GRAMMAR_ID }
        return AlpacaCompletionEngine(parserGrammar.table).suggestNextLiterals(LEXER_ID, lexerGrammar.tokens, prefixText)
    }

    @Test
    fun `suggests tokens that can start a new expression after a binary operator`() {
        val suggestions = suggest("1 + ")

        assertTrue("expected 'sin' (a function call can start an expression): $suggestions", "sin" in suggestions)
        assertTrue("expected '(' (a parenthesized expression can start an expression): $suggestions", "(" in suggestions)
        assertTrue("expected 'pi' (a named constant can start an expression): $suggestions", "pi" in suggestions)
        assertTrue("did not expect ')' right after a binary operator: $suggestions", ")" !in suggestions)
    }

    @Test
    fun `does not suggest closing a call before its argument is given`() {
        val suggestions = suggest("sin(")

        assertTrue("expected '(' (a nested call can start an expression): $suggestions", "(" in suggestions)
        assertTrue("did not expect ')' before any argument was given: $suggestions", ")" !in suggestions)
    }

    @Test
    fun `suggests closing a call and binary operators once its argument is complete`() {
        val suggestions = suggest("sin(1")

        assertTrue("expected ')' to close the call: $suggestions", ")" in suggestions)
        assertTrue("expected '+' to continue as a binary expression: $suggestions", "+" in suggestions)
        assertTrue("did not expect 'sin' right after a complete expression: $suggestions", "sin" !in suggestions)
    }

    @Test
    fun `suggests nothing for input that does not parse`() {
        assertTrue(suggest(")))").isEmpty())
    }
}
