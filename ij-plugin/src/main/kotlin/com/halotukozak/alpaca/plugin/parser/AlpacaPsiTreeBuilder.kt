package com.halotukozak.alpaca.plugin.parser

import com.halotukozak.alpaca.plugin.grammar.TokenSpec
import com.halotukozak.alpaca.plugin.lexer.AlpacaCompositeTypes
import com.halotukozak.alpaca.plugin.lexer.AlpacaTokenTypes
import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * Adapts a real [PsiBuilder] to [TreeBuilder], resolving terminal names against the same
 * [TokenSpec] list the grammar's [com.halotukozak.alpaca.plugin.lexer.AlpacaLexer] was built from.
 *
 * Hides `ignored` tokens (whitespace, comments) via [PsiBuilder.enforceCommentTokens] instead of
 * [com.intellij.lang.ParserDefinition.getWhitespaceTokens]: which rules count as "ignored" is only
 * known dynamically per grammar id, not at the shared [ParserDefinition]'s construction time.
 * Manually skipping via [PsiBuilder.advanceLexer] would also be wrong, not just inconvenient:
 * skipping trailing whitespace before a reduction's [PsiBuilder.Marker.done] call pulls that
 * whitespace inside the just-closed node's range. [PsiBuilder.enforceCommentTokens] leaves the
 * attachment to the platform's own whitespace-binding logic at tree-build time instead.
 */
class AlpacaPsiTreeBuilder(
    private val builder: PsiBuilder,
    private val grammarId: String,
    tokenSpecs: List<TokenSpec>,
) : TreeBuilder<PsiBuilder.Marker> {
    private val terminalNameByType: Map<IElementType, String> =
        tokenSpecs.associate { AlpacaTokenTypes.forName(grammarId, it.name) to it.name }

    init {
        val ignoredTypes = tokenSpecs.asSequence().filter { it.ignored }.map { AlpacaTokenTypes.forName(grammarId, it.name) }
        builder.enforceCommentTokens(TokenSet.create(*ignoredTypes.toList().toTypedArray()))
    }

    override fun currentTerminal(): String? {
        val type = builder.tokenType ?: return null
        return terminalNameByType[type] ?: type.toString()
    }

    override fun currentTokenText(): String = builder.tokenText ?: "<eof>"

    override fun advance() = builder.advanceLexer()

    override fun mark(): PsiBuilder.Marker = builder.mark()

    override fun done(
        marker: PsiBuilder.Marker,
        name: String,
    ) = marker.done(AlpacaCompositeTypes.forName(grammarId, name))

    override fun drop(marker: PsiBuilder.Marker) = marker.drop()

    override fun precede(marker: PsiBuilder.Marker): PsiBuilder.Marker = marker.precede()

    override fun error(message: String) = builder.error(message)
}
