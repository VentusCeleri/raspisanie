import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.colorScheme) private var scheme

    @State private var selectedDay = Schedule.startOfDay(Date())
    @State private var showSettings = false
    @State private var showAddGroup = false

    private var c: Swatch { model.palette.colors(scheme) }
    private var needsGroup: Bool { model.settings.groupId == nil }

    private var filter: ScheduleFilter {
        ScheduleFilter(subgroup: model.settings.subgroup, showMilitary: model.settings.showMilitary,
                       includeCancelled: true)
    }

    var body: some View {
        let all = model.data?.timed(filter) ?? []
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if needsGroup {
                        EmptyView()
                    } else if model.data == nil {
                        emptyState
                    } else {
                        TimelineView(.periodic(from: .now, by: 30)) { ctx in
                            NowCard(lessons: all.filter { !$0.lesson.isCancelled }, now: ctx.date, c: c)
                        }
                        WeekStrip(selectedDay: $selectedDay, lessons: all,
                                  upperMonday: model.data?.meta.upperMonday, c: c)
                        DayList(day: selectedDay, lessons: all.filter { $0.day == selectedDay }, c: c)
                        ExamsSection(lessons: all.filter { $0.lesson.isExam && $0.end > Date() }, c: c)
                    }
                    footer
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .refreshable { await model.refresh() }
            .background(c.bg.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(c.bg, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    GroupSwitcher(c: c) { showAddGroup = true }
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button { selectedDay = Schedule.startOfDay(Date()) } label: { Text("Сегодня") }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    ShareLink(item: ScheduleSource.shareURL, subject: Text("Пары РГУ"),
                              message: Text(ScheduleSource.shareMessage)) {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .accessibilityLabel("Поделиться приложением")
                    Button { showSettings = true } label: { Image(systemName: "paintpalette") }
                        .accessibilityLabel("Настройки и оформление")
                }
            }
        }
        .tint(c.accent)
        .onAppear {
            // Used by the screenshot workflow: `-openSettings` opens the settings sheet on launch.
            if ProcessInfo.processInfo.arguments.contains("-openSettings") { showSettings = true }
        }
        .sheet(isPresented: .constant(needsGroup)) {
            GroupPickerView(isOnboarding: true)
                .environmentObject(model)
        }
        .sheet(isPresented: $showAddGroup) {
            GroupPickerView().environmentObject(model)
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
                .environmentObject(model)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            if model.loading {
                ProgressView()
                Text("Загружаю расписание…").foregroundStyle(c.muted)
            } else {
                Image(systemName: "wifi.slash").font(.largeTitle).foregroundStyle(c.muted)
                Text("Нет данных. Подключись к интернету и потяни экран вниз.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(c.ink)
                Button("Повторить") { Task { await model.refresh() } }
                    .buttonStyle(.borderedProminent)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let updated = model.data?.meta.updated, !updated.isEmpty {
                Text("Обновлено с ЛК: \(updated)")
            }
            if model.offline && model.data != nil {
                Label("Нет сети, показана последняя копия", systemImage: "wifi.slash")
            }
            Text("\(model.settings.subgroup) подгруппа · группа и подгруппа меняются в настройках")
        }
        .font(.footnote)
        .foregroundStyle(c.muted)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 8)
    }
}

// MARK: - Group switcher (title)

/// Group code in the nav bar: swipe left/right to switch followed groups, tap for a menu.
struct GroupSwitcher: View {
    @EnvironmentObject private var model: AppModel
    let c: Swatch
    let addGroup: () -> Void

    var body: some View {
        let groups = model.settings.groups
        Menu {
            ForEach(groups) { g in
                Button { model.activate(g) } label: {
                    if g.id == model.settings.groupId { Label(g.code, systemImage: "checkmark") } else { Text(g.code) }
                }
            }
            Divider()
            Button { addGroup() } label: { Label("Добавить группу", systemImage: "plus") }
        } label: {
            VStack(spacing: 3) {
                HStack(spacing: 4) {
                    Text(model.settings.groupCode ?? "Пары").font(.headline).foregroundStyle(c.ink)
                    Image(systemName: "chevron.down").font(.caption2.weight(.bold)).foregroundStyle(c.muted)
                }
                if groups.count > 1 {
                    HStack(spacing: 4) {
                        ForEach(groups) { g in
                            Circle().fill(g.id == model.settings.groupId ? c.accent : c.muted.opacity(0.4))
                                .frame(width: 5, height: 5)
                        }
                    }
                }
            }
            .padding(.horizontal, 24)
            .contentShape(Rectangle())
        }
        .highPriorityGesture(DragGesture(minimumDistance: 20).onEnded { v in
            if v.translation.width < -40 { withAnimation { model.cycle(1) } }
            if v.translation.width > 40 { withAnimation { model.cycle(-1) } }
        })
        .accessibilityLabel("Группа \(model.settings.groupCode ?? ""). Смахните, чтобы переключить")
    }
}

// MARK: - Now / next

struct NowCard: View {
    let lessons: [TimedLesson]
    let now: Date
    let c: Swatch

    var body: some View {
        let state = NowState(lessons: lessons, now: now)
        VStack(alignment: .leading, spacing: 6) {
            if let f = state.focus {
                header(state, f)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(f.lesson.isExam ? c.exam : c.accent)
                HStack(alignment: .firstTextBaseline) {
                    Text(f.lesson.r.isEmpty ? "—" : f.lesson.r)
                        .font(.system(size: 44, weight: .heavy, design: .rounded))
                        .foregroundStyle(f.lesson.isExam ? c.exam : c.ink)
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                    Spacer()
                    Text("\(f.lesson.t)–\(f.endText)")
                        .font(.headline.monospacedDigit())
                        .foregroundStyle(c.muted)
                }
                Text(f.lesson.s)
                    .font(.headline)
                    .foregroundStyle(c.ink)
                Text(subtitle(f.lesson))
                    .font(.subheadline)
                    .foregroundStyle(c.muted)
                if !f.lesson.note.isEmpty {
                    Text(f.lesson.note).font(.subheadline.weight(.semibold)).foregroundStyle(c.exam)
                }
                if !state.rest.isEmpty {
                    Divider().overlay(c.line)
                    Text(state.rest.map { "\($0.lesson.t) · \($0.lesson.r)" }.joined(separator: "   "))
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(c.muted)
                }
            } else {
                Text("Пар больше нет").font(.headline).foregroundStyle(c.ink)
                Text("В расписании впереди пусто — каникулы или данные ещё не выложены.")
                    .font(.subheadline).foregroundStyle(c.muted)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(c.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(c.line, lineWidth: 1))
    }

    @ViewBuilder
    private func header(_ state: NowState, _ f: TimedLesson) -> some View {
        if state.current != nil {
            Text("СЕЙЧАС · ДО \(f.endText)")
        } else if f.day == Schedule.startOfDay(now) {
            Text("СЛЕДУЮЩАЯ · ЧЕРЕЗ ") + Text(f.start, style: .relative)
        } else {
            Text("СЛЕДУЮЩАЯ · \(Schedule.relativeDay(f.start, now: now).uppercased()) В \(f.lesson.t)")
        }
    }

    private func subtitle(_ l: Lesson) -> String {
        var parts = [l.ty.isEmpty ? nil : l.ty, l.n > 1 ? "×\(l.n)" : nil, l.te.isEmpty ? nil : l.te]
        if l.sg != 0 { parts.append("\(l.sg) подгр.") }
        return parts.compactMap { $0 }.joined(separator: " · ")
    }
}

// MARK: - Week

struct WeekStrip: View {
    @Binding var selectedDay: Date
    let lessons: [TimedLesson]
    let upperMonday: String?
    let c: Swatch

    var body: some View {
        let monday = Schedule.monday(of: selectedDay)
        let hasSunday = lessons.contains { $0.day == Schedule.addDays(6, to: monday) }
        let days = (0..<(hasSunday ? 7 : 6)).map { Schedule.addDays($0, to: monday) }
        let today = Schedule.startOfDay(Date())

        VStack(spacing: 10) {
            HStack {
                Button { selectedDay = Schedule.addDays(-7, to: selectedDay) } label: {
                    Image(systemName: "chevron.left")
                }
                Spacer()
                VStack(spacing: 2) {
                    Text("\(Schedule.shortDate(monday)) – \(Schedule.shortDate(days.last ?? monday))")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(c.ink)
                    if let up = Schedule.isUpperWeek(monday, upperMonday: upperMonday) {
                        Text(up ? "верхняя неделя" : "нижняя неделя")
                            .font(.caption)
                            .foregroundStyle(up ? c.accent : c.muted)
                    }
                }
                Spacer()
                Button { selectedDay = Schedule.addDays(7, to: selectedDay) } label: {
                    Image(systemName: "chevron.right")
                }
            }
            HStack(spacing: 6) {
                ForEach(days, id: \.self) { d in
                    let count = lessons.filter { $0.day == d && !$0.lesson.isCancelled }.count
                    let selected = d == selectedDay
                    Button { selectedDay = d } label: {
                        VStack(spacing: 4) {
                            Text(Schedule.weekdayShort[Schedule.weekday(d)])
                                .font(.caption2.weight(.semibold))
                            Text("\(Schedule.calendar.component(.day, from: d))")
                                .font(.headline.monospacedDigit())
                            Circle()
                                .fill(count > 0 ? (selected ? c.bg : c.accent) : .clear)
                                .frame(width: 5, height: 5)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .foregroundStyle(selected ? c.bg : (d == today ? c.accent : c.ink))
                        .background(selected ? c.accent : c.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .gesture(DragGesture(minimumDistance: 30).onEnded { v in
            if v.translation.width < -60 { selectedDay = Schedule.addDays(7, to: selectedDay) }
            if v.translation.width > 60 { selectedDay = Schedule.addDays(-7, to: selectedDay) }
        })
    }
}

// MARK: - Day

struct DayList: View {
    let day: Date
    let lessons: [TimedLesson]
    let c: Swatch

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("\(Schedule.weekdayFull[Schedule.weekday(day)].capitalized), \(Schedule.longDate(day))")
                .font(.title3.weight(.bold))
                .foregroundStyle(c.ink)
            if lessons.isEmpty {
                Text("Пар нет")
                    .foregroundStyle(c.muted)
                    .padding(.vertical, 12)
            } else {
                ForEach(lessons) { LessonRow(item: $0, c: c) }
            }
        }
    }
}

struct LessonRow: View {
    let item: TimedLesson
    let c: Swatch

    var body: some View {
        let l = item.lesson
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(l.t).font(.subheadline.weight(.bold).monospacedDigit())
                Text(item.endText).font(.caption.monospacedDigit()).foregroundStyle(c.muted)
            }
            .frame(width: 48, alignment: .leading)
            .foregroundStyle(c.ink)

            VStack(alignment: .leading, spacing: 4) {
                Text(l.s)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(c.ink)
                    .strikethrough(l.isCancelled)
                HStack(spacing: 6) {
                    if !l.ty.isEmpty { Tag(text: l.ty, color: l.isExam ? c.exam : c.accent) }
                    if l.sg != 0 { Tag(text: "\(l.sg) подгр.", color: c.muted) }
                    if l.mil { Tag(text: "военка", color: c.muted) }
                }
                if !l.te.isEmpty {
                    Text(l.te).font(.footnote).foregroundStyle(c.muted)
                }
                if !l.note.isEmpty {
                    Text(l.note).font(.footnote.weight(.semibold)).foregroundStyle(c.exam)
                }
            }
            Spacer(minLength: 4)
            Text(l.r.isEmpty ? "—" : l.r)
                .font(.title3.weight(.heavy).monospacedDigit())
                .foregroundStyle(l.isExam ? c.exam : c.ink)
                .strikethrough(l.isCancelled)
        }
        .padding(14)
        .background(c.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .opacity(l.isCancelled ? 0.55 : (item.end < Date() ? 0.6 : 1))
    }
}

struct Tag: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .foregroundStyle(color)
            .background(color.opacity(0.14), in: Capsule())
    }
}

// MARK: - Exams

struct ExamsSection: View {
    let lessons: [TimedLesson]
    let c: Swatch

    var body: some View {
        if !lessons.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("Сессия").font(.title3.weight(.bold)).foregroundStyle(c.ink)
                ForEach(lessons) { e in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(Schedule.dayLabel(e.start)).font(.caption.weight(.bold)).foregroundStyle(c.exam)
                            Text(e.lesson.s).font(.subheadline.weight(.semibold)).foregroundStyle(c.ink)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(e.lesson.t).font(.subheadline.monospacedDigit()).foregroundStyle(c.muted)
                            Text(e.lesson.r).font(.headline).foregroundStyle(c.ink)
                        }
                    }
                    .padding(12)
                    .background(c.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
            }
        }
    }
}
