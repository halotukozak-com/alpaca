package com.halotukozak.alpaca.plugin.grammar

import org.junit.Assert.assertEquals
import org.junit.Test

class IncompatibleExportsMessageTest {
    private val olderAdvice =
        "Rebuild the project to regenerate the older exports. If they stay the same, the project uses an Alpaca " +
            "release older than this plugin reads: update Alpaca."
    private val newerAdvice = "Update the Alpaca plugin to read the newer exports."

    @Test
    fun `says to rebuild or update Alpaca for an older export`() {
        assertEquals(
            listOf("This plugin reads export format $CURRENT_EXPORT_FORMAT_VERSION.", "L@L1.tokens.json: format 0", olderAdvice),
            incompatibleExportsMessage(listOf(IncompatibleExport("L@L1.tokens.json", 0))).lines(),
        )
    }

    @Test
    fun `says to update the plugin for a newer export`() {
        assertEquals(
            listOf("This plugin reads export format $CURRENT_EXPORT_FORMAT_VERSION.", "L@L1.tokens.json: format 99", newerAdvice),
            incompatibleExportsMessage(listOf(IncompatibleExport("L@L1.tokens.json", 99))).lines(),
        )
    }

    @Test
    fun `gives both advices when exports are older and newer`() {
        assertEquals(
            listOf(
                "This plugin reads export format $CURRENT_EXPORT_FORMAT_VERSION.",
                "Old.L@L1.tokens.json: format 0",
                "New.L@L1.tokens.json: format 99",
                olderAdvice,
                newerAdvice,
            ),
            incompatibleExportsMessage(
                listOf(IncompatibleExport("Old.L@L1.tokens.json", 0), IncompatibleExport("New.L@L1.tokens.json", 99)),
            ).lines(),
        )
    }
}
