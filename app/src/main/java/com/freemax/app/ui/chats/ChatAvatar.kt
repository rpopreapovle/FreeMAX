package com.freemax.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.freemax.app.ui.theme.avatarBackground
import com.freemax.app.ui.theme.avatarForeground

/**
 * Аватар чата или участника группы.
 *
 * Цвет фона/буквы детерминированно зависит от названия: у разных людей
 * в группе аватары не выглядят одинаково, а «Избранное» остаётся заметным
 * отдельным акцентом.
 */
@Composable
fun ChatAvatar(
    title: String,
    iconUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    /** Избранное: вместо инициалов — иконка закладки. */
    isSaved: Boolean = false,
    /** Стабильный ключ (id контакта/чата), чтобы цвет не зависел от переименования. */
    colorKey: Long = 0L,
    /** Зелёная точка присутствия (для диалогов, где известен статус). */
    online: Boolean = false
) {
    val background = if (isSaved) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        avatarBackground(colorKey, title)
    }
    val foreground = if (isSaved) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        avatarForeground(colorKey, title)
    }
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center
        ) {
            when {
                !iconUrl.isNullOrBlank() -> AsyncImage(
                    model = iconUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                isSaved -> Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = "Избранное",
                    modifier = Modifier.fillMaxSize(0.55f),
                    tint = foreground
                )
                else -> Text(
                    text = title.firstOrNull()?.uppercase() ?: "?",
                    style = if (size >= 48.dp) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = foreground
                )
            }
        }
        if (online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.24f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape
                    )
            )
        }
    }
}
