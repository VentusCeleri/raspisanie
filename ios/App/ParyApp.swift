import SwiftUI
import WidgetKit

@main
struct ParyApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .preferredColorScheme(model.settings.appearanceMode.colorScheme)
                .task { await model.refresh() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { Task { await model.refreshIfStale() } }
                }
        }
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var settings: AppSettings {
        didSet {
            guard settings != oldValue else { return }
            SettingsStore.save(settings)
            if settings.groupId != oldValue.groupId {
                data = settings.groupId.flatMap { ScheduleSource.cached(group: $0) }
                lastFetch = nil
                Task { await refresh() }
            }
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
    @Published private(set) var data: ScheduleData?
    @Published private(set) var offline = false
    @Published private(set) var loading = false
    @Published private(set) var groups: [GroupInfo] = []
    private var lastFetch: Date?

    init() {
        let s = SettingsStore.load()
        settings = s
        SettingsStore.save(s) // persists the 1.0 → 1.1 migration so the widget sees it too
        data = s.groupId.flatMap { ScheduleSource.cached(group: $0) }
        groups = ScheduleSource.cachedGroups()
    }

    var palette: Palette { settings.themeID.palette }

    func select(_ group: GroupInfo) {
        settings.activate(GroupRef(id: group.id, code: group.code))
    }

    func activate(_ g: GroupRef) { settings.activate(g) }
    func remove(_ id: Int) { settings.remove(id) }
    func cycle(_ delta: Int) { settings.cycle(delta) }

    func refresh() async {
        guard let group = settings.groupId, !loading else { return }
        loading = true
        defer { loading = false }
        let result = await ScheduleSource.load(group: group)
        guard group == settings.groupId else { return } // group changed while loading
        if let fresh = result.data { data = fresh }
        offline = result.offline
        if !result.offline {
            lastFetch = Date()
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    func refreshIfStale() async {
        if let lastFetch, Date().timeIntervalSince(lastFetch) < 10 * 60 { return }
        await refresh()
    }

    func loadGroups() async {
        let list = await ScheduleSource.loadGroups()
        if !list.isEmpty { groups = list }
    }
}
