package ru.ventusceleri.pary

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppSettings(
    val groupId: Int? = null,
    val groupCode: String? = null,
    val subgroup: Int = 1,
    val showMilitary: Boolean = false,
    val theme: String = ThemeId.SAND.key,
    val appearance: String = Appearance.SYSTEM.key,
) {
    val themeId get() = ThemeId.from(theme)
    val appearanceMode get() = Appearance.from(appearance)
}

data class LoadResult(val data: ScheduleData?, val offline: Boolean)

/** Settings (SharedPreferences) and schedule files (site + on-disk cache). Shared by app, widget and worker. */
object Repository {
    const val SITE = "https://ventusceleri.github.io/raspisanie/"
    val legacyGroup = GroupInfo(9209, "РН-23-04", "")

    private const val PREFS = "pary"
    private var _settings: MutableStateFlow<AppSettings>? = null

    fun settings(ctx: Context): StateFlow<AppSettings> {
        _settings?.let { return it }
        return MutableStateFlow(loadSettings(ctx)).also { _settings = it }
    }

    fun update(ctx: Context, change: (AppSettings) -> AppSettings) {
        val flow = settings(ctx) as MutableStateFlow
        val new = change(flow.value)
        if (new == flow.value) return
        flow.value = new
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("groupId", new.groupId ?: 0)
            .putString("groupCode", new.groupCode)
            .putInt("pg", new.subgroup)
            .putBoolean("mil", new.showMilitary)
            .putString("theme", new.theme)
            .putString("appearance", new.appearance)
            .apply()
    }

    private fun loadSettings(ctx: Context): AppSettings {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var groupId = p.getInt("groupId", 0).takeIf { it > 0 }
        var groupCode = p.getString("groupCode", null)
        // Version 1.x (WebView) only knew РН-23-04 and stored the subgroup as "pg".
        if (groupId == null && p.contains("pg")) {
            groupId = legacyGroup.id; groupCode = legacyGroup.code
        }
        return AppSettings(
            groupId = groupId, groupCode = groupCode,
            subgroup = p.getInt("pg", 1), showMilitary = p.getBoolean("mil", false),
            theme = p.getString("theme", null) ?: ThemeId.SAND.key,
            appearance = p.getString("appearance", null) ?: Appearance.SYSTEM.key,
        )
    }

    // ---- schedule

    private fun cacheFile(ctx: Context, group: Int) = File(ctx.filesDir, "group-$group.json")
    private fun groupsFile(ctx: Context) = File(ctx.filesDir, "groups.json")

    fun cached(ctx: Context, group: Int): ScheduleData? =
        runCatching { ScheduleData.parse(cacheFile(ctx, group).readText()) }.getOrNull()

    suspend fun load(ctx: Context, group: Int): LoadResult = withContext(Dispatchers.IO) {
        try {
            val text = get("g/$group.json")
            val data = ScheduleData.parse(text)
            cacheFile(ctx, group).writeText(text)
            LoadResult(data, offline = false)
        } catch (e: Exception) {
            LoadResult(cached(ctx, group), offline = true)
        }
    }

    fun cachedGroups(ctx: Context): List<GroupInfo> =
        runCatching { GroupInfo.parseIndex(groupsFile(ctx).readText()) }.getOrDefault(emptyList())

    suspend fun loadGroups(ctx: Context): List<GroupInfo> = withContext(Dispatchers.IO) {
        try {
            val text = get("groups.json")
            val list = GroupInfo.parseIndex(text)
            if (list.isNotEmpty()) groupsFile(ctx).writeText(text)
            list
        } catch (e: Exception) {
            cachedGroups(ctx)
        }
    }

    private fun get(path: String): String {
        val url = URL(SITE + path + "?t=" + System.currentTimeMillis() / 60000)
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.useCaches = false
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
