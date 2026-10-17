package com.halotukozak.alpaca.plugin.lexer

import com.intellij.openapi.fileTypes.FileTypeManager

/**
 * Registers one [AlpacaFileType] per grammar's extension with the platform's
 * [FileTypeManager]: the set of grammars, and which extension each maps to,
 * is only known at runtime from Settings, not at plugin-compile time, so
 * they can't be declared in plugin.xml the way a normal language plugin's
 * single file type would be.
 *
 * Callers must invoke [ensureRegistered] from inside a write action on the EDT
 * (`edtWriteAction`, not `writeAction`): the `fileTypesChanged` listeners it
 * fires touch EDT-only UI.
 */
object AlpacaFileTypeRegistrar {
    fun ensureRegistered(
        extension: String,
        grammarId: String,
    ) {
        val fileType = AlpacaFileTypes.forGrammar(grammarId)
        FileTypeManager.getInstance().associateExtension(fileType, extension)
    }
}
