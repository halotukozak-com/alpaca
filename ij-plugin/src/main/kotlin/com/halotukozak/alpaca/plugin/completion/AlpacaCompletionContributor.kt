package com.halotukozak.alpaca.plugin.completion

import com.halotukozak.alpaca.plugin.grammar.resolveGrammarForFile
import com.halotukozak.alpaca.plugin.lexer.AlpacaLanguage
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/** The run of characters immediately before the caret that could be the word still being typed:
 *  the actual completion prefix, excluded from what's replayed through the grammar's parse table
 *  since it isn't a complete token yet. */
private val PARTIAL_WORD = Regex("[A-Za-z0-9_]*$")

/** Offers every literal-text terminal ([AlpacaCompletionEngine]) valid at the caret, for whichever
 *  grammar the current file resolves to in Settings | Tools | Alpaca. Registered once for the
 *  shared [AlpacaLanguage]. */
class AlpacaCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(AlpacaLanguage), AlpacaCompletionProvider())
    }

    // The platform's default dummy identifier ("IntellijIdeaRulezzz") would otherwise land at the
    // caret in [CompletionParameters.getEditor]'s document, corrupting both the partial-word prefix
    // and the text replayed through the grammar's parse table.
    override fun beforeCompletion(context: CompletionInitializationContext) {
        context.dummyIdentifier = ""
    }
}

private class AlpacaCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val virtualFile = parameters.originalFile.virtualFile ?: return
        val resolved = resolveGrammarForFile(parameters.originalFile.project, virtualFile) ?: return
        val table = resolved.parserGrammar?.table ?: return

        val offset = parameters.editor.caretModel.offset
        val textBeforeCaret =
            parameters.editor.document.charsSequence
                .subSequence(0, offset)
        val partialWord = PARTIAL_WORD.find(textBeforeCaret)!!.value
        val basePrefixText = textBeforeCaret.subSequence(0, textBeforeCaret.length - partialWord.length).toString()

        val suggestions = AlpacaCompletionEngine(table).suggestNextLiterals(resolved.lexerId, resolved.tokens, basePrefixText)
        val resultWithPrefix = if (partialWord.isEmpty()) result else result.withPrefixMatcher(partialWord)
        for (text in suggestions) resultWithPrefix.addElement(LookupElementBuilder.create(text))
    }
}
