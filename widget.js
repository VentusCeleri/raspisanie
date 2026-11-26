// Variables used by Scriptable.
// These must be at the top of the file. Do not edit.
// icon-color: deep-blue; icon-glyph: calendar-alt;
// Виджет «Пары» — расписание РН-23-04 с сайта ventusceleri.github.io/raspisanie
// Данные сайт обновляет сам 2 раза в день. Без интернета виджет берёт последнюю копию.
// Подгруппа: параметр виджета (долгое нажатие → «Изменить виджет» → Parameter): 1 или 2. По умолчанию 1.

const SITE = "https://ventusceleri.github.io/raspisanie/";
const PG = Number((args.widgetParameter || "").trim()) === 2 ? 2 : 1;
const SLOTS = [["8:30","10:00"],["10:15","11:45"],["12:00","13:30"],["14:00","15:30"],["15:45","17:15"],["17:20","18:50"],["18:55","20:25"],["20:30","22:00"]];
const WD = ["Вс","Пн","Вт","Ср","Чт","Пт","Сб"];
const MON = ["янв","фев","мар","апр","мая","июн","июл","авг","сен","окт","ноя","дек"];
const SHORT = {
  "Скважинная добыча нефти": "СДН",
  "Проектирование разработки (Petrel)": "Проектирование",
  "Проектирование разработки месторождений в виртуальной среде профессиональной деятельности": "Проектирование",
  "Исследование скважин и пластов": "Исследование скважин",
  "Управление продуктивностью скважин": "УПС",
  "Система АРМ в добыче нефти": "Система АРМ",
  "Системы сбора и подготовки": "Системы сбора",
  "Основы промысловой геофизики": "Геофизика",
  "НГП оборудование": "НГП оборудование",
  "Нефтегазопромысловое оборудование": "НГП оборудование",
  "Морские НГ проекты": "Морские проекты",
  "Перспективные проекты освоения морских нефтегазовых ресурсов": "Морские проекты",
};
const TY = { "Лекция": "лек", "Семинар": "сем", "ЛР": "лаб", "Экзамен": "ЭКЗАМЕН", "Консультация": "конс", "Мероприятие": "меропр" };

// ---------- цвета (светлая / тёмная тема) ----------
const C = {
  bg: Color.dynamic(new Color("#f4efe6"), new Color("#0b1622")),
  ink: Color.dynamic(new Color("#14212e"), new Color("#ece6da")),
  muted: Color.dynamic(new Color("#6b7480"), new Color("#93a0ad")),
  accent: Color.dynamic(new Color("#c96f12"), new Color("#f0a04b")),
  exam: Color.dynamic(new Color("#b4372b"), new Color("#ff7b6b")),
  line: Color.dynamic(new Color("#e4dccd"), new Color("#223344")),
};

// ---------- данные ----------
const fm = FileManager.local();
const cachePath = fm.joinPath(fm.documentsDirectory(), "raspisanie-data.json");

async function loadData() {
  try {
    const r = new Request(SITE + "data.json?t=" + Date.now());
    r.timeoutInterval = 8;
    const j = await r.loadJSON();
    if (j && Array.isArray(j.lessons)) {
      fm.writeString(cachePath, JSON.stringify(j));
      return { data: j, offline: false };
    }
  } catch (e) {}
  if (fm.fileExists(cachePath)) return { data: JSON.parse(fm.readString(cachePath)), offline: true };
  return { data: null, offline: true };
}

const mins = t => { const [h, m] = t.split(":").map(Number); return h * 60 + m; };
const hhmm = m => Math.floor(m / 60) + ":" + String(m % 60).padStart(2, "0");
function endTime(l) {
  const i = SLOTS.findIndex(s => s[0] === l.t);
  if (i >= 0) return SLOTS[Math.min(i + (l.n || 1) - 1, SLOTS.length - 1)][1];
  return hhmm(mins(l.t) + (l.n || 1) * 90 + ((l.n || 1) - 1) * 15);
}
function at(dIso, t) { const [y, mo, d] = dIso.split("-").map(Number); const [h, mi] = t.split(":").map(Number); return new Date(y, mo - 1, d, h, mi); }
const isoOf = d => d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
const dayLabel = dIso => { const [y, mo, d] = dIso.split("-").map(Number); const x = new Date(y, mo - 1, d); return WD[x.getDay()] + ", " + d + " " + MON[mo - 1]; };
const shortS = s => SHORT[s] || s;

function prepare(data) {
  return (data.lessons || [])
    .filter(l => !l.mil && !l.st && (l.sg === 0 || l.sg === undefined || l.sg === PG))
    .map(l => Object.assign({}, l, { start: at(l.d, l.t), end: at(l.d, endTime(l)) }))
    .sort((a, b) => a.start - b.start);
}

// ---------- что показывать ----------
function state(lessons, now) {
  const cur = lessons.find(l => l.start <= now && now < l.end) || null;
  const upcoming = lessons.filter(l => l.start > now);
  const next = upcoming[0] || null;
  const focus = cur || next;
  const today = isoOf(now);
  // остаток дня, в который попадает главная пара (сегодня или ближайший учебный день)
  const dayIso = focus ? focus.d : today;
  const rest = lessons.filter(l => l.d === dayIso && l.end > now && l !== focus);
  return { cur, next, focus, rest, today };
}

function headline(s, now) {
  const f = s.focus;
  if (!f) return "Пар больше нет";
  if (s.cur) return "Сейчас · до " + endTime(f);
  if (f.d === s.today) return null; // относительное время «через …» рисуем отдельно
  const tomorrow = isoOf(new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1));
  return (f.d === tomorrow ? "Завтра" : dayLabel(f.d)) + " · " + f.t;
}

// ---------- отрисовка ----------
function txt(stack, s, font, color, lines) {
  const t = stack.addText(s);
  t.font = font; t.textColor = color;
  if (lines) t.lineLimit = lines;
  t.minimumScaleFactor = 0.6;
  return t;
}
function header(stack, s, now) {
  const h = headline(s, now);
  const row = stack.addStack(); row.centerAlignContent();
  if (h) { txt(row, h.toUpperCase(), Font.boldSystemFont(10), s.focus && s.focus.ty === "Экзамен" ? C.exam : C.accent, 1); return; }
  const a = txt(row, "СЛЕДУЮЩАЯ · " + s.focus.t + " · ", Font.boldSystemFont(10), C.accent, 1);
  const d = row.addDate(s.focus.start); d.applyRelativeStyle(); d.font = Font.boldSystemFont(10); d.textColor = C.accent; d.lineLimit = 1;
}
function focusBlock(stack, s, now, big) {
  header(stack, s, now);
  const f = s.focus;
  if (!f) { stack.addSpacer(4); txt(stack, "Каникулы 🎉", Font.boldSystemFont(18), C.ink); return; }
  stack.addSpacer(2);
  txt(stack, f.r || "—", Font.heavySystemFont(big ? 40 : 34), f.ty === "Экзамен" ? C.exam : C.ink, 1);
  txt(stack, shortS(f.s), Font.semiboldSystemFont(big ? 15 : 13), C.ink, 2);
  txt(stack, (TY[f.ty] || f.ty) + (f.n > 1 ? " ×" + f.n : "") + " · " + (f.te || "").split(",")[0], Font.systemFont(11), C.muted, 1);
  if (f.note) txt(stack, f.note, Font.semiboldSystemFont(10), C.exam, 1);
}
function restList(stack, items, max) {
  items.slice(0, max).forEach((l, i) => {
    if (i) stack.addSpacer(5);
    const row = stack.addStack(); row.centerAlignContent();
    const tm = txt(row, l.t, Font.boldMonospacedSystemFont(12), C.muted, 1);
    row.addSpacer(6);
    txt(row, shortS(l.s), Font.mediumSystemFont(12), C.ink, 1);
    row.addSpacer();
    txt(row, l.r || "", Font.boldSystemFont(13), l.ty === "Экзамен" ? C.exam : C.ink, 1);
  });
}

function buildWidget(lessons, offline, now, family) {
  const s = state(lessons, now);
  const w = new ListWidget();
  w.url = SITE + "?pg=" + PG;
  // перерисовать к началу/концу ближайшей пары, но не реже чем раз в 30 мин
  const marks = lessons.flatMap(l => [l.start, l.end]).filter(d => d > now).sort((a, b) => a - b);
  const soon = new Date(now.getTime() + 30 * 60000);
  w.refreshAfterDate = marks.length && marks[0] < soon ? new Date(marks[0].getTime() + 1000) : soon;

  if (family === "accessoryInline") {
    const f = s.focus;
    w.addText(f ? (f.t + " · " + (f.r || "") + " · " + shortS(f.s)) : "Пар нет");
    return w;
  }
  if (family === "accessoryCircular") {
    w.addAccessoryWidgetBackground = true;
    const f = s.focus;
    const st = w.addStack(); st.layoutVertically(); st.centerAlignContent();
    const a = st.addText(f ? f.t : "—"); a.font = Font.systemFont(10); a.centerAlignText();
    const b = st.addText(f ? (f.r || "—") : ""); b.font = Font.boldSystemFont(14); b.minimumScaleFactor = 0.5; b.centerAlignText();
    return w;
  }
  if (family === "accessoryRectangular") {
    const f = s.focus;
    if (!f) { w.addText("Пар больше нет"); return w; }
    const l1 = w.addText((s.cur ? "до " + endTime(f) : f.t) + " · ауд. " + (f.r || "—")); l1.font = Font.boldSystemFont(14);
    const l2 = w.addText(shortS(f.s) + " (" + (TY[f.ty] || f.ty) + ")"); l2.font = Font.systemFont(12); l2.lineLimit = 1;
    if (!s.cur && f.d === s.today) { const d = w.addDate(f.start); d.applyRelativeStyle(); d.font = Font.systemFont(12); }
    else if (!s.cur) { const l3 = w.addText(headline(s, now)); l3.font = Font.systemFont(12); }
    return w;
  }

  w.backgroundColor = C.bg;
  w.setPadding(14, 14, 12, 14);
  if (family === "small") {
    focusBlock(w, s, now, false);
    w.addSpacer();
    if (s.rest.length) {
      const n = s.rest[0];
      txt(w, "потом " + n.t + " · " + (n.r || "") + (s.rest.length > 1 ? "  +" + (s.rest.length - 1) : ""), Font.mediumSystemFont(10), C.muted, 1);
    }
  } else if (family === "medium") {
    const row = w.addStack();
    const left = row.addStack(); left.layoutVertically(); left.size = new Size(140, 0);
    focusBlock(left, s, now, false);
    left.addSpacer();
    row.addSpacer(12);
    const right = row.addStack(); right.layoutVertically();
    txt(right, s.focus ? (s.focus.d === s.today ? "ДАЛЬШЕ СЕГОДНЯ" : "В ЭТОТ ДЕНЬ") : "", Font.boldSystemFont(10), C.muted, 1);
    right.addSpacer(6);
    if (s.rest.length) restList(right, s.rest, 4); else txt(right, s.focus ? "больше пар нет" : "", Font.systemFont(12), C.muted);
    right.addSpacer();
  } else { // large
    focusBlock(w, s, now, true);
    w.addSpacer(10);
    txt(w, s.focus ? (s.focus.d === s.today ? "ДАЛЬШЕ СЕГОДНЯ" : "В ЭТОТ ДЕНЬ") : "", Font.boldSystemFont(10), C.muted, 1);
    w.addSpacer(6);
    if (s.rest.length) restList(w, s.rest, 5); else txt(w, "больше пар нет", Font.systemFont(12), C.muted);
    // следующий учебный день после этого
    const after = lessons.filter(l => s.focus && l.d > s.focus.d);
    if (after.length) {
      const d = after[0].d;
      w.addSpacer(10);
      txt(w, dayLabel(d).toUpperCase(), Font.boldSystemFont(10), C.muted, 1);
      w.addSpacer(6);
      restList(w, after.filter(l => l.d === d), 4);
    }
    w.addSpacer();
  }
  if (offline) { w.addSpacer(2); txt(w, "нет сети · последняя копия", Font.systemFont(9), C.muted, 1); }
  return w;
}

// ---------- запуск ----------
const { data, offline } = await loadData();
const now = new Date();
let widget;
if (!data) {
  widget = new ListWidget();
  widget.backgroundColor = C.bg;
  txt(widget, "Нет данных. Открой виджет с интернетом.", Font.systemFont(12), C.ink);
} else {
  widget = buildWidget(prepare(data), offline, now, config.widgetFamily || "medium");
}
if (config.runsInWidget) {
  Script.setWidget(widget);
} else {
  // запуск в самом приложении — показать превью
  await widget.presentMedium();
}
Script.complete();
