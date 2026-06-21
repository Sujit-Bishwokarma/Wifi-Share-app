package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

private val LightColorScheme =
  lightColorScheme(
    primary = SleekPrimary,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = SleekBackground,
    surface = SleekBackground,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    onBackground = SleekTextDark,
    onSurface = SleekTextDark,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disable dynamic color to maintain custom professional color contrast stability
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  // Sync the system dark theme state to our custom dynamic Sleek color engine
  isSystemDark = darkTheme

  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> {
        // Build crisp Dark Theme scheme
        darkColorScheme(
          primary = SleekPrimary,
          secondary = PurpleGrey80,
          background = SleekBackground,
          surface = SleekBackground,
          onBackground = SleekTextDark,
          onSurface = SleekTextDark
        )
      }
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
