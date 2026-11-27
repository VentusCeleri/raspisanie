import Foundation
import Security

/// One entry of groups.json.
struct GroupInfo: Codable, Hashable, Identifiable {
    let id: Int
    let code: String
    /// Faculty name.
    let f: String
}

struct GroupsIndex: Decodable {
    var updated: String?
    var groups: [GroupInfo]
}

/// User choices shared by the app and the widget.
struct AppSettings: Codable, Equatable {
    var groupId: Int?
    var groupCode: String?
    var subgroup: Int = 1
    var showMilitary: Bool = false
    var theme: String = ThemeID.sand.rawValue
    var appearance: String = AppearanceMode.system.rawValue

    var themeID: ThemeID { ThemeID.from(theme) }
    var appearanceMode: AppearanceMode { AppearanceMode.from(appearance) }
}

/// Settings live in a shared keychain item, so the widget sees what the app chose
/// (no App Group needed: the access group is covered by the team's App Store profiles).
enum SettingsStore {
    static let accessGroup = "P7FFVJWCKW.ru.ventusceleri.pary.shared"
    private static let service = "ru.ventusceleri.pary"
    private static let account = "settings"

    /// Group of version 1.0, which only knew РН-23-04.
    static let legacyGroup = GroupInfo(id: 9209, code: "РН-23-04", f: "")

    static func load() -> AppSettings {
        var s: AppSettings
        if let data = read(), let saved = try? JSONDecoder().decode(AppSettings.self, from: data) {
            s = saved
        } else {
            s = migrateFromV1()
        }
        // Launch-argument override (`-groupId 9209 -groupCode РН-23-04`), used by the screenshot workflow.
        let d = UserDefaults.standard
        if d.integer(forKey: "groupId") > 0 {
            s.groupId = d.integer(forKey: "groupId")
            s.groupCode = d.string(forKey: "groupCode") ?? s.groupCode
        }
        if let t = d.volatileDomain(forName: UserDefaults.argumentDomain)[ThemeID.storageKey] as? String { s.theme = t }
        if let a = d.volatileDomain(forName: UserDefaults.argumentDomain)[AppearanceMode.storageKey] as? String { s.appearance = a }
        return s
    }

    static func save(_ s: AppSettings) {
        guard let data = try? JSONEncoder().encode(s) else { return }
        let query: [String: Any] = baseQuery()
        let attrs: [String: Any] = [kSecValueData as String: data,
                                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock]
        let status = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            var add = query
            add.merge(attrs) { $1 }
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    private static func baseQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account,
         kSecAttrAccessGroup as String: accessGroup]
    }

    private static func read() -> Data? {
        var q = baseQuery()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    /// 1.0 kept settings in the app's UserDefaults and had a single group.
    private static func migrateFromV1() -> AppSettings {
        let d = UserDefaults.standard
        var s = AppSettings()
        if let pg = d.object(forKey: "subgroup") as? Int { s.subgroup = pg }
        s.showMilitary = d.bool(forKey: "showMilitary")
        if let t = d.string(forKey: ThemeID.storageKey) { s.theme = t }
        if let a = d.string(forKey: AppearanceMode.storageKey) { s.appearance = a }
        if FileManager.default.fileExists(atPath: ScheduleSource.legacyCacheURL.path) {
            s.groupId = legacyGroup.id
            s.groupCode = legacyGroup.code
        }
        return s
    }
}
