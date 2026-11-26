package ru.ventusceleri.pary

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

const val SITE = "https://ventusceleri.github.io/raspisanie/"

data class Lesson(
    val d: String, val t: String, val n: Int, val s: String, val ty: String,
    val r: String, val te: String, val note: String, val start: Long, val end: Long,
)

object Schedule {
    private val SLOTS = listOf("8:30" to "10:00", "10:15" to "11:45", "12:00" to "13:30", "14:00" to "15:30",
        "15:45" to "17:15", "17:20" to "18:50", "18:55" to "20:25", "20:30" to "22:00")
    private val SHORT = mapOf(
        "Скважинная добыча нефти" to "СДН",
        "Исследование скважин и пластов" to "Исследование скважин",
        "Управление продуктивностью скважин" to "УПС",
        "Система АРМ в добыче нефти" to "Система АРМ",
        "Системы сбора и подготовки" to "Системы сбора",
        "Основы промысловой геофизики" to "Геофизика",
        "Нефтегазопромысловое оборудование" to "НГП оборудование",
        "Морские НГ проекты" to "Морские проекты",
        "Перспективные проекты освоения морских нефтегазовых ресурсов" to "Морские проекты",
        "Проектирование разработки (Petrel)" to "Проектирование",
        "Проектирование разработки месторождений в виртуальной среде профессиональной деятельности" to "Проектирование",
    )
    val TY = mapOf("Лекция" to "лек", "Семинар" to "сем", "ЛР" to "лаб", "Экзамен" to "ЭКЗАМЕН",
        "Консультация" to "конс", "Мероприятие" to "меропр")
    private val WD = arrayOf("Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")
    private val MON = arrayOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

    fun short(s: String) = SHORT[s] ?: s
    private fun mins(t: String): Int { val p = t.split(":"); return p[0].toInt() * 60 + p[1].toInt() }
    private fun hhmm(m: Int) = "${m / 60}:" + (m % 60).toString().padStart(2, '0')

    fun endTime(t: String, n: Int): String {
        val i = SLOTS.indexOfFirst { it.first == t }
        if (i >= 0) return SLOTS[minOf(i + n - 1, SLOTS.size - 1)].second
        return hhmm(mins(t) + n * 90 + (n - 1) * 15)
    }

    fun at(d: String, t: String): Long {
        val p = d.split("-").map { it.toInt() }
        val c = Calendar.getInstance()
        c.set(p[0], p[1] - 1, p[2], mins(t) / 60, mins(t) % 60, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun iso(ms: Long): String {
        val c = Calendar.getInstance(); c.timeInMillis = ms
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun dayLabel(d: String): String {
        val p = d.split("-").map { it.toInt() }
        val c = Calendar.getInstance(); c.set(p[0], p[1] - 1, p[2])
        return WD[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + p[2] + " " + MON[p[1] - 1]
    }

    fun parse(json: String, pg: Int): List<Lesson> {
        val arr = JSONObject(json).getJSONArray("lessons")
        val out = ArrayList<Lesson>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("mil", false)) continue
            if (o.optString("st", "").isNotEmpty()) continue
            val sg = o.optInt("sg", 0)
            if (sg != 0 && sg != pg) continue
            val d = o.getString("d"); val t = o.getString("t"); val n = o.optInt("n", 1)
            out.add(Lesson(d, t, n, o.optString("s"), o.optString("ty"), o.optString("r"),
                o.optString("te"), o.optString("note"), at(d, t), at(d, endTime(t, n))))
        }
        return out.sortedBy { it.start }
    }

    // ----- кэш и загрузка -----
    private fun cache(ctx: Context) = File(ctx.filesDir, "data.json")

    fun cached(ctx: Context): String? = cache(ctx).takeIf { it.exists() }?.readText()

    fun lastFetch(ctx: Context) = prefs(ctx).getLong("lastFetch", 0)

    /** Скачивает data.json; при успехе кладёт в кэш. Возвращает true, если удалось. */
    fun fetch(ctx: Context): Boolean = try {
        val c = URL(SITE + "data.json?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Cache-Control", "no-cache")
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        JSONObject(text).getJSONArray("lessons") // проверка формата
        cache(ctx).writeText(text)
        prefs(ctx).edit().putLong("lastFetch", System.currentTimeMillis()).apply()
        true
    } catch (e: Exception) { false }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("pary", Context.MODE_PRIVATE)
    fun pg(ctx: Context) = prefs(ctx).getInt("pg", 1)
}

/** Что показать в виджете в момент now. */
class Snapshot(lessons: List<Lesson>, now: Long) {
    val cur = lessons.firstOrNull { it.start <= now && now < it.end }
    val focus = cur ?: lessons.firstOrNull { it.start > now }
    val today = Schedule.iso(now)
    val rest = focus?.let { f -> lessons.filter { it.d == f.d && it.end > now && it !== f } } ?: emptyList()
    val nextDay = focus?.let { f -> lessons.firstOrNull { it.d > f.d }?.d }
    val nextDayLessons = nextDay?.let { d -> lessons.filter { it.d == d } } ?: emptyList()
    /** Когда перерисовать: начало/конец ближайшей пары, но не позже чем через 10 минут. */
    val refreshAt: Long = (lessons.flatMap { listOf(it.start, it.end) }.filter { it > now }.minOrNull()
        ?.let { minOf(it + 1000, now + 10 * 60_000) }) ?: (now + 60 * 60_000)

    fun head(now: Long): String {
        val f = focus ?: return "ПАР БОЛЬШЕ НЕТ"
        if (cur != null) return "СЕЙЧАС · ДО " + Schedule.endTime(f.t, f.n)
        if (f.d == today) {
            val left = ((f.start - now) / 60_000).toInt()
            val rel = if (left >= 60) "${left / 60} ч ${left % 60} мин" else "$left мин"
            return "СЛЕДУЮЩАЯ · ${f.t} · ЧЕРЕЗ $rel"
        }
        val tomorrow = Schedule.iso(now + 24 * 3600_000L)
        return ((if (f.d == tomorrow) "ЗАВТРА" else Schedule.dayLabel(f.d).uppercase()) + " · " + f.t)
    }
}
