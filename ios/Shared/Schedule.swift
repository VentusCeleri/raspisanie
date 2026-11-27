import Foundation

/// Calendar math and display helpers. All dates are in Moscow time, whatever the device zone is.
enum Schedule {
    static let slots: [(start: String, end: String)] = [
        ("8:30", "10:00"), ("10:15", "11:45"), ("12:00", "13:30"), ("14:00", "15:30"),
        ("15:45", "17:15"), ("17:20", "18:50"), ("18:55", "20:25"), ("20:30", "22:00"),
    ]

    static let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Moscow") ?? .current
        c.locale = Locale(identifier: "ru_RU")
        c.firstWeekday = 2
        return c
    }()

    static let weekdayShort = ["Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"]
    static let weekdayFull = ["воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота"]
    static let monthShort = ["янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"]
    static let monthGen = ["января", "февраля", "марта", "апреля", "мая", "июня",
                           "июля", "августа", "сентября", "октября", "ноября", "декабря"]

    static let shortNames: [String: String] = [
        "Скважинная добыча нефти": "СДН",
        "Проектирование разработки (Petrel)": "Проектирование",
        "Проектирование разработки месторождений в виртуальной среде профессиональной деятельности": "Проектирование",
        "Исследование скважин и пластов": "Исследование скважин",
        "Управление продуктивностью скважин": "УПС",
        "Система АРМ в добыче нефти": "Система АРМ",
        "Системы сбора и подготовки": "Системы сбора",
        "Основы промысловой геофизики": "Геофизика",
        "Нефтегазопромысловое оборудование": "НГП оборудование",
        "Морские НГ проекты": "Морские проекты",
        "Перспективные проекты освоения морских нефтегазовых ресурсов": "Морские проекты",
    ]

    static let typeShort: [String: String] = [
        "Лекция": "лек", "Семинар": "сем", "ЛР": "лаб", "Экзамен": "экзамен",
        "Консультация": "конс", "Мероприятие": "меропр",
    ]

    static func shortName(_ s: String) -> String { shortNames[s] ?? s }
    static func typeLabel(_ ty: String) -> String { typeShort[ty] ?? ty.lowercased() }

    static func minutes(_ t: String) -> Int {
        let p = t.split(separator: ":").compactMap { Int($0) }
        return p.count == 2 ? p[0] * 60 + p[1] : 0
    }

    static func hhmm(_ m: Int) -> String { "\(m / 60):" + String(format: "%02d", m % 60) }

    static func endTime(_ l: Lesson) -> String {
        if let i = slots.firstIndex(where: { $0.start == l.t }) {
            return slots[min(i + l.n - 1, slots.count - 1)].end
        }
        return hhmm(minutes(l.t) + l.n * 90 + (l.n - 1) * 15)
    }

    static func day(_ iso: String) -> Date? {
        let p = iso.split(separator: "-").compactMap { Int($0) }
        guard p.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: p[0], month: p[1], day: p[2]))
    }

    static func date(_ iso: String, _ t: String) -> Date? {
        guard let d = day(iso) else { return nil }
        return calendar.date(byAdding: .minute, value: minutes(t), to: d)
    }

    static func iso(_ date: Date) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func startOfDay(_ d: Date) -> Date { calendar.startOfDay(for: d) }

    static func addDays(_ n: Int, to d: Date) -> Date { calendar.date(byAdding: .day, value: n, to: d) ?? d }

    static func monday(of d: Date) -> Date {
        let wd = calendar.component(.weekday, from: d) // 1 = Sunday
        return addDays(-((wd + 5) % 7), to: startOfDay(d))
    }

    /// nil when the site did not publish the reference Monday.
    static func isUpperWeek(_ d: Date, upperMonday: String?) -> Bool? {
        guard let ref = upperMonday.flatMap(day) else { return nil }
        let days = calendar.dateComponents([.day], from: ref, to: monday(of: d)).day ?? 0
        let weeks = Int((Double(days) / 7).rounded(.down))
        return ((weeks % 2) + 2) % 2 == 0
    }

    static func weekday(_ d: Date) -> Int { calendar.component(.weekday, from: d) - 1 }

    /// «Пт, 27 ноя»
    static func dayLabel(_ d: Date) -> String {
        let c = calendar.dateComponents([.day, .month], from: d)
        return "\(weekdayShort[weekday(d)]), \(c.day ?? 0) \(monthShort[(c.month ?? 1) - 1])"
    }

    /// «27 ноября»
    static func longDate(_ d: Date) -> String {
        let c = calendar.dateComponents([.day, .month], from: d)
        return "\(c.day ?? 0) \(monthGen[(c.month ?? 1) - 1])"
    }

    static func shortDate(_ d: Date) -> String {
        let c = calendar.dateComponents([.day, .month], from: d)
        return "\(c.day ?? 0) \(monthShort[(c.month ?? 1) - 1])"
    }

    /// «Сегодня», «Завтра» or «Пн, 30 ноя».
    static func relativeDay(_ d: Date, now: Date) -> String {
        let today = startOfDay(now)
        let day = startOfDay(d)
        if day == today { return "Сегодня" }
        if day == addDays(1, to: today) { return "Завтра" }
        return dayLabel(d)
    }
}

/// A lesson with resolved start/end dates.
struct TimedLesson: Identifiable, Hashable {
    let lesson: Lesson
    let start: Date
    let end: Date

    var id: String { lesson.id }
    var endText: String { Schedule.endTime(lesson) }
    var day: Date { Schedule.startOfDay(start) }
}

struct ScheduleFilter: Equatable {
    var subgroup: Int
    var showMilitary: Bool
    var includeCancelled: Bool
}

extension ScheduleData {
    func timed(_ f: ScheduleFilter) -> [TimedLesson] {
        lessons
            .filter { ($0.sg == 0 || $0.sg == f.subgroup) && (f.showMilitary || !$0.mil) && (f.includeCancelled || !$0.isCancelled) }
            .compactMap { l in
                guard let s = Schedule.date(l.d, l.t), let e = Schedule.date(l.d, Schedule.endTime(l)) else { return nil }
                return TimedLesson(lesson: l, start: s, end: e)
            }
            .sorted { $0.start < $1.start }
    }
}

/// What is going on right now: the current class or the next one, plus the rest of that day.
struct NowState {
    let current: TimedLesson?
    let next: TimedLesson?
    /// Remaining classes of the focus day, without the focus itself.
    let rest: [TimedLesson]
    /// First study day after the focus day.
    let followingDay: Date?
    let followingLessons: [TimedLesson]

    var focus: TimedLesson? { current ?? next }

    /// `lessons` must be sorted and should not contain cancelled classes.
    init(lessons: [TimedLesson], now: Date) {
        current = lessons.first { $0.start <= now && now < $0.end }
        next = lessons.first { $0.start > now }
        let focus = current ?? next
        if let focus {
            rest = lessons.filter { $0.day == focus.day && $0.end > now && $0.id != focus.id }
            let after = lessons.filter { $0.day > focus.day }
            followingDay = after.first?.day
            followingLessons = after.filter { $0.day == after.first?.day }
        } else {
            rest = []
            followingDay = nil
            followingLessons = []
        }
    }
}
