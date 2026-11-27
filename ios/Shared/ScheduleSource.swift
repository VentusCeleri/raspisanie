import Foundation

struct LoadResult {
    let data: ScheduleData?
    /// true when the network failed and `data` (if any) comes from the local copy.
    let offline: Bool
}

/// Loads data.json from the site and keeps the last good copy on disk.
/// The app and the widget each keep their own copy: no App Group is needed.
enum ScheduleSource {
    static let site = URL(string: "https://ventusceleri.github.io/raspisanie/")!

    static var cacheURL: URL {
        let fm = FileManager.default
        let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("raspisanie-data.json")
    }

    static func decode(_ data: Data) throws -> ScheduleData {
        try JSONDecoder().decode(ScheduleData.self, from: data)
    }

    static func cached() -> ScheduleData? {
        guard let raw = try? Data(contentsOf: cacheURL) else { return nil }
        return try? decode(raw)
    }

    static func fetch() async throws -> ScheduleData {
        var comps = URLComponents(url: site.appendingPathComponent("data.json"), resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "t", value: String(Int(Date().timeIntervalSince1970)))]
        let request = URLRequest(url: comps.url!, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        let (raw, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }
        let parsed = try decode(raw)
        try? raw.write(to: cacheURL, options: .atomic)
        return parsed
    }

    static func load() async -> LoadResult {
        do {
            return LoadResult(data: try await fetch(), offline: false)
        } catch {
            return LoadResult(data: cached(), offline: true)
        }
    }
}
