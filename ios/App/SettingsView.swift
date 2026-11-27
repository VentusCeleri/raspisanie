import SwiftUI
import WidgetKit

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @AppStorage("subgroup") private var subgroup = 1
    @AppStorage("showMilitary") private var showMilitary = false
    @AppStorage(ThemeID.storageKey) private var themeRaw = ThemeID.sand.rawValue
    @AppStorage(AppearanceMode.storageKey) private var appearanceRaw = AppearanceMode.system.rawValue

    private var theme: ThemeID { ThemeID.from(themeRaw) }
    private var c: Swatch { theme.palette.colors(scheme) }

    var body: some View {
        NavigationStack {
            Form {
                Section("Расписание") {
                    Picker("Подгруппа", selection: $subgroup) {
                        Text("1 подгруппа").tag(1)
                        Text("2 подгруппа").tag(2)
                    }
                    .pickerStyle(.segmented)
                    Toggle("Показывать военку", isOn: $showMilitary)
                }
                .listRowBackground(c.card)

                Section("Тема") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 12)], spacing: 12) {
                        ForEach(ThemeID.allCases) { t in
                            ThemeSwatch(theme: t, scheme: scheme, selected: t == theme)
                                .onTapGesture { themeRaw = t.rawValue }
                        }
                    }
                    .padding(.vertical, 6)
                    Picker("Оформление", selection: $appearanceRaw) {
                        ForEach(AppearanceMode.allCases) { Text($0.title).tag($0.rawValue) }
                    }
                }
                .listRowBackground(c.card)

                Section {
                    Label("Долгое нажатие на виджет → «Изменить виджет»: там своя подгруппа, тема и оформление.",
                          systemImage: "square.text.square")
                        .font(.footnote)
                        .foregroundStyle(c.ink)
                    Button("Обновить виджеты сейчас") { WidgetCenter.shared.reloadAllTimelines() }
                } header: {
                    Text("Виджет")
                }
                .listRowBackground(c.card)

                Section("О приложении") {
                    if let updated = model.data?.meta.updated, !updated.isEmpty {
                        LabeledContent("Данные из ЛК", value: updated)
                    }
                    Link("Сайт расписания", destination: ScheduleSource.site)
                    LabeledContent("Версия", value: appVersion)
                }
                .listRowBackground(c.card)
            }
            .scrollContentBackground(.hidden)
            .background(c.bg.ignoresSafeArea())
            .foregroundStyle(c.ink)
            .navigationTitle("Настройки")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } }
            }
        }
        .tint(c.accent)
        .preferredColorScheme(AppearanceMode.from(appearanceRaw).colorScheme)
        .onChange(of: subgroup) { _, _ in WidgetCenter.shared.reloadAllTimelines() }
    }

    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let v = info?["CFBundleShortVersionString"] as? String ?? "?"
        let b = info?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }
}

struct ThemeSwatch: View {
    let theme: ThemeID
    let scheme: ColorScheme
    let selected: Bool

    var body: some View {
        let s = theme.palette.colors(scheme)
        VStack(spacing: 6) {
            ZStack(alignment: .bottomLeading) {
                RoundedRectangle(cornerRadius: 12, style: .continuous).fill(s.bg)
                VStack(alignment: .leading, spacing: 4) {
                    Capsule().fill(s.accent).frame(width: 30, height: 6)
                    Capsule().fill(s.ink).frame(width: 40, height: 6)
                    Capsule().fill(s.muted).frame(width: 24, height: 6)
                }
                .padding(8)
            }
            .frame(height: 64)
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(selected ? s.accent : s.line, lineWidth: selected ? 3 : 1)
            )
            Text(theme.title).font(.caption)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Тема \(theme.title)")
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}
