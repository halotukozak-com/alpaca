package com.halotukozak.alpaca.plugin.startup

import com.halotukozak.alpaca.plugin.grammar.GrammarService
import com.halotukozak.alpaca.plugin.lexer.AlpacaFileTypeRegistrar
import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * On project open: registers a file type for each configured extension-to-grammar mapping, points
 * the platform's file watcher at the export directory (so [GrammarService] hears later on-disk
 * changes), and warms the grammar cache while logging what it found.
 */
class AlpacaGrammarStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = AlpacaSettingsState.getInstance(project)
        if (settings.resolvedExportDirectory() == null) return

        edtWriteAction {
            settings.associations.forEach { AlpacaFileTypeRegistrar.ensureRegistered(it.extension, it.lexerGrammarId) }
        }

        val service = GrammarService.getInstance(project)
        service.syncWatchedRoots()

        val grammars = service.exportedGrammars()
        thisLogger().info(
            "Alpaca: found ${grammars.lexers.size} lexer(s) and ${grammars.parsers.size} parser(s): " +
                "lexers=${grammars.lexers.map { it.id }}, parsers=${grammars.parsers.map { it.id }}",
        )
    }
}
