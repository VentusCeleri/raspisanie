package ru.ventusceleri.pary

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Экран приложения: выбор подгруппы, обновление данных, как добавить виджет. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var b1: Button
    private lateinit var b2: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (28 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
        }
        fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }.also { col.addView(it) }

        text("Пары РН-23-04", 26f, true)
        text("Виджет с текущей и следующей парой и аудиторией. Расписание берётся с сайта и обновляется само.", 15f)
        text("Моя подгруппа", 13f, true).setTextColor(Color.parseColor("#C96F12"))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        b1 = Button(this).apply { text = "1 подгруппа"; setOnClickListener { setPg(1) } }
        b2 = Button(this).apply { text = "2 подгруппа"; setOnClickListener { setPg(2) } }
        row.addView(b1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(b2, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        text("Как добавить виджет", 13f, true).setTextColor(Color.parseColor("#C96F12"))
        text("1. Зажми пустое место на рабочем столе.\n2. «Виджеты» → найди «Пары».\n3. Перетащи на экран. Размер можно менять: в маленьком — одна пара, в большом — весь день.", 15f)

        status = text("", 13f)
        col.addView(Button(this).apply {
            text = "Обновить расписание сейчас"
            setOnClickListener { refreshNow() }
        })
        col.addView(Button(this).apply {
            text = "Открыть сайт с расписанием"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE + "?pg=" + Schedule.pg(this@MainActivity)))) }
        })
        text("Если виджет перестал обновляться — открой это приложение и нажми «Обновить». На некоторых телефонах (Xiaomi, Huawei) стоит разрешить приложению автозапуск / работу в фоне.", 12f).alpha = 0.7f

        setContentView(ScrollView(this).apply { addView(col) })
        showPg()
        refreshNow()
    }

    private fun setPg(pg: Int) {
        Schedule.prefs(this).edit().putInt("pg", pg).apply()
        showPg()
        PairsWidget.updateAll(this)
    }

    private fun showPg() {
        val pg = Schedule.pg(this)
        b1.alpha = if (pg == 1) 1f else 0.45f
        b2.alpha = if (pg == 2) 1f else 0.45f
        b1.text = if (pg == 1) "✓ 1 подгруппа" else "1 подгруппа"
        b2.text = if (pg == 2) "✓ 2 подгруппа" else "2 подгруппа"
    }

    private fun refreshNow() {
        status.text = "Обновляю…"
        Thread {
            val ok = Schedule.fetch(applicationContext)
            runOnUiThread {
                val cnt = try { Schedule.cached(this)?.let { Schedule.parse(it, Schedule.pg(this)).size } } catch (e: Exception) { null }
                status.text = when {
                    ok -> "Расписание обновлено: $cnt пар впереди."
                    cnt != null -> "Нет сети — показываю сохранённое ($cnt пар)."
                    else -> "Нет сети и нет сохранённого расписания."
                }
                PairsWidget.updateAll(this)
            }
        }.start()
    }
}
