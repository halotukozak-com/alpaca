package com.halotukozak.alpaca.plugin.parser

import com.halotukozak.alpaca.plugin.grammar.GrammarDirectory
import com.halotukozak.alpaca.plugin.grammar.TokenSpec
import com.halotukozak.alpaca.plugin.grammar.exportedGrammarId
import com.halotukozak.alpaca.plugin.lexer.ALPACA_BAD_CHARACTER
import com.halotukozak.alpaca.plugin.lexer.AlpacaLexer
import com.halotukozak.alpaca.plugin.lexer.AlpacaTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Drives [AlpacaLrDriver] with [FakeTreeBuilder] against the real `MathParser` grammar exported by
 * `/tmp/alpaca-grammar-export` (see the Alpaca repo's own test suite), tokenized with the real
 * [AlpacaLexer], exercising the whole Kotlin-side pipeline (lex -> table-driven parse -> tree)
 * without needing a full IntelliJ platform test fixture.
 */
class AlpacaLrDriverTest {
    companion object {
        private val GRAMMAR_ID = exportedGrammarId("MathTest", "MathParser")
        private val LEXER_ID = exportedGrammarId("MathTest", "CalcLexer")
    }

    /** Tokenizes [text] with the real lexer, dropping ignored tokens (whitespace/comments), matching
     *  what a real PsiBuilder presents to a parser once ignored tokens are registered as whitespace. */
    private fun tokenize(
        specs: List<TokenSpec>,
        text: String,
    ): List<Pair<String, String>> {
        val specByType = specs.associateBy { AlpacaTokenTypes.forName(GRAMMAR_ID, it.name) }
        val lexer = AlpacaLexer(GRAMMAR_ID, specs)
        lexer.start(text, 0, text.length, 0)
        val tokens = mutableListOf<Pair<String, String>>()
        while (lexer.tokenType != null) {
            val tokenText = text.substring(lexer.tokenStart, lexer.tokenEnd)
            if (lexer.tokenType != ALPACA_BAD_CHARACTER) {
                val spec = specByType.getValue(lexer.tokenType!!)
                if (!spec.ignored) tokens += spec.name to tokenText
            }
            lexer.advance()
        }
        return tokens
    }

    /** Parses [text] and returns the tree under the grammar's own `root -> Expr` wrapper, since these
     *  tests are about the `Expr` shape the driver builds, not that MathParser-specific top rule. */
    private fun parse(text: String): List<FakeNode> {
        val dir = Path.of("/tmp/alpaca-grammar-export")
        val grammars = GrammarDirectory.scan(dir)
        val lexerGrammar = grammars.lexers.first { it.id == LEXER_ID }
        val parserGrammar = grammars.parsers.first { it.id == GRAMMAR_ID }
        assertTrue("expected a real exported table (run mill jvm.test.compile first)", parserGrammar.table.isNotEmpty())

        val tokens = tokenize(lexerGrammar.tokens, text)
        val builder = FakeTreeBuilder(tokens)
        AlpacaLrDriver.forTable(parserGrammar.table).parse(builder)
        assertEquals("expected no parse errors for '$text': ${builder.errors}", emptyList<Any>(), builder.errors)

        val tree = builder.buildTree()
        val root = tree.single() as FakeNode.Composite
        assertEquals("root", root.name)
        return root.children
    }

    @Test
    fun `parses a single number as a leaf-only Expr`() {
        val tree = parse("42")
        assertEquals(
            listOf(FakeNode.Composite("Expr", listOf(FakeNode.Leaf("int", "42")))),
            tree,
        )
    }

    @Test
    fun `parses a binary expression with the operands and operator as three siblings`() {
        val tree = parse("1 + 2")
        assertEquals(
            listOf(
                FakeNode.Composite(
                    "Expr",
                    listOf(
                        FakeNode.Composite("Expr", listOf(FakeNode.Leaf("int", "1"))),
                        FakeNode.Leaf("\\+", "+"),
                        FakeNode.Composite("Expr", listOf(FakeNode.Leaf("int", "2"))),
                    ),
                ),
            ),
            tree,
        )
    }

    @Test
    fun `left-associates a chain of same-precedence operators`() {
        // (1 + 2) - 3, not 1 + (2 - 3): the exported table already encodes this, the driver just follows it.
        val tree = parse("1 + 2 - 3")
        val inner =
            FakeNode.Composite(
                "Expr",
                listOf(
                    FakeNode.Composite("Expr", listOf(FakeNode.Leaf("int", "1"))),
                    FakeNode.Leaf("\\+", "+"),
                    FakeNode.Composite("Expr", listOf(FakeNode.Leaf("int", "2"))),
                ),
            )
        assertEquals(
            listOf(
                FakeNode.Composite(
                    "Expr",
                    listOf(inner, FakeNode.Leaf("-", "-"), FakeNode.Composite("Expr", listOf(FakeNode.Leaf("int", "3")))),
                ),
            ),
            tree,
        )
    }

    @Test
    fun `respects multiplication-over-addition precedence baked into the table`() {
        // 1 + (2 * 3), not (1 + 2) * 3.
        val tree = parse("1 + 2 * 3")
        val product =
            FakeNode.Composite(
                "Expr",
                listOf(
                    FakeNode.Composite("Expr", listOf(FakeNode.Leaf("int", "2"))),
                    FakeNode.Leaf("\\*", "*"),
                    FakeNode.Composite("Expr", listOf(FakeNode.Leaf("int", "3"))),
                ),
            )
        assertEquals(
            listOf(
                FakeNode.Composite(
                    "Expr",
                    listOf(FakeNode.Composite("Expr", listOf(FakeNode.Leaf("int", "1"))), FakeNode.Leaf("\\+", "+"), product),
                ),
            ),
            tree,
        )
    }

    @Test
    fun `nests a parenthesized call's arguments correctly`() {
        val tree = parse("sin(1)")
        assertEquals(
            listOf(
                FakeNode.Composite(
                    "Expr",
                    listOf(
                        FakeNode.Leaf("sin", "sin"),
                        FakeNode.Leaf("\\(", "("),
                        FakeNode.Composite("Expr", listOf(FakeNode.Leaf("int", "1"))),
                        FakeNode.Leaf("\\)", ")"),
                    ),
                ),
            ),
            tree,
        )
    }

    private fun driveRaw(text: String): FakeTreeBuilder {
        val dir = Path.of("/tmp/alpaca-grammar-export")
        val grammars = GrammarDirectory.scan(dir)
        val lexerGrammar = grammars.lexers.first { it.id == LEXER_ID }
        val parserGrammar = grammars.parsers.first { it.id == GRAMMAR_ID }

        val tokens = tokenize(lexerGrammar.tokens, text)
        val builder = FakeTreeBuilder(tokens)
        AlpacaLrDriver.forTable(parserGrammar.table).parse(builder)
        return builder
    }

    @Test
    fun `reports an error and recovers from an unexpected token instead of looping forever`() {
        val builder = driveRaw("1 + )")

        assertTrue("expected at least one reported error", builder.errors.isNotEmpty())
    }

    @Test
    fun `error messages show the offending token's actual text, not its internal terminal name`() {
        // "+" and "int" are two separate rules whose *names* aren't user-facing text; a trailing
        // extra ")" should be reported as ")", not as that terminal's regex pattern "\)".
        val builder = driveRaw("sin(1))")

        assertTrue(
            "expected an error mentioning the literal ')', got: ${builder.errors}",
            builder.errors.any { (message, _) -> message.contains("')'") },
        )
    }

    @Test
    fun `leaves no unresolved markers for incomplete input, so the tree still builds`() {
        // Every one of these is missing something a full expression needs, exactly what happens
        // on every keystroke while a user is still typing. The driver must still leave PsiBuilder
        // with a fully balanced tree (real symptom before the fix: "Unbalanced tree" from
        // PsiBuilderImpl.assertMarkersBalanced, corrupting the file on every edit).
        for (incomplete in listOf("1 +", "sin(1", "sin(", "(", "1 + 2 *")) {
            val builder = driveRaw(incomplete)
            builder.buildTree() // throws ("marker was never done()") if anything was left unresolved
        }
    }
}
