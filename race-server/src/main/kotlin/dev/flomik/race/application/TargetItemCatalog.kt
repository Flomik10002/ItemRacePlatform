package dev.flomik.race.application

import java.nio.file.Files
import java.nio.file.Path

object TargetItemCatalog {
    private const val DEFAULT_RESOURCE_NAME = "items.txt"
    private val ITEM_ID_PATTERN = Regex("^[a-z0-9_.-]+:[a-z0-9_/.-]+$")

    fun loadFromClasspath(resourceName: String = DEFAULT_RESOURCE_NAME): List<String> {
        val stream = TargetItemCatalog::class.java.classLoader.getResourceAsStream(resourceName)
            ?: throw IllegalStateException("Target items resource '$resourceName' was not found")

        stream.bufferedReader().use { reader ->
            return parse(reader.readLines())
        }
    }

    fun loadFromFile(path: Path): List<String> {
        if (!Files.exists(path)) {
            throw IllegalStateException("Target items file '$path' was not found")
        }
        if (!Files.isRegularFile(path)) {
            throw IllegalStateException("Target items path '$path' is not a regular file")
        }

        Files.newBufferedReader(path).use { reader ->
            return parse(reader.readLines())
        }
    }

    fun parse(lines: List<String>): List<String> {
        val parsed = lines.asSequence()
            .mapIndexedNotNull { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) {
                    null
                } else {
                    index + 1 to line
                }
            }
            .map { (lineNumber, itemId) ->
                if (!ITEM_ID_PATTERN.matches(itemId)) {
                    throw IllegalStateException("Invalid item id '$itemId' in items catalog at line $lineNumber")
                }
                itemId
            }
            .distinct()
            .toList()

        if (parsed.isEmpty()) {
            throw IllegalStateException("Target items catalog is empty")
        }
        return parsed
    }
}

