import SwiftUI

/// Colour set for one appearance (light or dark).
struct Swatch {
    let bg: Color
    let card: Color
    let ink: Color
    let muted: Color
    let accent: Color
    let exam: Color
    let line: Color

    init(bg: UInt32, card: UInt32, ink: UInt32, muted: UInt32, accent: UInt32, exam: UInt32, line: UInt32) {
        self.bg = Color(hex: bg)
        self.card = Color(hex: card)
        self.ink = Color(hex: ink)
        self.muted = Color(hex: muted)
        self.accent = Color(hex: accent)
        self.exam = Color(hex: exam)
        self.line = Color(hex: line)
    }
}

struct Palette {
    let light: Swatch
    let dark: Swatch

    func colors(_ scheme: ColorScheme) -> Swatch { scheme == .dark ? dark : light }
}

/// Themes shared by the app and the widget. Raw values are persisted: do not rename.
enum ThemeID: String, CaseIterable, Identifiable {
    case sand, ocean, forest, graphite, lilac, sunset, mint, contrast

    static let storageKey = "theme"
    var id: String { rawValue }

    var title: String {
        switch self {
        case .sand: return "Песок"
        case .ocean: return "Океан"
        case .forest: return "Лес"
        case .graphite: return "Графит"
        case .lilac: return "Сирень"
        case .sunset: return "Закат"
        case .mint: return "Мята"
        case .contrast: return "Контраст"
        }
    }

    var palette: Palette {
        switch self {
        case .sand:
            return Palette(
                light: Swatch(bg: 0xF4EFE6, card: 0xFFFAF2, ink: 0x14212E, muted: 0x6B7480, accent: 0xC96F12, exam: 0xB4372B, line: 0xE4DCCD),
                dark: Swatch(bg: 0x0B1622, card: 0x13202E, ink: 0xECE6DA, muted: 0x93A0AD, accent: 0xF0A04B, exam: 0xFF7B6B, line: 0x223344))
        case .ocean:
            return Palette(
                light: Swatch(bg: 0xEEF4F8, card: 0xFFFFFF, ink: 0x0F2235, muted: 0x5D6F80, accent: 0x1F6FB2, exam: 0xC0392B, line: 0xD6E2EC),
                dark: Swatch(bg: 0x0A1420, card: 0x122233, ink: 0xE6EEF5, muted: 0x8EA3B5, accent: 0x5FB0FF, exam: 0xFF7B6B, line: 0x1F3347))
        case .forest:
            return Palette(
                light: Swatch(bg: 0xEEF3EC, card: 0xFBFDF9, ink: 0x18261B, muted: 0x667366, accent: 0x2F7D4A, exam: 0xB4372B, line: 0xD7E2D3),
                dark: Swatch(bg: 0x0C1510, card: 0x15211A, ink: 0xE3ECE4, muted: 0x8FA194, accent: 0x6FCF8F, exam: 0xFF7B6B, line: 0x22342A))
        case .graphite:
            return Palette(
                light: Swatch(bg: 0xF2F2F4, card: 0xFFFFFF, ink: 0x1C1C1E, muted: 0x6E6E73, accent: 0x3A3A3C, exam: 0xC0392B, line: 0xDEDEE3),
                dark: Swatch(bg: 0x000000, card: 0x1C1C1E, ink: 0xF2F2F7, muted: 0x98989F, accent: 0xE5E5EA, exam: 0xFF6B5E, line: 0x2C2C2E))
        case .lilac:
            return Palette(
                light: Swatch(bg: 0xF4F0F8, card: 0xFDFBFF, ink: 0x241A33, muted: 0x72688A, accent: 0x7B4FC9, exam: 0xB4372B, line: 0xE2D9EE),
                dark: Swatch(bg: 0x140F1E, card: 0x1E1730, ink: 0xECE6F5, muted: 0xA297B8, accent: 0xB893FF, exam: 0xFF7B8B, line: 0x2E2445))
        case .sunset:
            return Palette(
                light: Swatch(bg: 0xFBEFED, card: 0xFFFAF9, ink: 0x2E1614, muted: 0x85625E, accent: 0xD1495B, exam: 0x9C2A1F, line: 0xF0D9D5),
                dark: Swatch(bg: 0x1A0D10, card: 0x26151A, ink: 0xF5E6E6, muted: 0xB39497, accent: 0xFF7A8A, exam: 0xFFB36B, line: 0x3A2228))
        case .mint:
            return Palette(
                light: Swatch(bg: 0xEAF6F3, card: 0xFAFFFD, ink: 0x10302A, muted: 0x5B7A73, accent: 0x0E9384, exam: 0xC0392B, line: 0xD0E8E2),
                dark: Swatch(bg: 0x061714, card: 0x0E231F, ink: 0xDDF3EE, muted: 0x86A8A0, accent: 0x3DD6C3, exam: 0xFF7B6B, line: 0x1A3530))
        case .contrast:
            return Palette(
                light: Swatch(bg: 0xFFFFFF, card: 0xFFFFFF, ink: 0x000000, muted: 0x3A3A3C, accent: 0x0040DD, exam: 0xD70015, line: 0xC7C7CC),
                dark: Swatch(bg: 0x000000, card: 0x121212, ink: 0xFFFFFF, muted: 0xD1D1D6, accent: 0xFFD60A, exam: 0xFF453A, line: 0x48484A))
        }
    }

    static func from(_ raw: String) -> ThemeID { ThemeID(rawValue: raw) ?? .sand }
}

enum AppearanceMode: String, CaseIterable, Identifiable {
    case system, light, dark

    static let storageKey = "appearance"
    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: return "Как в системе"
        case .light: return "Светлое"
        case .dark: return "Тёмное"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    static func from(_ raw: String) -> AppearanceMode { AppearanceMode(rawValue: raw) ?? .system }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}
