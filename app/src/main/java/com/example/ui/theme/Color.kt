package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Global state to track dynamic system dark theme preference
var isSystemDark: Boolean = false

// Sleek pure blue-accented color palette with high accessibility contrast
val SleekPrimary: Color
    get() = if (isSystemDark) Color(0xFF5C93FF) else Color(0xFF0044FF) // Vibrant Pure Sapphire Blue

val SleekBackground: Color
    get() = if (isSystemDark) Color(0xFF0F1221) else Color(0xFFFDFBF7) // Rich Warm Cream White (Light) / Luxurious Deep Midnight Sapphire (Dark)

val SleekTextDark: Color
    get() = if (isSystemDark) Color(0xFFF1F5F9) else Color(0xFF0D0E12) // Near Solid off-white for crisp reading in dark mode

val SleekTextSecondary: Color
    get() = if (isSystemDark) Color(0xFF94A3B8) else Color(0xFF374151) // Highly legible mid-tone text (Slate Blue in Dark Mode)

val SleekHeroBg: Color
    get() = if (isSystemDark) Color(0xFF191D34) else Color(0xFFF5EFE2) // Slightly elevated night card for perfect elements

val SleekInnerCircle: Color
    get() = if (isSystemDark) Color(0xFF141930) else Color(0xFFE5EEFF) // Accent ring container

val SleekIconDark: Color
    get() = if (isSystemDark) Color(0xFF60A5FA) else Color(0xFF0044FF) // Icon foreground

val SleekBorder: Color
    get() = if (isSystemDark) Color(0xFF2B3356) else Color(0xFFE6DEC9) // Elegant sophisticated borders

val SleekActivePill: Color
    get() = if (isSystemDark) Color(0xFF26305C) else Color(0xFFEBE3CF) // Soft premium pill indicator background

val SleekDarkBg: Color
    get() = Color(0xFF0A0D1A)

val SleekDarkHeader: Color
    get() = Color(0xFF15192D)

val SleekLogsText: Color
    get() = Color(0xFFF8FAFC)

val SleekLogsHeader: Color
    get() = if (isSystemDark) Color(0xFF93C5FD) else Color(0xFF0044FF)

// Compatibility fallbacks for general material themes
val Purple80 = Color(0xFF93C5FD)
val PurpleGrey80 = Color(0xFFCBD5E1)
val Pink80 = Color(0xFFFDA4AF)

val Purple40 = Color(0xFF0044FF)
val PurpleGrey40 = Color(0xFF64748B)
val Pink40 = Color(0xFFF43F5E)
