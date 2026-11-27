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

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

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
    Column(GlanceModifier.fillMaxSize().background(c.bg).cornerRadius(22.dp).padding(14.dp)
        .clickable(actionStartActivity<MainActivity>())) {
        when {
            s.groupId == null -> T("Открой «Пары» и выбери группу", c.ink, 13)
            !hasData -> T("Открой приложение «Пары» с интернетом", c.ink, 13)
            size.width >= 250.dp && size.height >= 250.dp -> Large(st, now, c)
            size.width >= 250.dp -> Medium(st, now, c)
            else -> Small(st, now, c)
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

@Composable
private fun Focus(st: NowState, c: Swatch, big: Boolean) {
    val f = st.focus
    if (f == null) {
        T("ПАР БОЛЬШЕ НЕТ", c.accent, 10, FontWeight.Bold)
        T("Каникулы 🎉", c.ink, 18, FontWeight.Bold)
        return
    }
    val l = f.lesson
    T(headline(st, f), if (l.isExam) c.exam else c.accent, 10, FontWeight.Bold)
    T(l.r.ifEmpty { "—" }, if (l.isExam) c.exam else c.ink, if (big) 38 else 32, FontWeight.Bold)
    T(Schedule.shortName(l.s), c.ink, if (big) 15 else 13, FontWeight.Medium, lines = 2)
    T(Schedule.typeLabel(l.ty) + (if (l.n > 1) " ×${l.n}" else "") +
          (if (l.te.isNotEmpty()) " · " + l.te.substringBefore(",") else ""), c.muted, 11)
    if (l.note.isNotEmpty()) T(l.note, c.exam, 10, FontWeight.Medium)
}

@Composable
private fun RestList(items: List<TimedLesson>, max: Int, c: Swatch) {
    items.take(max).forEach { l ->
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            T(l.lesson.t, c.muted, 12, FontWeight.Bold, modifier = GlanceModifier.width(44.dp))
            T(Schedule.shortName(l.lesson.s), c.ink, 12, FontWeight.Medium, modifier = GlanceModifier.defaultWeight())
            T(l.lesson.r, if (l.lesson.isExam) c.exam else c.ink, 13, FontWeight.Bold)
        }
    }
}

@Composable
private fun ColumnScope.Small(st: NowState, now: Instant, c: Swatch) {
    Focus(st, c, big = false)
    Spacer(GlanceModifier.defaultWeight())
    st.rest.firstOrNull()?.let { n ->
        T("потом ${n.lesson.t} · ${n.lesson.r}" + if (st.rest.size > 1) "  +${st.rest.size - 1}" else "", c.muted, 11)
    }
}

@Composable
private fun ColumnScope.Medium(st: NowState, now: Instant, c: Swatch) {
    Row(GlanceModifier.fillMaxSize()) {
        Column(GlanceModifier.width(130.dp)) { Focus(st, c, big = false) }
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.defaultWeight()) {
            val f = st.focus
            when {
                st.rest.isNotEmpty() -> {
                    T(if (f?.day == Schedule.today()) "ДАЛЬШЕ СЕГОДНЯ" else "В ЭТОТ ДЕНЬ", c.muted, 10, FontWeight.Bold)
                    Spacer(GlanceModifier.height(4.dp))
                    RestList(st.rest, 4, c)
                }
                st.followingDay != null -> {
                    T(Schedule.relativeDay(st.followingDay).uppercase(), c.muted, 10, FontWeight.Bold)
                    Spacer(GlanceModifier.height(4.dp))
                    RestList(st.followingLessons, 4, c)
                }
                f != null -> T("больше пар нет", c.muted, 12)
            }
        }
    }
}

@Composable
private fun ColumnScope.Large(st: NowState, now: Instant, c: Swatch) {
    Focus(st, c, big = true)
    Spacer(GlanceModifier.height(10.dp))
    val f = st.focus
    if (f != null) {
        T(if (f.day == Schedule.today()) "ДАЛЬШЕ СЕГОДНЯ" else "В ЭТОТ ДЕНЬ", c.muted, 10, FontWeight.Bold)
        if (st.rest.isEmpty()) T("больше пар нет", c.muted, 12) else RestList(st.rest, 5, c)
    }
    st.followingDay?.let { d ->
        Spacer(GlanceModifier.height(10.dp))
        T(Schedule.dayLabel(d).uppercase(), c.muted, 10, FontWeight.Bold)
        RestList(st.followingLessons, 4, c)
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
