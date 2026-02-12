package dev.flomik.race.application

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TargetItemCatalogTest {
    @Test
    fun parseIgnoresCommentsBlankLinesAndDuplicates() {
        val parsed = TargetItemCatalog.parse(
            listOf(
                "# comment",
                " minecraft:diamond ",
                "minecraft:emerald # inline comment",
                "",
                "minecraft:diamond",
            ),
        )

        assertEquals(listOf("minecraft:diamond", "minecraft:emerald"), parsed)
    }

    @Test
    fun parseRejectsInvalidIds() {
        val error = assertFailsWith<IllegalStateException> {
            TargetItemCatalog.parse(listOf("diamond"))
        }

        assertTrue(error.message?.contains("Invalid item id") == true)
    }

    @Test
    fun parseRejectsEmptyCatalog() {
        val error = assertFailsWith<IllegalStateException> {
            TargetItemCatalog.parse(listOf("# no data", " "))
        }

        assertEquals("Target items catalog is empty", error.message)
    }

    @Test
    fun loadFromFileReadsCatalog() {
        val file = Files.createTempFile("item-race-items-", ".txt")
        try {
            Files.writeString(
                file,
                """
                # custom pool
                minecraft:apple
                minecraft:golden_apple
                """.trimIndent(),
            )

            val parsed = TargetItemCatalog.loadFromFile(file)
            assertEquals(listOf("minecraft:apple", "minecraft:golden_apple"), parsed)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}

