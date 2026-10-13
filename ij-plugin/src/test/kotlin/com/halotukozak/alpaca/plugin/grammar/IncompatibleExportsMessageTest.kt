package com.halotukozak.alpaca.plugin.grammar

import org.junit.Assert.assertEquals
import org.junit.Test

class IncompatibleExportsMessageTest {
    @Test
    fun `names the Alpaca release of an older export and says to rebuild or update Alpaca`() {
        assertEquals(
            "This plugin reads export format $CURRENT_EXPORT_FORMAT_VERSION.\n" +
                "L@L1.tokens.json: format 3, written by Alpaca 1.0.0-RC3 or earlier\n" +
                "Rebuild the project to regenerate the older exports. If they stay the same, the project uses an Alpaca " +
                "release older than this plugin reads: update Alpaca.",
            incompatibleExportsMessage(listOf(IncompatibleExport("L@L1.tokens.json", 3))),
        )
    }

    @Test
    fun `says to update the plugin for a newer export`() {
        assertEquals(
            "This plugin reads export format $CURRENT_EXPORT_FORMAT_VERSION.\n" +
                "L@L1.tokens.json: format 99, written by Alpaca 2.0.0\n" +
                "Update the Alpaca plugin to read the newer exports.",
            incompatibleExportsMessage(listOf(IncompatibleExport("L@L1.tokens.json", 99, "2.0.0"))),
        )
    }

    @Test
    fun `gives both advices when exports are older and newer`() {
        val message =
            incompatibleExportsMessage(
                listOf(IncompatibleExport("Old.L@L1.tokens.json", 0), IncompatibleExport("New.L@L1.tokens.json", 99, "2.0.0")),
            )

        assertEquals(
            listOf(
                "This plugin reads export format $CURRENT_EXPORT_FORMAT_VERSION.",
                "Old.L@L1.tokens.json: format 0, written by an Alpaca release before 0.3.1",
                "New.L@L1.tokens.json: format 99, written by Alpaca 2.0.0",
                "Rebuild the project to regenerate the older exports. If they stay the same, the project uses an Alpaca " +
                    "release older than this plugin reads: update Alpaca.",
                "Update the Alpaca plugin to read the newer exports.",
            ),
            message.lines(),
        )
    }
}
