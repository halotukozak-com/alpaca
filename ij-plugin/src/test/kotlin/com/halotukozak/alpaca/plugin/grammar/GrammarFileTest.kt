package com.halotukozak.alpaca.plugin.grammar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** Matches the exact JSON shapes written by alpaca's internal.lexer/parser.GrammarExport. */
class GrammarFileTest {
    @Test
    fun `reads a tokens json file written by Alpaca's compile-time export`() {
        val json = versionedJson("""[{"name":"\\s+","pattern":"\\s+","ignored":true},{"name":"int","pattern":"[0-9]+","ignored":false}]""")
        val path = Files.createTempFile("grammar-file-test", ".tokens.json")
        try {
            Files.writeString(path, json)

            val result = LexerGrammarFile.read(path)

            assertEquals(
                VersionedExport.Compatible(
                    listOf(
                        TokenSpec(name = "\\s+", pattern = "\\s+", ignored = true),
                        TokenSpec(name = "int", pattern = "[0-9]+", ignored = false),
                    ),
                ),
                result,
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `reads a productions json file written by Alpaca's compile-time export`() {
        val json =
            versionedJson(
                """[{"lhs":"root","rhs":[{"kind":"terminal","name":"T"}],"name":null},""" +
                    """{"lhs":"Expr","rhs":[{"kind":"nonterminal","name":"Expr"},{"kind":"terminal","name":"+"}],"name":"plus"}]""",
            )
        val path = Files.createTempFile("grammar-file-test", ".productions.json")
        try {
            Files.writeString(path, json)

            val result = ParserGrammarFile.read(path)

            assertEquals(
                VersionedExport.Compatible(
                    listOf(
                        ProductionSpec(lhs = "root", rhs = listOf(SymbolSpec("terminal", "T")), name = null),
                        ProductionSpec(
                            lhs = "Expr",
                            rhs = listOf(SymbolSpec("nonterminal", "Expr"), SymbolSpec("terminal", "+")),
                            name = "plus",
                        ),
                    ),
                ),
                result,
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `reads a table json file written by Alpaca's compile-time export`() {
        val json =
            versionedJson(
                """{"endOfInput":"end","start":"S'","states":[""" +
                    """[{"symbol":{"kind":"terminal","name":"int"},"action":{"type":"shift","state":1}}],""" +
                    """[{"symbol":{"kind":"terminal","name":"end"},""" +
                    """"action":{"type":"reduce","production":{"lhs":"root","rhs":[],"name":null}}}]""" +
                    """]}""",
            )
        val path = Files.createTempFile("grammar-file-test", ".table.json")
        try {
            Files.writeString(path, json)

            val result = ParserTableFile.read(path)

            assertEquals(
                VersionedExport.Compatible(
                    ParseTableSpec(
                        endOfInput = "end",
                        start = "S'",
                        states =
                            listOf(
                                listOf(TableEntry(SymbolSpec("terminal", "int"), ActionSpec.Shift(1))),
                                listOf(
                                    TableEntry(
                                        SymbolSpec("terminal", "end"),
                                        ActionSpec.Reduce(ProductionSpec("root", emptyList(), null)),
                                    ),
                                ),
                            ),
                    ),
                ),
                result,
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `reports the found version for a mismatched version number`() {
        val path = Files.createTempFile("grammar-file-test", ".tokens.json")
        try {
            Files.writeString(path, versionedJson("""[]""", version = 99))

            assertEquals(VersionedExport.Incompatible(99), LexerGrammarFile.read(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `treats a pre-envelope file with no version key at all as version 0`() {
        val path = Files.createTempFile("grammar-file-test", ".tokens.json")
        try {
            // The exact shape written before this envelope existed: a bare JSON array.
            Files.writeString(path, """[{"name":"kw","pattern":"let","ignored":false}]""")

            assertEquals(VersionedExport.Incompatible(0), LexerGrammarFile.read(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
