package com.freemax.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Стабильный цвет автора: одинаковый id/имя всегда даёт один и тот же
 * акцент и в шапке сообщения, и в аватаре, и после перезапуска приложения.
 */
@Composable
fun senderAccent(key: Long, fallback: String? = null): Color {
    val palette = LocalChatColors.current.senderPalette
    return palette[stablePaletteIndex(key, fallback, palette.size)]
}

@Composable
fun senderAccent(seed: String): Color = senderAccent(0L, seed)

@Composable
fun avatarBackground(key: Long, fallback: String): Color {
    val colors = LocalChatColors.current
    val accent = senderAccent(key, fallback)
    return lerp(
        if (colors.dark) colors.incomingBubble else Color.White,
        accent,
        if (colors.dark) 0.34f else 0.16f
    )
}

@Composable
fun avatarBackground(seed: String): Color = avatarBackground(0L, seed)

@Composable
fun avatarForeground(key: Long, fallback: String): Color = senderAccent(key, fallback)

@Composable
fun avatarForeground(seed: String): Color = avatarForeground(0L, seed)

private fun stablePaletteIndex(key: Long, fallback: String?, size: Int): Int {
    if (size <= 0) return 0
    val raw = if (key != 0L) {
        (key xor (key ushr 32)).toInt()
    } else {
        fallback?.hashCode() ?: 0
    }
    return (raw and Int.MAX_VALUE) % size
}
