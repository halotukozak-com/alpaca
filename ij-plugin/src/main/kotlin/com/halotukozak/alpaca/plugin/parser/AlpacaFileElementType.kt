package com.halotukozak.alpaca.plugin.parser

import com.halotukozak.alpaca.plugin.grammar.ResolvedGrammar
import com.halotukozak.alpaca.plugin.grammar.resolveGrammarForFile
import com.halotukozak.alpaca.plugin.lexer.AlpacaLanguage
import com.halotukozak.alpaca.plugin.lexer.AlpacaLexer
import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilderFactory
import com.intellij.psi.tree.IFileElementType

/**
 * The single, shared root node type for every Alpaca-defined language's file. Overrides
 * [parseContents] directly instead of the usual
 * [com.intellij.lang.ParserDefinition.createLexer]/`createParser` pair, because those only get a
 * [com.intellij.openapi.project.Project], not a file, and resolving which grammar applies needs
 * the file. The chameleon [ASTNode] gives access to its own containing file.
 */
object AlpacaFileElementType : IFileElementType(AlpacaLanguage) {
    override fun parseContents(chameleon: ASTNode): ASTNode {
        val psi = chameleon.psi ?: error("Bad chameleon: $chameleon has no PSI")
        val project = psi.project
        val virtualFile = psi.containingFile?.originalFile?.virtualFile

        val resolved = virtualFile?.let { resolveGrammarForFile(project, it) }
        val (lexerId, tokens, parserGrammar) =
            resolved ?: ResolvedGrammar("<unresolved>", emptyList(), null)

        val lexer = AlpacaLexer(lexerId, tokens)
        val builder = PsiBuilderFactory.getInstance().createBuilder(project, chameleon, lexer, AlpacaLanguage, chameleon.chars)

        val rootMarker = builder.mark()
        parserGrammar?.table?.let { AlpacaLrDriver.forTable(it).parse(AlpacaPsiTreeBuilder(builder, lexerId, tokens)) }
        // Safety net: make sure the whole chameleon ends up under rootMarker either way, whether the
        // driver ran to EOF or there was no parser grammar to drive at all.
        while (!builder.eof()) builder.advanceLexer()
        rootMarker.done(this)

        return builder.treeBuilt.firstChildNode
    }
}
