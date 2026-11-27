import SwiftUI
import WidgetKit

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State private var showGroupPicker = false

    private var theme: ThemeID { model.settings.themeID }
    private var c: Swatch { theme.palette.colors(scheme) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(model.settings.groups) { g in
                        Button { model.activate(g) } label: {
                            HStack {
                                Text(g.code).foregroundStyle(c.ink)
                                Spacer()
                                if g.id == model.settings.groupId { Image(systemName: "checkmark").foregroundStyle(c.accent) }
                            }
                        }
                    }
                    .onDelete { idx in
                        let ids = idx.map { model.settings.groups[$0].id }
                        ids.forEach { model.remove($0) }
                    }
                    .deleteDisabled(model.settings.groups.count < 2)
                    Button { showGroupPicker = true } label: { Label("Добавить группу", systemImage: "plus") }
                } header: {
                    Text("Мои группы")
                } footer: {
                    Text("Переключать группы можно свайпом по названию вверху главного экрана. Удалить — свайп по строке влево.")
                }
                .listRowBackground(c.card)

                Section("Расписание") {
                    Picker("Подгруппа", selection: $model.settings.subgroup) {
                        Text("1 подгруппа").tag(1)
                        Text("2 подгруппа").tag(2)
                    }
                    .pickerStyle(.segmented)
                    Toggle("Показывать военку", isOn: $model.settings.showMilitary)
                }
                .listRowBackground(c.card)

                Section("Тема") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 12)], spacing: 12) {
                        ForEach(ThemeID.allCases) { t in
                            ThemeSwatch(theme: t, scheme: scheme, selected: t == theme)
                                .onTapGesture { model.settings.theme = t.rawValue }
                        }
                    }
                    .padding(.vertical, 6)
                    Picker("Оформление", selection: $model.settings.appearance) {
                        ForEach(AppearanceMode.allCases) { Text($0.title).tag($0.rawValue) }
                    }
                }
                .listRowBackground(c.card)

                Section {
                    Label("Виджет берёт группу, подгруппу и тему отсюда. Чтобы задать виджету свои: долгое нажатие → «Изменить виджет».",
                          systemImage: "square.text.square")
                        .font(.footnote)
                        .foregroundStyle(c.ink)
                    Button("Обновить виджеты сейчас") { WidgetCenter.shared.reloadAllTimelines() }
                } header: {
                    Text("Виджет")
                }
                .listRowBackground(c.card)

                Section("Автор") {
                    ShareLink(item: ScheduleSource.shareURL, subject: Text("Пары РГУ"),
                              message: Text(ScheduleSource.shareMessage)) {
                        Label("Поделиться приложением", systemImage: "square.and.arrow.up")
                    }
                    Link(destination: ScheduleSource.authorChannel) {
                        Label("Канал автора — Нейросети с нуля", systemImage: "paperplane")
                    }
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
        .preferredColorScheme(model.settings.appearanceMode.colorScheme)
        .sheet(isPresented: $showGroupPicker) {
            GroupPickerView().environmentObject(model)
        }
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
