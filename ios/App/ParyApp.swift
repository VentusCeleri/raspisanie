import SwiftUI
import WidgetKit

@main
struct ParyApp: App {
    @StateObject private var model = AppModel()
    @AppStorage(AppearanceMode.storageKey) private var appearanceRaw = AppearanceMode.system.rawValue
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .preferredColorScheme(AppearanceMode.from(appearanceRaw).colorScheme)
                .task { await model.refresh() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { Task { await model.refreshIfStale() } }
                }
        }
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var data: ScheduleData?
    @Published private(set) var offline = false
    @Published private(set) var loading = false
    private var lastFetch: Date?

    init() {
        data = ScheduleSource.cached()
    }

    func refresh() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        let result = await ScheduleSource.load()
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
}
