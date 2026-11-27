package ru.ventusceleri.pary

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One class as stored in data.json / g/<id>.json (see sync.py). */
data class Lesson(
    val d: String, val t: String, val n: Int, val s: String, val ty: String, val r: String,
    val te: String, val sg: Int, val note: String, val st: String, val mil: Boolean,
) {
    val id: String get() = listOf(d, t, s, ty, r, sg.toString(), st).joinToString("|")
    val isCancelled: Boolean get() = st.isNotEmpty()
    val isExam: Boolean get() = ty == "Экзамен"

    companion object {
        fun parse(o: JSONObject): Lesson? = runCatching {
            Lesson(
                d = o.getString("d"), t = o.getString("t"), n = maxOf(o.optInt("n", 1), 1),
                s = o.optString("s"), ty = o.optString("ty"), r = o.optString("r"), te = o.optString("te"),
                sg = o.optInt("sg", 0), note = o.optString("note"), st = o.optString("st"),
                mil = when (val m = o.opt("mil")) { is Boolean -> m; is Number -> m.toInt() != 0; else -> false },
            )
        }.getOrNull()
    }
}

data class Meta(val updated: String, val upperMonday: String?, val group: String)

data class ScheduleData(val meta: Meta, val lessons: List<Lesson>) {
    fun timed(f: ScheduleFilter): List<TimedLesson> = lessons
        .filter { (it.sg == 0 || it.sg == f.subgroup) && (f.showMilitary || !it.mil) && (f.includeCancelled || !it.isCancelled) }
        .mapNotNull { l ->
            val s = Schedule.instant(l.d, l.t) ?: return@mapNotNull null
            val e = Schedule.instant(l.d, Schedule.endTime(l)) ?: return@mapNotNull null
            TimedLesson(l, s, e)
        }
        .sortedBy { it.start }

    companion object {
        fun parse(text: String): ScheduleData {
            val root = JSONObject(text)
            val m = root.optJSONObject("meta") ?: JSONObject()
            val arr = root.optJSONArray("lessons") ?: JSONArray()
            val lessons = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Lesson::parse) }
            return ScheduleData(
                Meta(m.optString("updated"), m.optString("upperMonday").ifEmpty { null },
                     m.optString("group").ifEmpty { "РН-23-04" }),
                lessons,
            )
        }
    }
}

data class GroupInfo(val id: Int, val code: String, val f: String) {
    fun matches(query: String): Boolean {
        val q = normalize(query)
        return q.isEmpty() || normalize(code).contains(q)
    }

    companion object {
        fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        fun parseIndex(text: String): List<GroupInfo> {
            val arr = JSONObject(text).optJSONArray("groups") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { GroupInfo(it.optInt("id"), it.optString("code"), it.optString("f")) }
            }.filter { it.id != 0 }
        }
    }
}

data class ScheduleFilter(val subgroup: Int, val showMilitary: Boolean, val includeCancelled: Boolean)

data class TimedLesson(val lesson: Lesson, val start: Instant, val end: Instant) {
    val id: String get() = lesson.id
    val endText: String get() = Schedule.endTime(lesson)
    val day: LocalDate get() = start.atZone(Schedule.zone).toLocalDate()
}

/** Calendar math and labels. All times are Moscow time, whatever the device zone is. */
object Schedule {
    val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val slots = listOf(
        "8:30" to "10:00", "10:15" to "11:45", "12:00" to "13:30", "14:00" to "15:30",
        "15:45" to "17:15", "17:20" to "18:50", "18:55" to "20:25", "20:30" to "22:00",
    )
    val weekdayShort = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    val weekdayFull = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
    private val monthShort = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
    private val monthGen = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа",
                                  "сентября", "октября", "ноября", "декабря")

    private val shortNames = mapOf(
        "Скважинная добыча нефти" to "СДН",
        "Проектирование разработки (Petrel)" to "Проектирование",
        "Проектирование разработки месторождений в виртуальной среде профессиональной деятельности" to "Проектирование",
        "Исследование скважин и пластов" to "Исследование скважин",
        "Управление продуктивностью скважин" to "УПС",
        "Система АРМ в добыче нефти" to "Система АРМ",
        "Системы сбора и подготовки" to "Системы сбора",
        "Основы промысловой геофизики" to "Геофизика",
        "Нефтегазопромысловое оборудование" to "НГП оборудование",
        "Морские НГ проекты" to "Морские проекты",
        "Перспективные проекты освоения морских нефтегазовых ресурсов" to "Морские проекты",
    )
    private val typeShort = mapOf(
        "Лекция" to "лек", "Семинар" to "сем", "ЛР" to "лаб", "Экзамен" to "экзамен",
        "Консультация" to "конс", "Мероприятие" to "меропр",
    )

    fun shortName(s: String) = shortNames[s] ?: s
    fun typeLabel(ty: String) = typeShort[ty] ?: ty.lowercase()

    fun minutes(t: String): Int {
        val p = t.split(":").mapNotNull { it.toIntOrNull() }
        return if (p.size == 2) p[0] * 60 + p[1] else 0
    }

    private fun hhmm(m: Int) = "${m / 60}:" + (m % 60).toString().padStart(2, '0')

    fun endTime(l: Lesson): String {
        val i = slots.indexOfFirst { it.first == l.t }
        if (i >= 0) return slots[minOf(i + l.n - 1, slots.size - 1)].second
        return hhmm(minutes(l.t) + l.n * 90 + (l.n - 1) * 15)
    }

    fun day(iso: String): LocalDate? = runCatching { LocalDate.parse(iso) }.getOrNull()

    fun instant(iso: String, t: String): Instant? =
        day(iso)?.atStartOfDay(zone)?.plusMinutes(minutes(t).toLong())?.toInstant()

    fun today(): LocalDate = LocalDate.now(zone)
    fun monday(d: LocalDate): LocalDate = d.with(DayOfWeek.MONDAY)

    fun isUpperWeek(d: LocalDate, upperMonday: String?): Boolean? {
        val ref = upperMonday?.let(::day) ?: return null
        val weeks = Math.floorDiv(ChronoUnit.DAYS.between(ref, monday(d)), 7L)
        return Math.floorMod(weeks, 2L) == 0L
    }

    fun dayLabel(d: LocalDate) = "${weekdayShort[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${monthShort[d.monthValue - 1]}"
    fun longDate(d: LocalDate) = "${d.dayOfMonth} ${monthGen[d.monthValue - 1]}"
    fun shortDate(d: LocalDate) = "${d.dayOfMonth} ${monthShort[d.monthValue - 1]}"

    fun relativeDay(d: LocalDate, now: LocalDate = today()) = when (d) {
        now -> "Сегодня"
        now.plusDays(1) -> "Завтра"
        else -> dayLabel(d)
    }

    /** «через 1 ч 5 мин» */
    fun until(from: Instant, to: Instant): String {
        val m = maxOf(0L, ChronoUnit.MINUTES.between(from, to) + 1)
        return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин"
    }
}

/** Current class or the next one, plus the rest of that day and the following study day. */
class NowState(lessons: List<TimedLesson>, now: Instant) {
    val current: TimedLesson? = lessons.firstOrNull { it.start <= now && now < it.end }
    val next: TimedLesson? = lessons.firstOrNull { it.start > now }
    val focus: TimedLesson? = current ?: next
    val rest: List<TimedLesson>
    val followingDay: LocalDate?
    val followingLessons: List<TimedLesson>

    init {
        val f = focus
        if (f != null) {
            rest = lessons.filter { it.day == f.day && it.end > now && it.id != f.id }
            val after = lessons.filter { it.day > f.day }
            followingDay = after.firstOrNull()?.day
            followingLessons = after.filter { it.day == followingDay }
        } else {
            rest = emptyList(); followingDay = null; followingLessons = emptyList()
        }
    }
}
