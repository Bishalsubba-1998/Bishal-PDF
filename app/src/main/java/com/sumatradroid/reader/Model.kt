// App settings, per-file state (Sumatra's FileState) and file history ("Frequently Read"/recent files).
package com.sumatradroid.reader

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class Settings(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themeName: String
        get() = sp.getString("theme", "Light") ?: "Light"
        set(v) { sp.edit().putString("theme", v).apply() }
    /** Name of the DisplayMode enum constant used for newly opened files. */
    var displayMode: String
        get() = sp.getString("displayMode", DisplayMode.CONTINUOUS.name) ?: DisplayMode.CONTINUOUS.name
        set(v) { sp.edit().putString("displayMode", v).apply() }
    var zoom: Float
        get() = sp.getFloat("zoom", Zoom.FIT_WIDTH)
        set(v) { sp.edit().putFloat("zoom", v).apply() }
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", true)
        set(v) { sp.edit().putBoolean("keepScreenOn", v).apply() }
    var volumeKeys: Boolean
        get() = sp.getBoolean("volumeKeys", false)
        set(v) { sp.edit().putBoolean("volumeKeys", v).apply() }
    var tapZones: Boolean
        get() = sp.getBoolean("tapZones", true)
        set(v) { sp.edit().putBoolean("tapZones", v).apply() }
    /** Sumatra's "document colors follow theme" (FixedPageUI TextColor/BackgroundColor from theme). */
    var followTheme: Boolean
        get() = sp.getBoolean("followTheme", false)
        set(v) { sp.edit().putBoolean("followTheme", v).apply() }
    var invert: Boolean
        get() = sp.getBoolean("invert", false)
        set(v) { sp.edit().putBoolean("invert", v).apply() }
    var grayscale: Boolean
        get() = sp.getBoolean("grayscale", false)
        set(v) { sp.edit().putBoolean("grayscale", v).apply() }
    var brightness: Int
        get() = sp.getInt("brightness", 0)
        set(v) { sp.edit().putInt("brightness", v).apply() }
    var contrast: Int
        get() = sp.getInt("contrast", 0)
        set(v) { sp.edit().putInt("contrast", v).apply() }
    /** Page spacing in dp (Sumatra: FixedPageUI.PageSpacing). */
    var pageSpacing: Int
        get() = sp.getInt("pageSpacing", 6)
        set(v) { sp.edit().putInt("pageSpacing", v).apply() }
    /** Font size (em, pt) for reflowable documents (EPUB, MOBI, FB2). */
    var ebookFontSize: Float
        get() = sp.getFloat("ebookFontSize", 12f)
        set(v) { sp.edit().putFloat("ebookFontSize", v).apply() }
    var rememberState: Boolean
        get() = sp.getBoolean("rememberState", true)
        set(v) { sp.edit().putBoolean("rememberState", v).apply() }
    var showPageInfo: Boolean
        get() = sp.getBoolean("showPageInfo", true)
        set(v) { sp.edit().putBoolean("showPageInfo", v).apply() }
}

class Favorite(var page: Int, var name: String)

/** Per-document state that is restored when a file is reopened. */
class FileState(val uri: String) {
    var name: String = ""
    var displayMode: String = ""
    var zoom: Float = Float.NaN
    var page: Int = 0
    var offsetY: Float = 0f      // fraction (0..1) of the page height scrolled off the top
    var offsetX: Float = 0f
    var rotation: Int = 0
    var pageCount: Int = 0
    var openCount: Int = 0
    var lastOpened: Long = 0
    var pinned: Boolean = false
    var trim: Boolean = false
    var favorites: MutableList<Favorite> = ArrayList()

    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri); put("name", name); put("mode", displayMode)
        put("zoom", if (zoom.isNaN()) JSONObject.NULL else zoom.toDouble())
        put("page", page); put("oy", offsetY.toDouble()); put("ox", offsetX.toDouble())
        put("rot", rotation); put("count", pageCount); put("opens", openCount)
        put("last", lastOpened); put("pinned", pinned); put("trim", trim)
        val fa = JSONArray()
        for (f in favorites) fa.put(JSONObject().put("p", f.page).put("n", f.name))
        put("favs", fa)
    }

    companion object {
        fun fromJson(o: JSONObject): FileState {
            val s = FileState(o.getString("uri"))
            s.name = o.optString("name")
            s.displayMode = o.optString("mode")
            s.zoom = if (o.isNull("zoom")) Float.NaN else o.optDouble("zoom", Double.NaN).toFloat()
            s.page = o.optInt("page"); s.offsetY = o.optDouble("oy", 0.0).toFloat()
            s.offsetX = o.optDouble("ox", 0.0).toFloat(); s.rotation = o.optInt("rot")
            s.pageCount = o.optInt("count"); s.openCount = o.optInt("opens")
            s.lastOpened = o.optLong("last"); s.pinned = o.optBoolean("pinned")
            s.trim = o.optBoolean("trim")
            val fa = o.optJSONArray("favs")
            if (fa != null) for (i in 0 until fa.length()) {
                val f = fa.getJSONObject(i)
                s.favorites.add(Favorite(f.optInt("p"), f.optString("n")))
            }
            return s
        }
    }
}

class HistoryStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")
    private val items = LinkedHashMap<String, FileState>()
    val thumbDir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    init {
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val s = FileState.fromJson(arr.getJSONObject(i))
                    items[s.uri] = s
                }
            }
        } catch (_: Exception) { }
    }

    fun recent(): List<FileState> =
        items.values.sortedWith(compareByDescending<FileState> { it.pinned }.thenByDescending { it.lastOpened })

    fun get(uri: String): FileState? = items[uri]

    fun getOrCreate(uri: String, name: String): FileState {
        val s = items.getOrPut(uri) { FileState(uri) }
        if (name.isNotEmpty()) s.name = name
        return s
    }

    fun remove(uri: String) {
        items.remove(uri); thumbFile(uri).delete(); save()
    }

    fun clear() {
        items.values.toList().forEach { thumbFile(it.uri).delete() }
        items.clear(); save()
    }

    fun save() {
        // keep at most 200 entries (pinned first)
        if (items.size > 200) {
            val keep = recent().take(200).map { it.uri }.toSet()
            items.keys.retainAll(keep)
        }
        try {
            val arr = JSONArray()
            for (s in items.values) arr.put(s.toJson())
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun thumbFile(uri: String): File = File(thumbDir, sha1(uri) + ".png")

    companion object {
        fun sha1(s: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
