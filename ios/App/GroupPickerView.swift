import SwiftUI

/// Search and pick a study group. Used on first launch and from Settings.
struct GroupPickerView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var loading = false

    /// true on first launch: no "Cancel", the sheet cannot be dismissed without a choice.
    let isOnboarding: Bool

    init(isOnboarding: Bool = false) {
        self.isOnboarding = isOnboarding
    }

    private var c: Swatch { model.palette.colors(scheme) }

    private var filtered: [GroupInfo] {
        model.groups.filter { $0.matches(query) }
    }

    private var byFaculty: [(String, [GroupInfo])] {
        Dictionary(grouping: filtered, by: \.f)
            .map { ($0.key.isEmpty ? "Без факультета" : $0.key, $0.value) }
            .sorted { $0.0 < $1.0 }
    }

    var body: some View {
        NavigationStack {
            Group {
                if model.groups.isEmpty {
                    VStack(spacing: 12) {
                        if loading {
                            ProgressView()
                            Text("Загружаю список групп…").foregroundStyle(c.muted)
                        } else {
                            Image(systemName: "wifi.slash").font(.largeTitle).foregroundStyle(c.muted)
                            Text("Не удалось загрузить список групп. Проверь интернет.")
                                .multilineTextAlignment(.center).foregroundStyle(c.ink)
                            Button("Повторить") { Task { await reload() } }.buttonStyle(.borderedProminent)
                        }
                    }
                    .padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        if query.isEmpty {
                            ForEach(byFaculty, id: \.0) { faculty, groups in
                                Section(faculty) { rows(groups) }
                                    .listRowBackground(c.card)
                            }
                        } else {
                            rows(filtered).listRowBackground(c.card)
                            if filtered.isEmpty {
                                Text("Нет такой группы").foregroundStyle(c.muted).listRowBackground(c.card)
                            }
                        }
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .background(c.bg.ignoresSafeArea())
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Например, РН-23-04")
            .navigationTitle(isOnboarding ? "Твоя группа" : "Группа")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !isOnboarding {
                    ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
                }
            }
            .task { await reload() }
        }
        .tint(c.accent)
        .interactiveDismissDisabled(isOnboarding)
    }

    private func rows(_ groups: [GroupInfo]) -> some View {
        ForEach(groups) { g in
            Button {
                model.select(g)
                dismiss()
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(g.code).font(.body.weight(.semibold)).foregroundStyle(c.ink)
                        if !query.isEmpty && !g.f.isEmpty {
                            Text(g.f).font(.caption).foregroundStyle(c.muted).lineLimit(1)
                        }
                    }
                    Spacer()
                    if g.id == model.settings.groupId {
                        Image(systemName: "checkmark").foregroundStyle(c.accent)
                    }
                }
            }
        }
    }

    private func reload() async {
        loading = true
        await model.loadGroups()
        loading = false
    }
}
