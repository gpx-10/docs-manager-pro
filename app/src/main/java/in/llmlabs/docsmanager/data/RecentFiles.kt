package `in`.llmlabs.docsmanager.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Document opened lately. [uri] is the link used to reopen it: the original link when the app
 * holds lasting access to it, otherwise a private copy kept by the app (see [DocumentFiles.keepCopy]).
 */
data class RecentFile(
    val uri: String,
    val name: String,
    val openedAt: Long,
    val size: Long = -1,
    /** 0-based page the reader was on when they left, so the file reopens there. */
    val lastPage: Int = 0,
)

/** The Documents opened lately, newest first. Only names, sizes and links are kept, never contents. */
class RecentFiles(private val context: Context) {
    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun list(): List<RecentFile> {
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            RecentFile(o.getString("uri"), o.getString("name"), o.getLong("at"), o.optLong("size", -1), o.optInt("page", 0))
        }
    }

    fun find(uri: String): RecentFile? = list().firstOrNull { it.uri == uri }

    /**
     * Puts the file at the top. The same file reached by another link (same name and size)
     * replaces its older entry instead of appearing twice.
     */
    fun add(uri: String, name: String, size: Long) {
        val old = list()
        val previous = old.firstOrNull { it.uri == uri } ?: old.firstOrNull { it.isSameFile(name, size) }
        val entry = RecentFile(uri, name, System.currentTimeMillis(), size, previous?.lastPage ?: 0)
        save(listOf(entry) + old.filter { it.uri != uri && !it.isSameFile(name, size) })
    }

    fun setLastPage(uri: String, page: Int) = save(list().map { if (it.uri == uri) it.copy(lastPage = page) else it })

    fun remove(uri: String) = save(list().filter { it.uri != uri })

    /** Puts a removed entry back where it was (for Undo). */
    fun restore(file: RecentFile, position: Int) {
        val items = list().filter { it.uri != file.uri }.toMutableList()
        items.add(position.coerceIn(0, items.size), file)
        save(items)
    }

    fun clear() = save(emptyList())

    private fun RecentFile.isSameFile(otherName: String, otherSize: Long) = size >= 0 && size == otherSize && name == otherName

    private fun save(items: List<RecentFile>) {
        val kept = items.take(MAX)
        val arr = JSONArray()
        kept.forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("at", it.openedAt).put("size", it.size).put("page", it.lastPage))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
        DocumentFiles.deleteUnusedCopies(context, kept.map { it.uri }.toSet())
    }

    private companion object {
        const val KEY = "items"
        const val MAX = 30
    }
}
