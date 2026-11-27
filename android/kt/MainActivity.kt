package ru.ventusceleri.pary

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val settings: StateFlow<AppSettings> = Repository.settings(app)
    val data = MutableStateFlow(settings.value.groupId?.let { Repository.cached(app, it) })
    val offline = MutableStateFlow(false)
    val loading = MutableStateFlow(false)
    val groups = MutableStateFlow(Repository.cachedGroups(app))
    val update = MutableStateFlow<UpdateInfo?>(null)

    fun checkUpdate() = viewModelScope.launch { update.value = Repository.checkUpdate(ctx) }

    /** RuStore install → its page (it updates the app itself); side-loaded APK → download the new APK. */
    fun openUpdate(activity: android.content.Context) {
        val url = if (Repository.installer(ctx) == Repository.RUSTORE_PACKAGE) Repository.RUSTORE_PAGE else Repository.APK_URL
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    private var lastFetch = 0L

    fun change(f: (AppSettings) -> AppSettings) {
        val oldGroup = settings.value.groupId
        Repository.update(ctx, f)
        if (settings.value.groupId != oldGroup) {
            data.value = settings.value.groupId?.let { Repository.cached(ctx, it) }
            lastFetch = 0
            refresh()
        }
        WidgetUpdates.updateNow(ctx)
    }

    fun select(g: GroupInfo) = change { it.activate(GroupRef(g.id, g.code)) }
    fun activate(g: GroupRef) = change { it.activate(g) }
    fun remove(id: Int) = change { it.remove(id) }
    fun cycle(delta: Int) = change { it.cycle(delta) }

    fun refresh(force: Boolean = true) {
        val group = settings.value.groupId ?: return
        if (loading.value) return
        if (!force && System.currentTimeMillis() - lastFetch < 10 * 60_000) return
        viewModelScope.launch {
            loading.value = true
            val r = Repository.load(ctx, group)
            if (group == settings.value.groupId) {
                r.data?.let { data.value = it }
                offline.value = r.offline
                if (!r.offline) {
                    lastFetch = System.currentTimeMillis()
                    WidgetUpdates.updateNow(ctx)
                }
            }
            loading.value = false
        }
    }

    fun loadGroups() = viewModelScope.launch {
        val list = Repository.loadGroups(ctx)
        if (list.isNotEmpty()) groups.value = list
        // Group chosen by id only (screenshot launch extras): fill in its code from the list.
        val s = settings.value
        if (s.groupId != null && s.groupCode == null) {
            list.firstOrNull { it.id == s.groupId }?.let { g ->
                Repository.update(ctx) { st -> st.copy(groupCode = g.code,
                    savedGroups = st.savedGroups.map { if (it.id == g.id) GroupRef(g.id, g.code) else it }) }
            }
        }
    }
}

private enum class Screen { MAIN, SETTINGS, GROUPS }

var openSettingsOnStart = false

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLaunchExtras()
        WidgetUpdates.schedule(this)
        setContent {
            val vm: AppViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = settings.appearanceMode.isDark(isSystemInDarkTheme())
            val c = settings.themeId.palette.colors(dark)
            LaunchedEffect(dark, c) {
                val bar = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                          else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(bar, bar)
            }
            LaunchedEffect(Unit) {
                vm.refresh()
                vm.checkUpdate()
                if (settings.groupId != null && settings.groupCode == null) vm.loadGroups()
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = c.accent) else lightColorScheme(primary = c.accent)) {
                App(vm, settings, c)
            }
        }
    }

    /** Used by the screenshot workflow: `am start --ei groupId 9209 --es theme ocean --es appearance dark --ez openSettings true`. */
    private fun applyLaunchExtras() {
        val e = intent?.extras ?: return
        Repository.update(this) { s ->
            var n = s
            if (e.containsKey("groupId")) n = n.copy(groupId = e.getInt("groupId"), groupCode = null)
            e.getString("theme")?.let { n = n.copy(theme = it) }
            e.getString("appearance")?.let { n = n.copy(appearance = it) }
            n
        }
        openSettingsOnStart = e.getBoolean("openSettings", false)
    }

    override fun onResume() {
        super.onResume()
        WidgetUpdates.updateNow(this)
    }
}

@Composable
private fun App(vm: AppViewModel, s: AppSettings, c: Swatch) {
    var screen by remember { mutableStateOf(if (openSettingsOnStart) Screen.SETTINGS else Screen.MAIN) }
    val needsGroup = s.groupId == null
    Box(Modifier.fillMaxSize().background(c.bg)) {
        when {
            needsGroup -> GroupPicker(vm, c, s, onboarding = true) {}
            screen == Screen.GROUPS -> GroupPicker(vm, c, s, onboarding = false) { screen = Screen.SETTINGS }
            screen == Screen.SETTINGS -> SettingsScreen(vm, s, c, onGroups = { screen = Screen.GROUPS }) { screen = Screen.MAIN }
            else -> MainScreen(vm, s, c, openSettings = { screen = Screen.SETTINGS },
                               openGroups = { screen = Screen.GROUPS })
        }
    }
    BackHandler(enabled = !needsGroup && screen != Screen.MAIN) {
        screen = if (screen == Screen.GROUPS) Screen.SETTINGS else Screen.MAIN
    }
}

// ---------------------------------------------------------------- main

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: AppViewModel, s: AppSettings, c: Swatch, openSettings: () -> Unit, openGroups: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    var selected by remember { mutableStateOf(Schedule.today()) }

    val all = remember(data, s) {
        data?.timed(ScheduleFilter(s.subgroup, s.showMilitary, includeCancelled = true)) ?: emptyList()
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Сегодня", c) { selected = Schedule.today() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                GroupTitle(vm, s, c, onAdd = openGroups)
            }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            IconButton(onClick = { shareApp(ctx) }, Modifier.clip(CircleShape).background(c.card)) {
                Icon(Icons.Default.Share, contentDescription = "Поделиться приложением", tint = c.accent)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = openSettings, Modifier.clip(CircleShape).background(c.card)) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки и оформление", tint = c.accent)
            }
        }
        PullToRefreshBox(isRefreshing = loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                       verticalArrangement = Arrangement.spacedBy(16.dp)) {
                update?.let { u -> item { UpdateBanner(u, c) { vm.openUpdate(ctx) } } }
                if (data == null) {
                    item { EmptyState(loading, c) { vm.refresh() } }
                } else {
                    item { NowCard(all.filter { !it.lesson.isCancelled }, now, c) }
                    item { WeekStrip(selected, { selected = it }, all, data?.meta?.upperMonday, c) }
                    item {
                        val d = Schedule.weekdayFull[selected.dayOfWeek.value - 1]
                        Text("$d, ${Schedule.longDate(selected)}", color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    val day = all.filter { it.day == selected }
                    if (day.isEmpty()) item { Text("Пар нет", color = c.muted) }
                    itemsIndexed(day, key = { i, l -> "d$i|${l.id}" }) { _, l -> LessonRow(l, now, c) }
                    val exams = all.filter { it.lesson.isExam && it.end > now }
                    if (exams.isNotEmpty()) {
                        item { Text("Сессия", color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        itemsIndexed(exams, key = { i, l -> "e$i|${l.id}" }) { _, l -> ExamRow(l, c) }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        data?.meta?.updated?.takeIf { it.isNotEmpty() }?.let { Small("Обновлено с ЛК: $it", c) }
                        if (offline && data != null) Small("Нет сети, показана последняя копия", c)
                        Small("${s.subgroup} подгруппа · группа и подгруппа меняются в настройках", c)
                    }
                }
            }
        }
    }
}

/** Group code in the header: swipe left/right to switch followed groups, tap for a menu. */
@Composable
private fun GroupTitle(vm: AppViewModel, s: AppSettings, c: Swatch, onAdd: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val groups = s.groups
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable { menu = true }
            .pointerInput(groups.size, s.groupId) {
                var total = 0f
                detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                    if (total < -80) vm.cycle(1) else if (total > 80) vm.cycle(-1)
                }) { _, dx -> total += dx }
            }
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text((s.groupCode ?: "Пары") + " ▾", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        if (groups.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                groups.forEach { g ->
                    Box(Modifier.size(5.dp).clip(CircleShape)
                        .background(if (g.id == s.groupId) c.accent else c.muted.copy(alpha = 0.4f)))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.card) {
            groups.forEach { g ->
                DropdownMenuItem(text = { Text(g.code, color = c.ink) }, onClick = { menu = false; vm.activate(g) },
                    trailingIcon = { if (g.id == s.groupId) Icon(Icons.Default.Check, null, tint = c.accent) })
            }
            HorizontalDivider(color = c.line)
            DropdownMenuItem(text = { Text("+ Добавить группу", color = c.accent) }, onClick = { menu = false; onAdd() })
        }
    }
}

@Composable
private fun UpdateBanner(u: UpdateInfo, c: Swatch, onUpdate: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accent.copy(alpha = 0.14f))
        .border(1.dp, c.accent, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Доступна новая версия ${u.versionName}", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("Исправления и новые функции", color = c.muted, fontSize = 13.sp)
        }
        Button(onClick = onUpdate, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)) {
            Text("Обновить", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun Small(t: String, c: Swatch) = Text(t, color = c.muted, fontSize = 13.sp)

@Composable
private fun Pill(text: String, c: Swatch, onClick: () -> Unit) {
    Text(text, color = c.accent, fontSize = 16.sp,
         modifier = Modifier.clip(RoundedCornerShape(50)).background(c.card).clickable(onClick = onClick)
             .padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun EmptyState(loading: Boolean, c: Swatch, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) {
            CircularProgressIndicator(color = c.accent)
            Text("Загружаю расписание…", color = c.muted)
        } else {
            Text("Нет данных. Подключись к интернету и потяни экран вниз.", color = c.ink)
            Button(onClick = retry, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text("Повторить") }
        }
    }
}

@Composable
private fun Card(c: Swatch, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card)
        .border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
private fun NowCard(lessons: List<TimedLesson>, now: Instant, c: Swatch) {
    val st = NowState(lessons, now)
    val f = st.focus
    Card(c) {
        if (f == null) {
            Text("Пар больше нет", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text("В расписании впереди пусто — каникулы или данные ещё не выложены.", color = c.muted)
            return@Card
        }
        val l = f.lesson
        val head = when {
            st.current != null -> "СЕЙЧАС · ДО ${f.endText}"
            f.day == Schedule.today() -> "СЛЕДУЮЩАЯ · ЧЕРЕЗ ${Schedule.until(now, f.start).uppercase()}"
            else -> "СЛЕДУЮЩАЯ · ${Schedule.relativeDay(f.day).uppercase()} В ${l.t}"
        }
        Text(head, color = if (l.isExam) c.exam else c.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(l.r.ifEmpty { "—" }, color = if (l.isExam) c.exam else c.ink, fontSize = 44.sp,
                 fontWeight = FontWeight.Black, maxLines = 1, modifier = Modifier.weight(1f))
            Text("${l.t}–${f.endText}", color = c.muted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(l.s, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        val sub = listOfNotNull(l.ty.ifEmpty { null }, if (l.n > 1) "×${l.n}" else null, l.te.ifEmpty { null },
                                if (l.sg != 0) "${l.sg} подгр." else null).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, color = c.muted, fontSize = 15.sp)
        if (l.note.isNotEmpty()) Text(l.note, color = c.exam, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (st.rest.isNotEmpty()) {
            HorizontalDivider(color = c.line)
            Text(st.rest.joinToString("   ") { "${it.lesson.t} · ${it.lesson.r}" }, color = c.muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun WeekStrip(selected: LocalDate, onSelect: (LocalDate) -> Unit, lessons: List<TimedLesson>,
                      upperMonday: String?, c: Swatch) {
    val monday = Schedule.monday(selected)
    val hasSunday = lessons.any { it.day == monday.plusDays(6) }
    val days = (0 until if (hasSunday) 7 else 6).map { monday.plusDays(it.toLong()) }
    val today = Schedule.today()
    Column(Modifier.pointerInput(selected) {
        var total = 0f
        detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
            if (total < -120) onSelect(selected.plusWeeks(1)) else if (total > 120) onSelect(selected.minusWeeks(1))
        }) { _, dx -> total += dx }
    }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelect(selected.minusWeeks(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Прошлая неделя", tint = c.accent)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${Schedule.shortDate(monday)} – ${Schedule.shortDate(days.last())}", color = c.ink,
                     fontWeight = FontWeight.SemiBold)
                Schedule.isUpperWeek(monday, upperMonday)?.let { up ->
                    Text(if (up) "верхняя неделя" else "нижняя неделя", color = if (up) c.accent else c.muted, fontSize = 13.sp)
                }
            }
            IconButton(onClick = { onSelect(selected.plusWeeks(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующая неделя", tint = c.accent)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { d ->
                val isSel = d == selected
                val count = lessons.count { it.day == d && !it.lesson.isCancelled }
                val fg = if (isSel) c.bg else if (d == today) c.accent else c.ink
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (isSel) c.accent else c.card)
                    .clickable { onSelect(d) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(Schedule.weekdayShort[d.dayOfWeek.value - 1], color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${d.dayOfMonth}", color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.size(5.dp).clip(CircleShape)
                        .background(if (count == 0) Color.Transparent else if (isSel) c.bg else c.accent))
                }
            }
        }
    }
}

@Composable
private fun LessonRow(item: TimedLesson, now: Instant, c: Swatch) {
    val l = item.lesson
    val strike = if (l.isCancelled) TextDecoration.LineThrough else null
    val dim = if (l.isCancelled) 0.55f else if (item.end < now) 0.6f else 1f
    Row(Modifier.fillMaxWidth().alpha(dim).clip(RoundedCornerShape(14.dp)).background(c.card).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(48.dp)) {
            Text(l.t, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(item.endText, color = c.muted, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(l.s, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textDecoration = strike)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (l.ty.isNotEmpty()) Tag(l.ty, if (l.isExam) c.exam else c.accent)
                if (l.sg != 0) Tag("${l.sg} подгр.", c.muted)
                if (l.mil) Tag("военка", c.muted)
            }
            if (l.te.isNotEmpty()) Text(l.te, color = c.muted, fontSize = 13.sp)
            if (l.note.isNotEmpty()) Text(l.note, color = c.exam, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(l.r.ifEmpty { "—" }, color = if (l.isExam) c.exam else c.ink, fontSize = 20.sp,
             fontWeight = FontWeight.Black, textDecoration = strike)
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
         modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
             .padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
private fun ExamRow(e: TimedLesson, c: Swatch) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Schedule.dayLabel(e.day), color = c.exam, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(e.lesson.s, color = c.ink, fontWeight = FontWeight.SemiBold)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(e.lesson.t, color = c.muted)
            Text(e.lesson.r, color = c.ink, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsScreen(vm: AppViewModel, s: AppSettings, c: Swatch, onGroups: () -> Unit, close: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dark = s.appearanceMode.isDark(isSystemInDarkTheme())
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Настройки", Modifier.weight(1f), color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Pill("Готово", c, close)
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Header("Расписание", c) }
            item {
                Card(c) {
                    Text("Мои группы", color = c.muted, fontSize = 13.sp)
                    s.groups.forEach { g ->
                        Row(Modifier.fillMaxWidth().clickable { vm.activate(g) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(g.code, color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f),
                                 fontWeight = if (g.id == s.groupId) FontWeight.SemiBold else FontWeight.Normal)
                            if (g.id == s.groupId) Icon(Icons.Default.Check, null, tint = c.accent)
                            if (s.groups.size > 1) Text("  ✕", color = c.muted, fontSize = 16.sp,
                                                        modifier = Modifier.clickable { vm.remove(g.id) })
                        }
                    }
                    Text("+ Добавить группу", color = c.accent, fontSize = 16.sp,
                         modifier = Modifier.fillMaxWidth().clickable(onClick = onGroups).padding(vertical = 6.dp))
                    Text("Переключать группы — свайпом по названию вверху главного экрана", color = c.muted, fontSize = 12.sp)
                    HorizontalDivider(color = c.line)
                    Segmented(listOf("1 подгруппа", "2 подгруппа"), s.subgroup - 1, c) { i -> vm.change { it.copy(subgroup = i + 1) } }
                    HorizontalDivider(color = c.line)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Показывать военку", color = c.ink, modifier = Modifier.weight(1f), fontSize = 16.sp)
                        Switch(s.showMilitary, { v -> vm.change { it.copy(showMilitary = v) } },
                               colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
                    }
                }
            }
            item { Header("Тема", c) }
            item {
                Card(c) {
                    LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 400.dp),
                                     horizontalArrangement = Arrangement.spacedBy(10.dp),
                                     verticalArrangement = Arrangement.spacedBy(10.dp), userScrollEnabled = false) {
                        items(ThemeId.entries) { t ->
                            ThemeSwatch(t, dark, t == s.themeId, c.ink) { vm.change { it.copy(theme = t.key) } }
                        }
                    }
                    HorizontalDivider(color = c.line)
                    Text("Оформление", color = c.ink, fontSize = 16.sp)
                    Segmented(Appearance.entries.map { it.title }, s.appearanceMode.ordinal, c) { i ->
                        vm.change { it.copy(appearance = Appearance.entries[i].key) }
                    }
                }
            }
            item { Header("Виджет", c) }
            item {
                Card(c) {
                    Text("Виджет берёт группу, подгруппу и тему отсюда. Добавить: долгое нажатие на рабочий стол → Виджеты → «Пары».",
                         color = c.ink, fontSize = 14.sp)
                }
            }
            item { Header("Автор", c) }
            item {
                Card(c) {
                    Text("Поделиться приложением", color = c.accent, fontSize = 16.sp,
                         modifier = Modifier.fillMaxWidth().clickable { shareApp(ctx) }.padding(vertical = 6.dp))
                    HorizontalDivider(color = c.line)
                    Text("Канал автора — Нейросети с нуля", color = c.accent, fontSize = 16.sp,
                         modifier = Modifier.fillMaxWidth().clickable {
                             ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Repository.AUTHOR_CHANNEL)))
                         }.padding(vertical = 6.dp))
                }
            }
            item { Header("О приложении", c) }
            item {
                Card(c) {
                    data?.meta?.updated?.takeIf { it.isNotEmpty() }?.let { Text("Данные из ЛК: $it", color = c.ink) }
                    Text("Сайт расписания", color = c.accent, modifier = Modifier.clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Repository.SITE)))
                    })
                    Text("Политика конфиденциальности", color = c.accent, modifier = Modifier.clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Repository.SITE + "privacy.html")))
                    })
                    Text("Версия ${BuildConfigCompat.versionName(ctx)}", color = c.muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable private fun Header(t: String, c: Swatch) = Text(t, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Segmented(options: List<String>, selected: Int, c: Swatch, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.line).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Text(o, color = if (i == selected) c.ink else c.muted, fontSize = 14.sp, maxLines = 1,
                 overflow = TextOverflow.Ellipsis, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                 textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                 modifier = Modifier.weight(1f).clip(RoundedCornerShape(50))
                     .background(if (i == selected) c.card else Color.Transparent)
                     .clickable { onSelect(i) }.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun ThemeSwatch(t: ThemeId, dark: Boolean, selected: Boolean, label: Color, onClick: () -> Unit) {
    val s = t.palette.colors(dark)
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp)).background(s.bg)
            .border(if (selected) 3.dp else 1.dp, if (selected) s.accent else s.line, RoundedCornerShape(12.dp))
            .padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
            Box(Modifier.width(26.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(s.accent))
            Box(Modifier.width(34.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(s.ink))
            Box(Modifier.width(20.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(s.muted))
        }
        Text(t.title, fontSize = 12.sp, color = label, maxLines = 1)
    }
}

// ---------------------------------------------------------------- groups

@Composable
private fun GroupPicker(vm: AppViewModel, c: Swatch, s: AppSettings, onboarding: Boolean, close: () -> Unit) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { vm.loadGroups().join(); loading = false }
    val filtered = remember(groups, query) { groups.filter { it.matches(query) } }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (onboarding) "Твоя группа" else "Группа", Modifier.weight(1f), color = c.ink, fontSize = 20.sp,
                 fontWeight = FontWeight.Bold)
            if (!onboarding) Pill("Отмена", c, close)
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Например, РН-23-04") }, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line,
                focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent))
        if (groups.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                   verticalArrangement = Arrangement.Center) {
                if (loading) CircularProgressIndicator(color = c.accent)
                else {
                    Text("Не удалось загрузить список групп. Проверь интернет.", color = c.ink)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { loading = true; vm.loadGroups().invokeOnCompletion { loading = false } },
                           colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text("Повторить") }
                }
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (query.isBlank()) {
                filtered.groupBy { it.f.ifEmpty { "Без факультета" } }.toSortedMap().forEach { (faculty, list) ->
                    item(key = "h$faculty") {
                        Text(faculty.uppercase(), color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                             modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                    }
                    items(list, key = { it.id }) { GroupRow(it, s, c, showFaculty = false) { vm.select(it); close() } }
                }
            } else {
                if (filtered.isEmpty()) item { Text("Нет такой группы", color = c.muted) }
                items(filtered.take(200), key = { it.id }) { GroupRow(it, s, c, showFaculty = true) { vm.select(it); close() } }
            }
        }
    }
}

@Composable
private fun GroupRow(g: GroupInfo, s: AppSettings, c: Swatch, showFaculty: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.card).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(g.code, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (showFaculty && g.f.isNotEmpty()) Text(g.f, color = c.muted, fontSize = 12.sp, maxLines = 1,
                                                      overflow = TextOverflow.Ellipsis)
        }
        if (g.id == s.groupId) Icon(Icons.Default.Check, null, tint = c.accent)
    }
}

private fun shareApp(ctx: android.content.Context) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Пары РГУ")
        putExtra(Intent.EXTRA_TEXT, Repository.SHARE_MESSAGE + " " + Repository.SHARE_URL)
    }
    ctx.startActivity(Intent.createChooser(send, "Поделиться приложением"))
}

object BuildConfigCompat {
    fun versionName(ctx: android.content.Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}

