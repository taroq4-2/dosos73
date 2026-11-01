package com.os73.dosos73

import android.content.Context
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A format preference, never a source selector, URL, title or automatic download. */
internal data class DownloadPreset(
    val id: String,
    val name: String,
    val kind: Kind,
    val extension: String,
    val expectedHeight: Int?,
    val quality: String?,
    val tree: String?,
) {
    /** Only an available exact format/height (or non-height quality) may be selected. */
    fun match(choices: List<Choice>): Choice? = choices.firstOrNull { choice ->
        choice.kind == kind && choice.extension.lowercase(Locale.ROOT) == extension &&
            choice.expectedHeight == expectedHeight &&
            (if (kind == Kind.AUDIO && extension == "mp3") true
             else expectedHeight != null || choice.quality == quality)
    }
}

/** Small, versioned encrypted preferences. Mutations preserve unreadable/unknown records. */
internal class DownloadPresets(context: Context) {
    private val journal = PrivateJournal(context, FILE_NAME, MAX_BYTES)

    fun read(): List<DownloadPreset> = synchronized(lock) {
        journal.readOrNull()?.let(::decode) ?: emptyList()
    }

    /** Saving an existing name updates it without changing its identity. */
    fun save(name: String, choice: Choice, tree: String?): List<DownloadPreset> = synchronized(lock) {
        val rows = read().toMutableList()
        val cleanName = name.trim()
        val index = rows.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
        val preset = fromChoice(cleanName, choice, tree, rows.getOrNull(index)?.id ?: UUID.randomUUID().toString())
        if (index >= 0) rows[index] = preset else {
            require(rows.size < MAX_PRESETS) { "Preset limit reached" }
            rows.add(preset)
        }
        journal.write(encode(rows))
        rows.toList()
    }

    fun delete(id: String): List<DownloadPreset> = synchronized(lock) {
        val rows = read().filterNot { it.id == id }
        journal.write(encode(rows))
        rows
    }

    companion object {
        internal const val FILE_NAME = "download-presets.json"
        internal const val MAX_BYTES = 64 * 1024
        internal const val MAX_PRESETS = 20
        internal const val MAX_NAME = 64
        private const val MAX_TREE = 1024
        private val lock = Any()

        internal fun validName(name: String) = name.isNotBlank() && name.length <= MAX_NAME &&
            name.none { Character.isISOControl(it) }

        internal fun fromChoice(name: String, choice: Choice, tree: String?, id: String = UUID.randomUUID().toString()): DownloadPreset {
            val extension = choice.extension.lowercase(Locale.ROOT)
            val height = choice.expectedHeight
            val quality = when {
                choice.kind == Kind.AUDIO && extension == "mp3" -> "MP3 V0"
                height != null -> null
                else -> choice.quality
            }
            return DownloadPreset(id, name.trim(), choice.kind, extension, height, quality, tree).also(::validate)
        }

        private fun validate(row: DownloadPreset) {
            require(UUID.fromString(row.id).toString() == row.id)
            require(validName(row.name) && row.name == row.name.trim())
            require(row.extension.matches(Regex("[a-z0-9]{1,16}")))
            require(row.expectedHeight == null || (row.kind == Kind.VIDEO && row.expectedHeight in 1..100000))
            require(if (row.expectedHeight != null) row.quality == null
                else !row.quality.isNullOrBlank() && row.quality.length <= 192 && row.quality.none { Character.isISOControl(it) })
            require(row.kind != Kind.AUDIO || row.extension != "mp3" || row.quality == "MP3 V0")
            require(row.tree == null || (row.tree.length <= MAX_TREE &&
                row.tree.startsWith("content://") && row.tree.none { Character.isISOControl(it) }))
        }

        internal fun encode(rows: List<DownloadPreset>): String {
            require(rows.size <= MAX_PRESETS)
            require(rows.map { it.id }.toSet().size == rows.size)
            require(rows.map { it.name.lowercase(Locale.ROOT) }.toSet().size == rows.size)
            val array = JSONArray()
            rows.forEach { row ->
                validate(row)
                array.put(JSONObject().put("id", row.id).put("name", row.name)
                    .put("kind", row.kind.name).put("extension", row.extension)
                    .put("height", row.expectedHeight ?: JSONObject.NULL)
                    .put("quality", row.quality ?: JSONObject.NULL).put("tree", row.tree ?: JSONObject.NULL))
            }
            return JSONObject().put("version", 1).put("presets", array).toString().also {
                require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES - 32)
            }
        }

        internal fun decode(text: String): List<DownloadPreset> {
            require(text.length <= MAX_BYTES - 32 && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES - 32)
            val root = JSONObject(text)
            require(root.getInt("version") == 1)
            val array = root.getJSONArray("presets")
            require(array.length() <= MAX_PRESETS)
            val rows = (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                DownloadPreset(row.getString("id"), row.getString("name"), Kind.valueOf(row.getString("kind")),
                    row.getString("extension"), if (row.isNull("height")) null else row.getInt("height"),
                    if (row.isNull("quality")) null else row.getString("quality"),
                    if (row.isNull("tree")) null else row.getString("tree")).also(::validate)
            }
            // Reuse all collection invariants, including unique names and bounded encoding.
            encode(rows)
            return rows
        }
    }
}
