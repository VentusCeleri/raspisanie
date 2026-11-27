import AppIntents
import SwiftUI
import WidgetKit

// MARK: - Configuration ("Изменить виджет")

struct GroupEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Группа"
    static var defaultQuery = GroupQuery()

    let id: Int
    let code: String
    let faculty: String

    var displayRepresentation: DisplayRepresentation {
        faculty.isEmpty ? DisplayRepresentation(title: "\(code)")
                        : DisplayRepresentation(title: "\(code)", subtitle: "\(faculty)")
    }

    init(_ g: GroupInfo) { id = g.id; code = g.code; faculty = g.f }
}

struct GroupQuery: EntityStringQuery {
    private func all() async -> [GroupInfo] {
        let cached = ScheduleSource.cachedGroups()
        return cached.isEmpty ? await ScheduleSource.loadGroups() : cached
    }

    func entities(for identifiers: [Int]) async throws -> [GroupEntity] {
        let list = await all()
        return identifiers.compactMap { id in list.first { $0.id == id }.map(GroupEntity.init) }
    }

    func entities(matching string: String) async throws -> [GroupEntity] {
        await all().filter { $0.matches(string) }.prefix(60).map(GroupEntity.init)
    }

    func suggestedEntities() async throws -> [GroupEntity] {
        let list = await all()
        let mine = SettingsStore.load().groupId
        let first = list.filter { $0.id == mine }
        return (first + list.filter { $0.id != mine }).prefix(60).map(GroupEntity.init)
    }
}

enum SubgroupOption: String, AppEnum {
    case app, first = "1", second = "2"

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Подгруппа"
    static var caseDisplayRepresentations: [SubgroupOption: DisplayRepresentation] = [
        .app: "Как в приложении",
        .first: "1 подгруппа",
        .second: "2 подгруппа",
    ]

    func resolve(_ s: AppSettings) -> Int {
        switch self {
        case .app: return s.subgroup
        case .first: return 1
        case .second: return 2
        }
    }
}

/// Mirrors ThemeID (raw values must match) plus "as in the app".
enum ThemeOption: String, AppEnum {
    case app, sand, ocean, forest, graphite, lilac, sunset, mint, contrast

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Тема"
    static var caseDisplayRepresentations: [ThemeOption: DisplayRepresentation] = [
        .app: "Как в приложении",
        .sand: "Песок", .ocean: "Океан", .forest: "Лес", .graphite: "Графит",
        .lilac: "Сирень", .sunset: "Закат", .mint: "Мята", .contrast: "Контраст",
    ]

    func resolve(_ s: AppSettings) -> ThemeID { self == .app ? s.themeID : ThemeID.from(rawValue) }
}

enum AppearanceOption: String, AppEnum {
    case app, system, light, dark

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Оформление"
    static var caseDisplayRepresentations: [AppearanceOption: DisplayRepresentation] = [
        .app: "Как в приложении", .system: "Как в системе", .light: "Светлое", .dark: "Тёмное",
    ]

    func resolve(_ s: AppSettings) -> AppearanceMode {
        self == .app ? s.appearanceMode : AppearanceMode.from(rawValue)
    }
}

struct PairsConfigIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Пары"
    static var description = IntentDescription("Текущая и следующая пара с аудиторией.")

    @Parameter(title: "Группа", description: "Пусто — как в приложении")
    var group: GroupEntity?

    @Parameter(title: "Подгруппа", default: .app)
    var subgroup: SubgroupOption

    @Parameter(title: "Тема", default: .app)
    var theme: ThemeOption

    @Parameter(title: "Оформление", default: .app)
    var appearance: AppearanceOption
}

// MARK: - Timeline

struct PairsEntry: TimelineEntry {
    let date: Date
    let lessons: [TimedLesson]
    let hasData: Bool
    let needsGroup: Bool
    let offline: Bool
    let theme: ThemeID
    let appearance: AppearanceMode

    static func sample(_ date: Date = Date(), theme: ThemeID = .sand,
                       appearance: AppearanceMode = .system) -> PairsEntry {
        let day = Schedule.iso(date)
        let raw = [
            Lesson(d: day, t: "10:15", s: "Исследование скважин и пластов", ty: "Лекция", r: "352", te: "Иванов И.И."),
            Lesson(d: day, t: "12:00", s: "Скважинная добыча нефти", ty: "Семинар", r: "219"),
            Lesson(d: day, t: "14:00", s: "НГП оборудование", ty: "ЛР", r: "П-10"),
        ]
        let data = ScheduleData(meta: .init(), lessons: raw)
        return PairsEntry(date: date,
                          lessons: data.timed(ScheduleFilter(subgroup: 1, showMilitary: false, includeCancelled: false)),
                          hasData: true, needsGroup: false, offline: false, theme: theme, appearance: appearance)
    }
}

struct PairsProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> PairsEntry { .sample() }

    func snapshot(for configuration: PairsConfigIntent, in context: Context) async -> PairsEntry {
        let s = SettingsStore.load()
        let group = configuration.group?.id ?? s.groupId
        var result = LoadResult(data: nil, offline: false)
        if let group {
            result = context.isPreview ? LoadResult(data: ScheduleSource.cached(group: group), offline: false)
                                       : await ScheduleSource.load(group: group)
        }
        guard result.data != nil else {
            return .sample(theme: configuration.theme.resolve(s), appearance: configuration.appearance.resolve(s))
        }
        return entries(for: configuration, settings: s, group: group, result: result, now: Date()).first!
    }

    func timeline(for configuration: PairsConfigIntent, in context: Context) async -> Timeline<PairsEntry> {
        let now = Date()
        let s = SettingsStore.load()
        let group = configuration.group?.id ?? s.groupId
        var result = LoadResult(data: nil, offline: false)
        if let group { result = await ScheduleSource.load(group: group) }
        let list = entries(for: configuration, settings: s, group: group, result: result, now: now)
        // Refetch data hourly (sooner when offline or no group yet); entries in between redraw at each class start/end.
        let reload = now.addingTimeInterval(result.offline || group == nil ? 15 * 60 : 60 * 60)
        return Timeline(entries: list, policy: .after(reload))
    }

    private func entries(for config: PairsConfigIntent, settings s: AppSettings, group: Int?,
                         result: LoadResult, now: Date) -> [PairsEntry] {
        let filter = ScheduleFilter(subgroup: config.subgroup.resolve(s), showMilitary: false, includeCancelled: false)
        let horizon = now.addingTimeInterval(14 * 24 * 3600)
        let lessons = (result.data?.timed(filter) ?? []).filter { $0.end > now && $0.start < horizon }
        let theme = config.theme.resolve(s)
        let appearance = config.appearance.resolve(s)

        var dates: Set<Date> = [now]
        let redrawUntil = now.addingTimeInterval(26 * 3600)
        for l in lessons {
            for d in [l.start, l.end] where d > now && d < redrawUntil { dates.insert(d.addingTimeInterval(1)) }
        }
        let midnight = Schedule.addDays(1, to: Schedule.startOfDay(now))
        dates.insert(midnight.addingTimeInterval(1))

        return dates.sorted().prefix(60).map {
            PairsEntry(date: $0, lessons: lessons, hasData: result.data != nil, needsGroup: group == nil,
                       offline: result.offline, theme: theme, appearance: appearance)
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
        if entry.needsGroup {
            Text("Открой «Пары» и выбери группу")
                .font(.caption)
                .foregroundStyle(isAccessory ? Color.primary : c.ink)
        } else if !entry.hasData {
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
