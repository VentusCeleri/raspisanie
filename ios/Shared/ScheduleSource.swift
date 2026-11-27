import Foundation

struct LoadResult {
    let data: ScheduleData?
    /// true when the network failed and `data` (if any) comes from the local copy.
    let offline: Bool
}

/// Loads schedule files from the site and keeps the last good copy on disk.
/// The app and the widget each keep their own copies.
enum ScheduleSource {
    static let site = URL(string: "https://ventusceleri.github.io/raspisanie/")!
    /// Landing page that picks App Store / APK by platform — what we share.
    static let shareURL = URL(string: "https://ventusceleri.github.io/raspisanie/app.html")!
    static let shareMessage = "Пары РГУ — расписание Губкина в телефоне"
    static let authorChannel = URL(string: "https://t.me/neuro_s_nulya")!

    private static var dir: URL {
        let fm = FileManager.default
        let d = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    /// Cache file of version 1.0 (single group) — its presence marks a 1.0 user.
    static var legacyCacheURL: URL { dir.appendingPathComponent("raspisanie-data.json") }

    static func cacheURL(group: Int) -> URL { dir.appendingPathComponent("group-\(group).json") }
    static var groupsCacheURL: URL { dir.appendingPathComponent("groups.json") }

    static func decode(_ data: Data) throws -> ScheduleData {
        try JSONDecoder().decode(ScheduleData.self, from: data)
    }

    // MARK: schedule of one group

    static func cached(group: Int) -> ScheduleData? {
        if let raw = try? Data(contentsOf: cacheURL(group: group)), let d = try? decode(raw) { return d }
        if group == SettingsStore.legacyGroup.id, let raw = try? Data(contentsOf: legacyCacheURL) {
            return try? decode(raw)
        }
        return nil
    }

    static func fetch(group: Int) async throws -> ScheduleData {
        let raw = try await get("g/\(group).json")
        let parsed = try decode(raw)
        try? raw.write(to: cacheURL(group: group), options: .atomic)
        return parsed
    }

    static func load(group: Int) async -> LoadResult {
        do {
            return LoadResult(data: try await fetch(group: group), offline: false)
        } catch {
            return LoadResult(data: cached(group: group), offline: true)
        }
    }

    // MARK: list of groups

    static func cachedGroups() -> [GroupInfo] {
        guard let raw = try? Data(contentsOf: groupsCacheURL),
              let idx = try? JSONDecoder().decode(GroupsIndex.self, from: raw) else { return [] }
        return idx.groups
    }

    static func fetchGroups() async throws -> [GroupInfo] {
        let raw = try await get("groups.json")
        let idx = try JSONDecoder().decode(GroupsIndex.self, from: raw)
        try? raw.write(to: groupsCacheURL, options: .atomic)
        return idx.groups
    }

    static func loadGroups() async -> [GroupInfo] {
        (try? await fetchGroups()) ?? cachedGroups()
    }

    // MARK: network

    private static func get(_ path: String) async throws -> Data {
        var comps = URLComponents(url: site.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "t", value: String(Int(Date().timeIntervalSince1970) / 60))]
        let request = URLRequest(url: comps.url!, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 12)
        let (raw, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }
        return raw
    }
}

extension GroupInfo {
    /// Case- and layout-insensitive match on the group code ("рн2304", "РН-23-04").
    func matches(_ query: String) -> Bool {
        let q = GroupInfo.normalize(query)
        return q.isEmpty || GroupInfo.normalize(code).contains(q)
    }

    static func normalize(_ s: String) -> String {
        s.lowercased().filter { $0.isLetter || $0.isNumber }
    }
}
