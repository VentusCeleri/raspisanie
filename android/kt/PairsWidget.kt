package ru.ventusceleri.pary

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

class PairsWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) =
        refresh(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_TICK) { refresh(ctx); return }
        super.onReceive(ctx, intent)
    }

    override fun onDisabled(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(tickIntent(ctx))
    }

    private fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        // сначала мгновенно из кэша, потом (если пора) в фоне скачать свежие данные
        render(app)
        val stale = System.currentTimeMillis() - Schedule.lastFetch(app) > 3 * 3600_000L
        if (stale || Schedule.cached(app) == null) {
            val pr = goAsync()
            Thread {
                try { if (Schedule.fetch(app)) render(app) } finally { pr.finish() }
            }.start()
        }
    }

    companion object {
        const val ACTION_TICK = "ru.ventusceleri.pary.TICK"

        fun updateAll(ctx: Context) {
            ctx.sendBroadcast(Intent(ctx, PairsWidget::class.java).setAction(ACTION_TICK))
        }

        private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, PairsWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun render(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PairsWidget::class.java))
            if (ids.isEmpty()) return
            val pg = Schedule.pg(ctx)
            val json = Schedule.cached(ctx)
            val now = System.currentTimeMillis()
            val lessons = try { json?.let { Schedule.parse(it, pg) } } catch (e: Exception) { null }
            val snap = lessons?.let { Snapshot(it, now) }
            for (id in ids) {
                val h = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
                mgr.updateAppWidget(id, build(ctx, snap, now, pg, h))
            }
            // следующая перерисовка — к началу/концу пары (неточный будильник, без спецразрешений)
            val at = snap?.refreshAt ?: (now + 30 * 60_000)
            ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, tickIntent(ctx))
        }

        private fun build(ctx: Context, snap: Snapshot?, now: Long, pg: Int, heightDp: Int): RemoteViews {
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val ink = Color.parseColor(if (night) "#ECE6DA" else "#14212E")
            val muted = Color.parseColor(if (night) "#93A0AD" else "#6B7480")
            val accent = Color.parseColor(if (night) "#F0A04B" else "#C96F12")
            val exam = Color.parseColor(if (night) "#FF7B6B" else "#B4372B")

            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setInt(R.id.root, "setBackgroundResource", if (night) R.drawable.bg_dark else R.drawable.bg_light)
            listOf(R.id.room, R.id.subj, R.id.rest1, R.id.rest2, R.id.rest3).forEach { v.setTextColor(it, ink) }
            listOf(R.id.meta, R.id.restHead, R.id.foot).forEach { v.setTextColor(it, muted) }
            v.setTextColor(R.id.note, exam)

            val open = PendingIntent.getActivity(ctx, 2,
                Intent(Intent.ACTION_VIEW, Uri.parse(SITE + "?pg=" + pg)), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.root, open)

            if (snap == null) {
                v.setTextViewText(R.id.head, "ПАРЫ")
                v.setTextColor(R.id.head, accent)
                v.setTextViewText(R.id.room, "…")
                v.setTextViewText(R.id.subj, "Загружаю расписание. Нужен интернет.")
                v.setTextViewText(R.id.meta, "")
                v.setViewVisibility(R.id.note, View.GONE)
                v.setViewVisibility(R.id.restBox, View.GONE)
                return v
            }
            val f = snap.focus
            v.setTextViewText(R.id.head, snap.head(now))
            v.setTextColor(R.id.head, if (f?.ty == "Экзамен") exam else accent)
            if (f == null) {
                v.setTextViewText(R.id.room, "🎉")
                v.setTextViewText(R.id.subj, "Каникулы")
                v.setTextViewText(R.id.meta, "")
                v.setViewVisibility(R.id.note, View.GONE)
                v.setViewVisibility(R.id.restBox, View.GONE)
                return v
            }
            v.setTextViewText(R.id.room, f.r.ifEmpty { "—" })
            v.setTextColor(R.id.room, if (f.ty == "Экзамен") exam else ink)
            v.setTextViewText(R.id.subj, Schedule.short(f.s))
            v.setTextViewText(R.id.meta, (Schedule.TY[f.ty] ?: f.ty) + (if (f.n > 1) " ×${f.n}" else "") +
                " · " + f.te.split(",").first() + " · ${pg} пг")
            if (f.note.isNotEmpty()) { v.setViewVisibility(R.id.note, View.VISIBLE); v.setTextViewText(R.id.note, f.note) }
            else v.setViewVisibility(R.id.note, View.GONE)

            // список: остаток дня, а если его нет — следующий учебный день
            val (title, items) = when {
                snap.rest.isNotEmpty() -> (if (f.d == snap.today) "ДАЛЬШЕ СЕГОДНЯ" else "В ЭТОТ ДЕНЬ") to snap.rest
                snap.nextDay != null -> Schedule.dayLabel(snap.nextDay).uppercase() to snap.nextDayLessons
                else -> "" to emptyList()
            }
            val rows = when { heightDp < 150 -> 0; heightDp < 210 -> 2; else -> 3 }
            if (rows == 0 || items.isEmpty()) {
                v.setViewVisibility(R.id.restBox, View.GONE)
            } else {
                v.setViewVisibility(R.id.restBox, View.VISIBLE)
                v.setTextViewText(R.id.restHead, title)
                val ids = listOf(R.id.rest1, R.id.rest2, R.id.rest3)
                ids.forEachIndexed { i, rid ->
                    val l = items.getOrNull(i)
                    if (l == null || i >= rows) v.setViewVisibility(rid, View.GONE)
                    else {
                        v.setViewVisibility(rid, View.VISIBLE)
                        v.setTextViewText(rid, l.t + "  ·  " + l.r.ifEmpty { "—" } + "  ·  " + Schedule.short(l.s))
                        v.setTextColor(rid, if (l.ty == "Экзамен") exam else ink)
                    }
                }
            }
            return v
        }
    }
}
