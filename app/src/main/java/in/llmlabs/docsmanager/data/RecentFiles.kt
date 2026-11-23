package `in`.llmlabs.docsmanager.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(val uri: String, val name: String, val openedAt: Long)

/** The Documents opened lately, newest first. Only names and links are kept, never contents. */
class RecentFiles(context: Context) {
    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun list(): List<RecentFile> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RecentFile(o.getString("uri"), o.getString("name"), o.getLong("at"))
        }
    }

    fun add(uri: String, name: String) = save(listOf(RecentFile(uri, name, System.currentTimeMillis())) + list().filter { it.uri != uri })

    fun remove(uri: String) = save(list().filter { it.uri != uri })

    private fun save(items: List<RecentFile>) {
        val arr = JSONArray()
        items.take(MAX).forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("at", it.openedAt)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "items"
        const val MAX = 30
    }
}
