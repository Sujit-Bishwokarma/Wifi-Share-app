package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Global state to track dynamic system dark theme preference
var isSystemDark: Boolean = false

// Sleek pure blue-accented color palette with high accessibility contrast
val SleekPrimary: Color
    get() = if (isSystemDark) Color(0xFF60A5FA) else Color(0xFF2F54EB) // Gorgeous Cobalt/Electric Blue matching the image

val SleekBackground: Color
    get() = if (isSystemDark) Color(0xFF0F172A) else Color(0xFFFAF8F5) // Gorgeous Warm White/Cream background

val SleekTextDark: Color
    get() = if (isSystemDark) Color(0xFFF8FAFC) else Color(0xFF1E293B) // Dark slate for sharp reading

val SleekTextSecondary: Color
    get() = if (isSystemDark) Color(0xFF94A3B8) else Color(0xFF64748B) // Sleek secondary text

val SleekHeroBg: Color
    get() = if (isSystemDark) Color(0xFF1E293B) else Color(0xFFFFFFFF) // Pure sparkling white surface card matching the image

val SleekInnerCircle: Color
    get() = if (isSystemDark) Color(0xFF1E1B4B) else Color(0xFFEFF6FF) // Cobalt light blue accent container

val SleekIconDark: Color
    get() = if (isSystemDark) Color(0xFF60A5FA) else Color(0xFF2F54EB) // Icon foreground

val SleekBorder: Color
    get() = if (isSystemDark) Color(0xFF334155) else Color(0xFFEFEAE2) // Beautiful warm-matching subtle border lines

val SleekActivePill: Color
    get() = if (isSystemDark) Color(0xFF2F54EB).copy(alpha = 0.2f) else Color(0xFFDBEAFE) // Soft blue highlight container


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
