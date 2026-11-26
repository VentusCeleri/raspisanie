package ru.ventusceleri.pary

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/** Приложение = сайт расписания внутри (WebView, работает офлайн через service worker) + виджет. */
class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = Color.parseColor(if (night) "#0B1622" else "#0F2A44")
        window.decorView.setBackgroundColor(bg)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor(if (night) "#0B1622" else "#F4EFE6"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "ParyApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val inside = u.host == Uri.parse(SITE).host && !u.path.orEmpty().endsWith(".apk")
                if (inside) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (!req.isForMainFrame) return
                view.loadDataWithBaseURL(SITE, OFFLINE_HTML, "text/html", "utf-8", null)
            }
        }

        // отступы под статус-бар и навигацию (Android 15 рисует приложение под ними)
        val box = FrameLayout(this)
        box.addView(web, FrameLayout.LayoutParams(-1, -1))
        box.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }
        setContentView(box)

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(SITE + "?pg=" + Schedule.pg(this) + "&app=1")

        // данные для виджета — заодно обновить при открытии приложения
        Thread { if (Schedule.fetch(applicationContext)) PairsWidget.render(applicationContext) }.start()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed() }

    /** Вызывается из JS сайта: window.ParyApp.* */
    inner class Bridge {
        @JavascriptInterface
        fun setPg(pg: String) {
            val n = if (pg == "2") 2 else 1
            Schedule.prefs(this@MainActivity).edit().putInt("pg", n).apply()
            PairsWidget.updateAll(this@MainActivity)
        }

        @JavascriptInterface
        fun hasWidget(): Boolean {
            val m = AppWidgetManager.getInstance(this@MainActivity)
            return m.getAppWidgetIds(ComponentName(this@MainActivity, PairsWidget::class.java)).isNotEmpty()
        }

        @JavascriptInterface
        fun addWidget() = runOnUiThread {
            val m = AppWidgetManager.getInstance(this@MainActivity)
            val ok = m.isRequestPinAppWidgetSupported &&
                m.requestPinAppWidget(ComponentName(this@MainActivity, PairsWidget::class.java), null, null)
            if (!ok) Toast.makeText(this@MainActivity,
                "Зажми пустое место на рабочем столе → «Виджеты» → «Пары»", Toast.LENGTH_LONG).show()
        }

        @JavascriptInterface
        fun version(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    }

    companion object {
        private val OFFLINE_HTML = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<body style="margin:0;background:#0f2a44;color:#f4efe6;font:17px/1.45 sans-serif;padding:32px 24px">
<h2 style="margin:0 0 8px">Нет интернета</h2>
<p style="opacity:.8">Расписание откроется, как только появится сеть. После первого открытия оно сохраняется и работает без интернета.</p>
<p style="opacity:.8">Виджет на рабочем столе показывает сохранённое расписание.</p>
<p><a href="#" onclick="location.href='""" + SITE + """?app=1';return false" style="color:#f0a04b">Попробовать снова</a></p></body>"""
    }
}
