import Foundation

/// Root of `data.json` published by the site (see sync.py).
struct ScheduleData: Decodable {
    struct Meta: Decodable {
        var updated: String = ""
        var upperMonday: String? = nil
        var group: String = "РН-23-04"

        private enum CodingKeys: String, CodingKey { case updated, upperMonday, group }

        init() {}

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            updated = c.flexString(.updated)
            upperMonday = try? c.decodeIfPresent(String.self, forKey: .upperMonday)
            let g = c.flexString(.group)
            group = g.isEmpty ? "РН-23-04" : g
        }
    }

    var meta: Meta
    var lessons: [Lesson]

    private enum CodingKeys: String, CodingKey { case meta, lessons }

    init(meta: Meta, lessons: [Lesson]) {
        self.meta = meta
        self.lessons = lessons
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        meta = (try? c.decode(Meta.self, forKey: .meta)) ?? Meta()
        // Skip malformed lessons instead of failing the whole file.
        let raw = try c.decode([FailableDecodable<Lesson>].self, forKey: .lessons)
        lessons = raw.compactMap(\.value)
    }
}

/// One class as stored in data.json:
/// d — date (yyyy-MM-dd), t — start (H:mm), n — number of slots, s — subject, ty — type,
/// r — room, te — teacher, sg — subgroup (0 = whole group), note, st — cancel/moved, mil — military.
struct Lesson: Decodable, Hashable, Identifiable {
    let d: String
    let t: String
    let n: Int
    let s: String
    let ty: String
    let r: String
    let te: String
    let sg: Int
    let note: String
    let st: String
    let mil: Bool

    var id: String { [d, t, s, ty, r, String(sg), st].joined(separator: "|") }
    var isCancelled: Bool { !st.isEmpty }
    var isExam: Bool { ty == "Экзамен" }

    private enum CodingKeys: String, CodingKey { case d, t, n, s, ty, r, te, sg, note, st, mil }

    init(d: String, t: String, n: Int = 1, s: String, ty: String = "", r: String = "", te: String = "",
         sg: Int = 0, note: String = "", st: String = "", mil: Bool = false) {
        self.d = d; self.t = t; self.n = n; self.s = s; self.ty = ty; self.r = r
        self.te = te; self.sg = sg; self.note = note; self.st = st; self.mil = mil
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        d = try c.decode(String.self, forKey: .d)
        t = try c.decode(String.self, forKey: .t)
        s = c.flexString(.s)
        n = max(c.flexInt(.n) ?? 1, 1)
        ty = c.flexString(.ty)
        r = c.flexString(.r)
        te = c.flexString(.te)
        sg = c.flexInt(.sg) ?? 0
        note = c.flexString(.note)
        st = c.flexString(.st)
        mil = (c.flexInt(.mil) ?? 0) != 0
    }
}

private struct FailableDecodable<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: Decoder) throws { value = try? T(from: decoder) }
}

extension KeyedDecodingContainer {
    func flexInt(_ key: Key) -> Int? {
        if let v = try? decodeIfPresent(Int.self, forKey: key) { return v }
        if let s = try? decodeIfPresent(String.self, forKey: key) { return Int(s.trimmingCharacters(in: .whitespaces)) }
        if let b = try? decodeIfPresent(Bool.self, forKey: key) { return b ? 1 : 0 }
        return nil
    }

    func flexString(_ key: Key) -> String {
        if let s = try? decodeIfPresent(String.self, forKey: key) { return s }
        if let i = flexInt(key) { return String(i) }
        return ""
    }
}
