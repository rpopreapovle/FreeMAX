package com.freemax.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Отдельная палитра для переписки.
 *
 * Material-схема отвечает за системные элементы (шапки, диалоги, настройки),
 * а здесь лежат цвета, по которым пользователь читает сам чат: фон переписки,
 * входящие/исходящие пузыри и стабильные цвета авторов в группах.
 *
 * Такой набор не зависит от dynamic color и обоев устройства, поэтому
 * «мои» и «чужие» сообщения всегда различимы одинаково.
 */
@Immutable
data class ChatColors(
    val dark: Boolean,
    val background: Color,
    val incomingBubble: Color,
    val incomingText: Color,
    val incomingMuted: Color,
    val incomingBorder: Color,
    val outgoingBubble: Color,
    val outgoingText: Color,
    val outgoingMuted: Color,
    val unreadRow: Color,
    /** Стабильные акценты для имён/аватаров участников группы. */
    val senderPalette: List<Color>
)

val LightChatColors = ChatColors(
    dark = false,
    background = Color(0xFFF2F5FA),
    incomingBubble = Color(0xFFFFFFFF),
    incomingText = Color(0xFF15212B),
    incomingMuted = Color(0xFF6B7785),
    incomingBorder = Color(0xFFE1E8F0),
    outgoingBubble = Color(0xFF1F6FEB),
    outgoingText = Color(0xFFFFFFFF),
    outgoingMuted = Color(0xCCFFFFFF),
    unreadRow = Color(0xFFE7F0FF),
    senderPalette = listOf(
        Color(0xFF1D5FA8), // синий
        Color(0xFF7B3FA0), // фиолетовый
        Color(0xFF2E7D32), // зелёный
        Color(0xFFA64B00), // оранжевый
        Color(0xFF9C2338), // красный
        Color(0xFF00695C), // бирюзовый
        Color(0xFF5B4BB7), // индиго
        Color(0xFF8A6500), // горчичный
        Color(0xFF00707A), // циан
        Color(0xFFA0336B)  // розовый
    )
)

val DarkChatColors = ChatColors(
    dark = true,
    background = Color(0xFF0D1319),
    incomingBubble = Color(0xFF19232D),
    incomingText = Color(0xFFE9EFF5),
    incomingMuted = Color(0xFF93A2B1),
    incomingBorder = Color(0xFF283440),
    outgoingBubble = Color(0xFF2C64B0),
    outgoingText = Color(0xFFF6FAFF),
    outgoingMuted = Color(0xBFF6FAFF),
    unreadRow = Color(0xFF111C27),
    senderPalette = listOf(
        Color(0xFF8CC5FF), // синий
        Color(0xFFD7A6F3), // фиолетовый
        Color(0xFF9BDBA0), // зелёный
        Color(0xFFFFBE85), // оранжевый
        Color(0xFFFFA6B4), // красный
        Color(0xFF80D6CC), // бирюзовый
        Color(0xFFB8AFF5), // индиго
        Color(0xFFE8CF7A), // горчичный
        Color(0xFF7FD6E0), // циан
        Color(0xFFF2A3C7)  // розовый
    )
)

val LocalChatColors = staticCompositionLocalOf { LightChatColors }
