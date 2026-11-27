package ru.ventusceleri.pary

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit

class PairsWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val MEDIUM = DpSize(250.dp, 110.dp)
        private val LARGE = DpSize(250.dp, 250.dp)
    }

    // Exact: layout is computed from the real widget size, so any shape is filled (see WidgetContent).
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = Repository.settings(context).value
        val data = s.groupId?.let { Repository.cached(context, it) }
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val c = s.themeId.palette.colors(s.appearanceMode.isDark(systemDark))
        val lessons = data?.timed(ScheduleFilter(s.subgroup, showMilitary = false, includeCancelled = false)) ?: emptyList()
        provideContent { WidgetContent(s, data != null, lessons, c) }
    }
}

class PairsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PairsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdates.schedule(context)
    }
}

@Composable
private fun WidgetContent(s: AppSettings, hasData: Boolean, lessons: List<TimedLesson>, c: Swatch) {
    val size = LocalSize.current
    val now = Instant.now()
    val st = NowState(lessons, now)
    val w = size.width.value
    val h = size.height.value
    val pad = if (w < 110 || h < 80) 8 else 14
    Column(GlanceModifier.fillMaxSize().background(c.bg).cornerRadius(22.dp).padding(pad.dp)
        .clickable(actionStartActivity<MainActivity>())) {
        val inner = h - 2 * pad
        when {
            s.groupId == null -> T("Открой «Пары» и выбери группу", c.ink, 12, lines = 3)
            !hasData -> T("Открой «Пары» с интернетом", c.ink, 12, lines = 3)
            w < 110 || h < 80 -> Micro(st, c)                                   // 1×1 and tiny
            h < 110 -> Strip(st, lessons, now, c, wide = w >= 280)             // one row high
            w < 200 -> Tall(st, lessons, now, c, inner)                         // narrow column
            h < 200 -> TwoColumns(st, lessons, now, c, inner)                   // wide, not tall
            else -> Stacked(st, lessons, now, c, inner, big = w >= 300)         // square / large
        }
    }
}

@Composable
private fun T(text: String, color: androidx.compose.ui.graphics.Color, size: Int,
              weight: FontWeight = FontWeight.Normal, lines: Int = 1, modifier: GlanceModifier = GlanceModifier) {
    Text(text, modifier, style = TextStyle(color = ColorProvider(color), fontSize = size.sp, fontWeight = weight),
         maxLines = lines)
}

private fun headline(st: NowState, f: TimedLesson): String = when {
    st.current != null -> "СЕЙЧАС · ДО ${f.endText}"
    f.day == Schedule.today() -> "СЛЕДУЮЩАЯ · ${f.lesson.t}"
    else -> "${Schedule.relativeDay(f.day).uppercase()} · ${f.lesson.t}"
}

private fun accentOf(l: Lesson, c: Swatch) = if (l.isExam) c.exam else c.accent
private fun inkOf(l: Lesson, c: Swatch) = if (l.isExam) c.exam else c.ink

/** Rows after the focus class: rest of its day, then following days with a day header, as many as fit. */
private sealed interface UpRow {
    data class Day(val label: String) : UpRow
    data class Item(val l: TimedLesson) : UpRow
}

private fun upcoming(all: List<TimedLesson>, st: NowState, now: Instant, max: Int): List<UpRow> {
    val f = st.focus ?: return emptyList()
    if (max <= 0) return emptyList()
    val rows = mutableListOf<UpRow>()
    var day: java.time.LocalDate? = null
    for (l in all) {
        if (l.start <= f.start || l.id == f.id || l.end <= now) continue
        if (l.day != day) {
            val label = if (l.day == f.day) (if (f.day == Schedule.today()) "ДАЛЬШЕ СЕГОДНЯ" else "В ЭТОТ ДЕНЬ")
                        else Schedule.relativeDay(l.day).uppercase()
            if (rows.size + 2 > max) break          // a header needs room for at least one class
            rows += UpRow.Day(label); day = l.day
        }
        rows += UpRow.Item(l)
        if (rows.size >= max) break
    }
    return rows
}

private const val ROW_DP = 21f

@Composable
private fun UpList(rows: List<UpRow>, c: Swatch, compact: Boolean = false) {
    rows.forEachIndexed { i, r ->
        when (r) {
            is UpRow.Day -> T(r.label, c.muted, 10, FontWeight.Bold,
                              modifier = GlanceModifier.padding(top = if (i == 0) 0.dp else 4.dp, bottom = 1.dp))
            is UpRow.Item -> Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                                 verticalAlignment = Alignment.CenterVertically) {
                T(r.l.lesson.t, c.muted, 12, FontWeight.Bold, modifier = GlanceModifier.width(if (compact) 38.dp else 44.dp))
                T(Schedule.shortName(r.l.lesson.s), c.ink, 12, FontWeight.Medium, modifier = GlanceModifier.defaultWeight())
                T(r.l.lesson.r, inkOf(r.l.lesson, c), 13, FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NoMore(c: Swatch) {
    T("ПАР БОЛЬШЕ НЕТ", c.accent, 10, FontWeight.Bold)
    T("Каникулы 🎉", c.ink, 16, FontWeight.Bold)
}

/** Focus block; `roomSize` scales with the widget. Returns nothing — height ≈ 60 + roomSize. */
@Composable
private fun Focus(st: NowState, c: Swatch, roomSize: Int, details: Boolean) {
    val f = st.focus ?: return NoMore(c)
    val l = f.lesson
    T(headline(st, f), accentOf(l, c), 10, FontWeight.Bold)
    T(l.r.ifEmpty { "—" }, inkOf(l, c), roomSize, FontWeight.Bold)
    T(Schedule.shortName(l.s), c.ink, if (roomSize >= 36) 15 else 13, FontWeight.Medium, lines = if (details) 2 else 1)
    if (details) {
        T(Schedule.typeLabel(l.ty) + (if (l.n > 1) " ×${l.n}" else "") +
              (if (l.te.isNotEmpty()) " · " + l.te.substringBefore(",") else ""), c.muted, 11)
        if (l.note.isNotEmpty()) T(l.note, c.exam, 10, FontWeight.Medium)
    }
}

@Composable
private fun ColumnScope.Micro(st: NowState, c: Swatch) {
    val f = st.focus
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
           horizontalAlignment = Alignment.CenterHorizontally) {
        if (f == null) { T("—", c.ink, 20, FontWeight.Bold); return@Column }
        T(if (st.current != null) "до ${f.endText}" else f.lesson.t, accentOf(f.lesson, c), 11, FontWeight.Bold)
        T(f.lesson.r.ifEmpty { "—" }, inkOf(f.lesson, c), 22, FontWeight.Bold)
    }
}

@Composable
private fun ColumnScope.Strip(st: NowState, all: List<TimedLesson>, now: Instant, c: Swatch, wide: Boolean) {
    val f = st.focus
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (f == null) { NoMore(c); return@Row }
        Column(GlanceModifier.width(if (wide) 110.dp else 90.dp)) {
            T(headline(st, f).substringAfter("· ").let { if (st.current != null) "до ${f.endText}" else it },
              accentOf(f.lesson, c), 11, FontWeight.Bold)
            T(f.lesson.r.ifEmpty { "—" }, inkOf(f.lesson, c), 26, FontWeight.Bold)
        }
        Column(GlanceModifier.defaultWeight()) {
            T(Schedule.shortName(f.lesson.s), c.ink, 13, FontWeight.Medium)
            T(Schedule.typeLabel(f.lesson.ty) + if (f.lesson.te.isNotEmpty()) " · " + f.lesson.te.substringBefore(",") else "",
              c.muted, 11)
            val next = (upcoming(all, st, now, 2).firstOrNull { it is UpRow.Item } as? UpRow.Item)?.l
            if (next != null && wide) T("потом ${next.lesson.t} · ${next.lesson.r}", c.muted, 11, FontWeight.Medium)
        }
    }
}

@Composable
private fun ColumnScope.Tall(st: NowState, all: List<TimedLesson>, now: Instant, c: Swatch, inner: Float) {
    val details = inner >= 230
    Focus(st, c, roomSize = 30, details = details)
    val used = if (details) 108f else 80f
    val capacity = ((inner - used - 8) / ROW_DP).toInt()
    if (capacity > 0) {
        Spacer(GlanceModifier.height(8.dp))
        UpList(upcoming(all, st, now, capacity), c, compact = true)
    }
}

@Composable
private fun ColumnScope.TwoColumns(st: NowState, all: List<TimedLesson>, now: Instant, c: Swatch, inner: Float) {
    Row(GlanceModifier.fillMaxSize()) {
        Column(GlanceModifier.width(130.dp)) { Focus(st, c, roomSize = 32, details = inner >= 120) }
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.defaultWeight()) {
            val rows = upcoming(all, st, now, (inner / ROW_DP).toInt())
            if (rows.isEmpty() && st.focus != null) T("больше пар нет", c.muted, 12) else UpList(rows, c)
        }
    }
}

@Composable
private fun ColumnScope.Stacked(st: NowState, all: List<TimedLesson>, now: Instant, c: Swatch, inner: Float, big: Boolean) {
    Focus(st, c, roomSize = if (big) 40 else 34, details = true)
    val used = if (big) 126f else 118f
    val capacity = ((inner - used - 10) / ROW_DP).toInt()
    if (capacity > 0) {
        Spacer(GlanceModifier.height(10.dp))
        val rows = upcoming(all, st, now, capacity)
        if (rows.isEmpty() && st.focus != null) T("больше пар нет", c.muted, 12) else UpList(rows, c)
    }
}

// ---------------------------------------------------------------- updates

/** Hourly data refresh (WorkManager) + redraw at the next class start/end (inexact alarm, no permission needed). */
object WidgetUpdates {
    private const val WORK = "pary-refresh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun updateNow(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { redraw(app) }
    }

    suspend fun redraw(ctx: Context) {
        PairsWidget().updateAll(ctx)
        scheduleBoundary(ctx)
    }

    private fun scheduleBoundary(ctx: Context) {
        val s = Repository.settings(ctx).value
        val group = s.groupId ?: return
        val now = Instant.now()
        val next = Repository.cached(ctx, group)
            ?.timed(ScheduleFilter(s.subgroup, showMilitary = false, includeCancelled = false))
            ?.flatMap { listOf(it.start, it.end) }
            ?.filter { it > now }
            ?.minOrNull() ?: return
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, BoundaryReceiver::class.java),
                                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC, next.toEpochMilli() + 1000, pi)
    }
}

class BoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { WidgetUpdates.redraw(context.applicationContext) } finally { pending.finish() }
        }
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val group = Repository.settings(applicationContext).value.groupId
        if (group != null) Repository.load(applicationContext, group)
        WidgetUpdates.redraw(applicationContext)
        return Result.success()
    }
}
