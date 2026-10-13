package com.halotukozak.alpaca.plugin.grammar

import com.halotukozak.alpaca.plugin.settings.AlpacaSettingsState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-project cache of [GrammarDirectory.scan] over the configured export directory.
 *
 * Without this, the highlighter factory, parser definition, commenter and completion contributor
 * each re-list the export directory and re-parse every `*.json` file in it on every editor
 * operation. They now all go through [exportedGrammars]/[resolveForFile], which scan once and reuse
 * the result until [invalidate].
 *
 * The cache is dropped by:
 * - [GrammarExportChangeListener], when a file under the export directory changes on disk;
 * - the Settings pane, when the export directory or the extension mappings change.
 *
 * [syncWatchedRoots] keeps the platform's file watcher pointed at the export directory, which
 * normally sits outside the project's own content roots, so those on-disk changes actually surface
 * as VFS events.
 */
@Service(Service.Level.PROJECT)
class GrammarService(
    private val project: Project,
) {
    private val cache = AtomicReference<Snapshot?>()
    private val watchRequest = AtomicReference<LocalFileSystem.WatchRequest?>()

    private class Snapshot(
        val directory: String,
        val grammars: ExportedGrammars,
    )

    private fun exportDirectory(): String? = AlpacaSettingsState.getInstance(project).resolvedExportDirectory()

    /** True when [path] is the export directory or a file inside it. */
    fun isUnderExportDirectory(path: String): Boolean {
        val directory = exportDirectory() ?: return false
        return runCatching { Path.of(path).startsWith(Path.of(directory)) }.getOrDefault(false)
    }

    /** Every grammar in the export directory, scanned once and reused until [invalidate]. */
    fun exportedGrammars(): ExportedGrammars {
        val directory = exportDirectory() ?: return EMPTY

        cache.get()?.let { if (it.directory == directory) return it.grammars }

        val scanned = GrammarDirectory.scan(Path.of(directory))
        cache.set(Snapshot(directory, scanned))
        if (scanned.incompatible.isNotEmpty()) notifyIncompatible(scanned.incompatible)
        return scanned
    }

    /** One balloon per fresh (non-cached) scan that found a version it doesn't understand -- the
     *  cache means this can't fire more often than an actual on-disk change or Settings edit. */
    private fun notifyIncompatible(incompatible: List<IncompatibleExport>) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Alpaca")
        group
            .createNotification(
                "Alpaca grammar export format mismatch",
                incompatibleExportsMessage(incompatible),
                NotificationType.WARNING,
            ).notify(project)
    }

    /** The grammar [virtualFile] resolves to via the Settings extension-to-grammar mappings: always
     *  a lexer, and a parser when the mapping names one and it was found in the export. */
    fun resolveForFile(virtualFile: VirtualFile): ResolvedGrammar? {
        val settings = AlpacaSettingsState.getInstance(project)
        val extension = virtualFile.extension ?: return null
        val association = settings.associationForExtension(extension) ?: return null

        val grammars = exportedGrammars()
        val tokens = grammars.lexers.firstOrNull { it.id == association.lexerGrammarId }?.tokens ?: return null
        val parserGrammar =
            association.parserGrammarId
                .takeIf { it.isNotBlank() }
                ?.let { parserId -> grammars.parsers.firstOrNull { it.id == parserId } }

        return ResolvedGrammar(association.lexerGrammarId, tokens, parserGrammar)
    }

    /** Drops the cached scan; the next [exportedGrammars] call re-reads the directory. */
    fun invalidate() {
        cache.set(null)
    }

    /**
     * Points the platform's file watcher at the current export directory, replacing any previous
     * watch. Call on project open and after a Settings change: the export directory is usually
     * outside the project, so without a watch the platform never refreshes it and
     * [GrammarExportChangeListener] never fires.
     */
    fun syncWatchedRoots() {
        val directory = exportDirectory()
        val updated =
            LocalFileSystem.getInstance().replaceWatchedRoots(
                watchRequest.get()?.let(::listOf).orEmpty(),
                directory?.let(::listOf).orEmpty(),
                null,
            )
        watchRequest.set(updated.firstOrNull())
    }

    companion object {
        private val EMPTY = ExportedGrammars(emptyList(), emptyList())

        fun getInstance(project: Project): GrammarService = project.service()
    }
}

/** What a notification says about export files this plugin can't read: which Alpaca release wrote each one, and
 *  whether the project or the plugin needs updating. */
internal fun incompatibleExportsMessage(incompatible: List<IncompatibleExport>): String {
    val files =
        incompatible.joinToString("\n") { export ->
            val writer =
                when {
                    export.alpacaVersion != null -> "written by Alpaca ${export.alpacaVersion}"
                    export.foundVersion == 0 -> "written by an Alpaca release before 0.3.1"
                    else -> "written by Alpaca 1.0.0-RC3 or earlier"
                }
            "${export.fileName}: format ${export.foundVersion}, $writer"
        }
    val older =
        "Rebuild the project to regenerate the older exports. If they stay the same, the project uses an Alpaca " +
            "release older than this plugin reads: update Alpaca."
    val newer = "Update the Alpaca plugin to read the newer exports."
    val advice =
        listOfNotNull(
            older.takeIf { incompatible.any { it.foundVersion < CURRENT_EXPORT_FORMAT_VERSION } },
            newer.takeIf { incompatible.any { it.foundVersion > CURRENT_EXPORT_FORMAT_VERSION } },
        )
    return "This plugin reads export format $CURRENT_EXPORT_FORMAT_VERSION.\n$files\n${advice.joinToString("\n")}"
}
