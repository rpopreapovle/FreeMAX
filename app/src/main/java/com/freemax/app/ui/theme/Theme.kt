package com.freemax.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * Спокойная фиксированная палитра вместо случайных цветов Material You.
 * Она нужна, чтобы интерфейс выглядел одинаково на всех устройствах, а
 * акцентные элементы (кнопки, бейджи, выбранные состояния) не сливались
 * с фоном и между собой.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8CC5FF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF1F4E7A),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0xFFB8C8DA),
    onSecondary = Color(0xFF22303D),
    secondaryContainer = Color(0xFF2D3D4D),
    onSecondaryContainer = Color(0xFFD4E4F7),
    tertiary = Color(0xFF9BDBA0),
    onTertiary = Color(0xFF00391B),
    tertiaryContainer = Color(0xFF1D5131),
    onTertiaryContainer = Color(0xFFB8F2BB),
    background = Color(0xFF0D1319),
    onBackground = Color(0xFFE4EAF1),
    surface = Color(0xFF0D1319),
    onSurface = Color(0xFFE4EAF1),
    surfaceVariant = Color(0xFF3F4852),
    onSurfaceVariant = Color(0xFFBFC8D2),
    surfaceContainerLowest = Color(0xFF080D12),
    surfaceContainerLow = Color(0xFF151C23),
    surfaceContainer = Color(0xFF19212A),
    surfaceContainerHigh = Color(0xFF202933),
    surfaceContainerHighest = Color(0xFF2A3440),
    outline = Color(0xFF8B96A3),
    outlineVariant = Color(0xFF3D4854),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE4EAF1),
    inverseOnSurface = Color(0xFF28313A),
    inversePrimary = Color(0xFF1F6FEB)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E7FF),
    onPrimaryContainer = Color(0xFF002A5C),
    secondary = Color(0xFF506174),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE8F7),
    onSecondaryContainer = Color(0xFF0E1E2C),
    tertiary = Color(0xFF2E7D32),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC8E6C9),
    onTertiaryContainer = Color(0xFF002106),
    background = Color(0xFFF2F5FA),
    onBackground = Color(0xFF15212B),
    surface = Color(0xFFF9FBFD),
    onSurface = Color(0xFF15212B),
    surfaceVariant = Color(0xFFDDE5EE),
    onSurfaceVariant = Color(0xFF46525E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F9FC),
    surfaceContainer = Color(0xFFF0F4F8),
    surfaceContainerHigh = Color(0xFFEAF0F6),
    surfaceContainerHighest = Color(0xFFE3EAF1),
    outline = Color(0xFF727E8A),
    outlineVariant = Color(0xFFC6D0DA),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF29323B),
    inverseOnSurface = Color(0xFFEFF3F7),
    inversePrimary = Color(0xFF8CC5FF)
)

@Composable
fun FreeMAXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Material You оставлен как возможность, но по умолчанию выключен:
     * в мессенджере важнее предсказуемый контраст «своё/чужое», чем
     * цветовые пятна из обоев пользователя.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalChatColors provides if (darkTheme) DarkChatColors else LightChatColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
