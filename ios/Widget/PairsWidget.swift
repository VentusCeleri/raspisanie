import AppIntents
import SwiftUI
import WidgetKit

// MARK: - Configuration ("Изменить виджет")

enum SubgroupOption: String, AppEnum {
    case first = "1", second = "2"

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Подгруппа"
    static var caseDisplayRepresentations: [SubgroupOption: DisplayRepresentation] = [
        .first: "1 подгруппа",
        .second: "2 подгруппа",
    ]

    var number: Int { self == .second ? 2 : 1 }
}

/// Mirrors ThemeID: raw values must match.
enum ThemeOption: String, AppEnum {
    case sand, ocean, forest, graphite, lilac, sunset, mint, contrast

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Тема"
    static var caseDisplayRepresentations: [ThemeOption: DisplayRepresentation] = [
        .sand: "Песок", .ocean: "Океан", .forest: "Лес", .graphite: "Графит",
        .lilac: "Сирень", .sunset: "Закат", .mint: "Мята", .contrast: "Контраст",
    ]

    var theme: ThemeID { ThemeID.from(rawValue) }
}

enum AppearanceOption: String, AppEnum {
    case system, light, dark

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Оформление"
    static var caseDisplayRepresentations: [AppearanceOption: DisplayRepresentation] = [
        .system: "Как в системе", .light: "Светлое", .dark: "Тёмное",
    ]

    var mode: AppearanceMode { AppearanceMode.from(rawValue) }
}

struct PairsConfigIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Пары"
    static var description = IntentDescription("Текущая и следующая пара с аудиторией.")

    @Parameter(title: "Подгруппа", default: .first)
    var subgroup: SubgroupOption

    @Parameter(title: "Тема", default: .sand)
    var theme: ThemeOption

    @Parameter(title: "Оформление", default: .system)
    var appearance: AppearanceOption
}

// MARK: - Timeline

struct PairsEntry: TimelineEntry {
    let date: Date
    let lessons: [TimedLesson]
    let hasData: Bool
    let offline: Bool
    let theme: ThemeID
    let appearance: AppearanceMode

    static func sample(_ date: Date = Date()) -> PairsEntry {
        let day = Schedule.iso(date)
        let raw = [
            Lesson(d: day, t: "10:15", s: "Исследование скважин и пластов", ty: "Лекция", r: "352", te: "Иванов И.И."),
            Lesson(d: day, t: "12:00", s: "Скважинная добыча нефти", ty: "Семинар", r: "219"),
            Lesson(d: day, t: "14:00", s: "НГП оборудование", ty: "ЛР", r: "П-10"),
        ]
        let data = ScheduleData(meta: .init(), lessons: raw)
        return PairsEntry(date: date,
                          lessons: data.timed(ScheduleFilter(subgroup: 1, showMilitary: false, includeCancelled: false)),
                          hasData: true, offline: false, theme: .sand, appearance: .system)
    }
}

struct PairsProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> PairsEntry { .sample() }

    func snapshot(for configuration: PairsConfigIntent, in context: Context) async -> PairsEntry {
        let result = context.isPreview ? LoadResult(data: ScheduleSource.cached(), offline: false)
                                       : await ScheduleSource.load()
        guard result.data != nil else {
            let s = PairsEntry.sample()
            return PairsEntry(date: s.date, lessons: s.lessons, hasData: true, offline: false,
                              theme: configuration.theme.theme, appearance: configuration.appearance.mode)
        }
        return entries(for: configuration, result: result, now: Date()).first!
    }

    func timeline(for configuration: PairsConfigIntent, in context: Context) async -> Timeline<PairsEntry> {
        let now = Date()
        let result = await ScheduleSource.load()
        let list = entries(for: configuration, result: result, now: now)
        // Refetch data hourly (sooner when offline); entries in between redraw at each class start/end.
        let reload = now.addingTimeInterval(result.offline ? 15 * 60 : 60 * 60)
        return Timeline(entries: list, policy: .after(reload))
    }

    private func entries(for config: PairsConfigIntent, result: LoadResult, now: Date) -> [PairsEntry] {
        let filter = ScheduleFilter(subgroup: config.subgroup.number, showMilitary: false, includeCancelled: false)
        let horizon = now.addingTimeInterval(14 * 24 * 3600)
        let lessons = (result.data?.timed(filter) ?? []).filter { $0.end > now && $0.start < horizon }

        var dates: Set<Date> = [now]
        let redrawUntil = now.addingTimeInterval(26 * 3600)
        for l in lessons {
            for d in [l.start, l.end] where d > now && d < redrawUntil { dates.insert(d.addingTimeInterval(1)) }
        }
        let midnight = Schedule.addDays(1, to: Schedule.startOfDay(now))
        dates.insert(midnight.addingTimeInterval(1))

        return dates.sorted().prefix(60).map {
            PairsEntry(date: $0, lessons: lessons, hasData: result.data != nil, offline: result.offline,
                       theme: config.theme.theme, appearance: config.appearance.mode)
        }
    }
}

// MARK: - Widget

@main
struct PairsWidgetBundle: WidgetBundle {
    var body: some Widget { PairsWidget() }
}

struct PairsWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "PairsWidget", intent: PairsConfigIntent.self, provider: PairsProvider()) { entry in
            PairsWidgetView(entry: entry)
        }
        .configurationDisplayName("Пары")
        .description("Текущая или следующая пара с аудиторией. Подгруппу и цвета можно поменять в «Изменить виджет».")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge,
                            .accessoryInline, .accessoryRectangular, .accessoryCircular])
    }
}

// MARK: - Views

struct PairsWidgetView: View {
    let entry: PairsEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var systemScheme

    private var c: Swatch { entry.theme.palette.colors(entry.appearance.colorScheme ?? systemScheme) }
    private var state: NowState { NowState(lessons: entry.lessons, now: entry.date) }
    private var isAccessory: Bool {
        family == .accessoryInline || family == .accessoryRectangular || family == .accessoryCircular
    }

    var body: some View {
        content
            .containerBackground(for: .widget) {
                if family == .accessoryCircular {
                    AccessoryWidgetBackground()
                } else if isAccessory {
                    Color.clear
                } else {
                    c.bg
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        if !entry.hasData {
            Text("Открой приложение «Пары» с интернетом")
                .font(.caption)
                .foregroundStyle(isAccessory ? Color.primary : c.ink)
        } else {
            switch family {
            case .accessoryInline: inline
            case .accessoryCircular: circular
            case .accessoryRectangular: rectangular
            case .systemSmall: small
            case .systemLarge: large
            default: medium
            }
        }
    }

    // Lock screen

    private var inline: some View {
        Group {
            if let f = state.focus {
                Text("\(f.lesson.t) · \(f.lesson.r) · \(Schedule.shortName(f.lesson.s))")
            } else {
                Text("Пар нет")
            }
        }
    }

    private var circular: some View {
        VStack(spacing: 0) {
            Text(state.focus?.lesson.t ?? "—").font(.system(size: 11))
            Text(state.focus?.lesson.r ?? "")
                .font(.system(size: 15, weight: .bold))
                .minimumScaleFactor(0.5)
                .lineLimit(1)
        }
        .padding(2)
    }

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 1) {
            if let f = state.focus {
                Text(state.current != nil ? "до \(f.endText) · ауд. \(f.lesson.r)" : "\(f.lesson.t) · ауд. \(f.lesson.r)")
                    .font(.headline)
                    .widgetAccentable()
                Text("\(Schedule.shortName(f.lesson.s)) (\(Schedule.typeLabel(f.lesson.ty)))")
                    .font(.caption)
                    .lineLimit(1)
                if state.current == nil {
                    if f.day == Schedule.startOfDay(entry.date) {
                        (Text("через ") + Text(f.start, style: .relative)).font(.caption)
                    } else {
                        Text(Schedule.relativeDay(f.start, now: entry.date)).font(.caption)
                    }
                }
            } else {
                Text("Пар больше нет").font(.headline)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // Home screen

    private var small: some View {
        VStack(alignment: .leading, spacing: 2) {
            focusBlock(big: false)
            Spacer(minLength: 0)
            if let n = state.rest.first {
                Text("потом \(n.lesson.t) · \(n.lesson.r)" + (state.rest.count > 1 ? "  +\(state.rest.count - 1)" : ""))
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(c.muted)
                    .lineLimit(1)
            }
            offlineNote
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var medium: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                focusBlock(big: false)
                Spacer(minLength: 0)
                offlineNote
            }
            .frame(width: 140, alignment: .leading)
            VStack(alignment: .leading, spacing: 6) {
                if !state.rest.isEmpty {
                    Text(restTitle).font(.system(size: 10, weight: .bold)).foregroundStyle(c.muted)
                    restList(state.rest, max: 4)
                } else if let d = state.followingDay {
                    Text(Schedule.relativeDay(d, now: entry.date).uppercased())
                        .font(.system(size: 10, weight: .bold)).foregroundStyle(c.muted)
                    restList(state.followingLessons, max: 4)
                } else {
                    Text(state.focus == nil ? "" : "больше пар нет").font(.caption).foregroundStyle(c.muted)
                }
                Spacer(minLength: 0)
            }
        }
    }

    private var large: some View {
        VStack(alignment: .leading, spacing: 6) {
            focusBlock(big: true)
            Text(restTitle).font(.system(size: 10, weight: .bold)).foregroundStyle(c.muted).padding(.top, 8)
            if state.rest.isEmpty {
                Text("больше пар нет").font(.caption).foregroundStyle(c.muted)
            } else {
                restList(state.rest, max: 5)
            }
            if let d = state.followingDay {
                Text(Schedule.dayLabel(d).uppercased())
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(c.muted)
                    .padding(.top, 8)
                restList(state.followingLessons, max: 4)
            }
            Spacer(minLength: 0)
            offlineNote
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var restTitle: String {
        guard let f = state.focus else { return "" }
        return f.day == Schedule.startOfDay(entry.date) ? "ДАЛЬШЕ СЕГОДНЯ" : "В ЭТОТ ДЕНЬ"
    }

    @ViewBuilder
    private func focusBlock(big: Bool) -> some View {
        if let f = state.focus {
            headline(f)
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(f.lesson.isExam ? c.exam : c.accent)
                .lineLimit(1)
            Text(f.lesson.r.isEmpty ? "—" : f.lesson.r)
                .font(.system(size: big ? 40 : 34, weight: .heavy, design: .rounded))
                .foregroundStyle(f.lesson.isExam ? c.exam : c.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            Text(Schedule.shortName(f.lesson.s))
                .font(.system(size: big ? 15 : 13, weight: .semibold))
                .foregroundStyle(c.ink)
                .lineLimit(2)
            Text(Schedule.typeLabel(f.lesson.ty) + (f.lesson.n > 1 ? " ×\(f.lesson.n)" : "")
                 + (f.lesson.te.isEmpty ? "" : " · " + (f.lesson.te.components(separatedBy: ",").first ?? "")))
                .font(.system(size: 11))
                .foregroundStyle(c.muted)
                .lineLimit(1)
            if !f.lesson.note.isEmpty {
                Text(f.lesson.note).font(.system(size: 10, weight: .semibold)).foregroundStyle(c.exam).lineLimit(1)
            }
        } else {
            Text("ПАР БОЛЬШЕ НЕТ").font(.system(size: 10, weight: .bold)).foregroundStyle(c.accent)
            Text("Каникулы 🎉").font(.system(size: 18, weight: .bold)).foregroundStyle(c.ink).padding(.top, 4)
        }
    }

    @ViewBuilder
    private func headline(_ f: TimedLesson) -> some View {
        if state.current != nil {
            Text("СЕЙЧАС · ДО \(f.endText)")
        } else if f.day == Schedule.startOfDay(entry.date) {
            Text("\(f.lesson.t) · ЧЕРЕЗ ") + Text(f.start, style: .relative)
        } else {
            Text("\(Schedule.relativeDay(f.start, now: entry.date).uppercased()) · \(f.lesson.t)")
        }
    }

    private func restList(_ items: [TimedLesson], max: Int) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            ForEach(Array(items.prefix(max))) { l in
                HStack(spacing: 6) {
                    Text(l.lesson.t)
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundStyle(c.muted)
                    Text(Schedule.shortName(l.lesson.s))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(c.ink)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(l.lesson.r)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(l.lesson.isExam ? c.exam : c.ink)
                        .lineLimit(1)
                }
            }
        }
    }

    @ViewBuilder
    private var offlineNote: some View {
        if entry.offline {
            Text("нет сети · последняя копия").font(.system(size: 9)).foregroundStyle(c.muted).lineLimit(1)
        }
    }
}
