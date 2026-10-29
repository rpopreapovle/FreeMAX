package com.freemax.app.ui.chat

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.freemax.api.model.Attachment
import com.freemax.api.model.Message
import com.freemax.app.ui.chats.ChatAvatar
import com.freemax.app.ui.theme.LocalChatColors
import com.freemax.app.ui.theme.senderAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    isFromMe: Boolean,
    senderName: String? = null,
    senderAvatarUrl: String? = null,
    isGroup: Boolean = false,
    showSenderName: Boolean = true,
    showAvatar: Boolean = true,
    modifier: Modifier = Modifier,
    onReply: (Message) -> Unit = {},
    onMenu: (Message) -> Unit = {},
    onRetry: (Message) -> Unit = {},
    onReaction: (String) -> Unit = {},
    onSenderClick: (Long) -> Unit = {},
    onOpenAttachment: (Message, Attachment) -> Unit = { _, _ -> },
    onSaveAttachment: (Message, Attachment) -> Unit = { _, _ -> },
    busyAttachmentKey: String? = null,
    attachmentProgress: Float? = null
) {
    val chatColors = LocalChatColors.current

    if (message.isService) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
        return
    }

    // «Своё» всегда синее, «чужое» — светлое/тёмное с лёгким оттенком
    // цвета автора. Имя в группе окрашено тем же стабильным акцентом.
    val accent = if (isFromMe) chatColors.outgoingBubble else senderAccent(message.userId, senderName)
    val bubbleColor = if (isFromMe) {
        chatColors.outgoingBubble
    } else {
        // У каждого автора свой оттенок фона, а не один и тот же серый.
        // Достаточно заметно, чтобы различать людей, но текст остаётся
        // читаемым в обеих темах.
        lerp(chatColors.incomingBubble, accent, if (chatColors.dark) 0.26f else 0.15f)
    }
    val textColor = if (isFromMe) chatColors.outgoingText else chatColors.incomingText
    val mutedColor = if (isFromMe) chatColors.outgoingMuted else chatColors.incomingMuted
    val linkColor = if (isFromMe) chatColors.outgoingText else accent
    val metaAccent = if (isFromMe) chatColors.outgoingText else accent

    val shape = if (isFromMe) {
        RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    }

    val avatarGutter = isGroup && !isFromMe
    val avatarSize = 34.dp

    var dragX by remember(message.id) { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Пузырь занимает не всю ширину: так видна структура «аватар →
        // сообщение» и длинные строки не превращаются в сплошную стену.
        val bubbleMax = (maxWidth * if (avatarGutter) 0.74f else 0.82f)
            .coerceAtLeast(160.dp)

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (isFromMe) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            // Свайп влево — ответ. Подсказка появляется с противоположной
            // стороны, чтобы не перекрывать сам пузырь.
            if (dragX < -16f) {
                Icon(
                    imageVector = Icons.Filled.Reply,
                    contentDescription = null,
                    tint = if (isFromMe) chatColors.outgoingBubble else accent,
                    modifier = Modifier
                        .align(if (isFromMe) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 16.dp)
                        .size(24.dp)
                        .graphicsLayer { alpha = (-dragX / 160f).coerceIn(0f, 1f) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Bottom
            ) {
                if (avatarGutter) {
                    if (showAvatar) {
                        ChatAvatar(
                            title = senderName ?: "?",
                            iconUrl = senderAvatarUrl,
                            size = avatarSize,
                            colorKey = message.userId,
                            modifier = Modifier.clickable { onSenderClick(message.userId) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        // Сохраняем колонку аватаров, чтобы сообщения одного
                        // автора не «прыгали» влево.
                        Spacer(modifier = Modifier.width(avatarSize + 8.dp))
                    }
                }

                Column(
                    modifier = Modifier
                        .widthIn(max = bubbleMax)
                        .offset { androidx.compose.ui.unit.IntOffset(dragX.toInt(), 0) }
                        .pointerInput(message.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (dragX < -140f) onReply(message)
                                    dragX = 0f
                                },
                                onDragCancel = { dragX = 0f }
                            ) { _, dragAmount ->
                                dragX = (dragX + dragAmount).coerceIn(-320f, 0f)
                            }
                        }
                        .combinedClickable(
                            onClick = {
                                // Тап по «не отправлено» — повторная отправка того же сообщения
                                if (isFromMe && message.state == Message.STATE_NOT_SENT) onRetry(message)
                            },
                            onLongClick = { if (!message.isService) onMenu(message) }
                        )
                        .clip(shape)
                        .background(bubbleColor)
                        .then(
                            if (isFromMe) Modifier else Modifier.border(
                                width = 1.dp,
                                color = lerp(
                                    chatColors.incomingBorder,
                                    accent,
                                    if (chatColors.dark) 0.28f else 0.22f
                                ),
                                shape = shape
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (isGroup && !isFromMe && showSenderName && !senderName.isNullOrBlank()) {
                        Text(
                            text = senderName,
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.clickable { onSenderClick(message.userId) }
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                    }

                    if (message.isForward) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (message.forwardType == "REPLY") Icons.Filled.Reply else Icons.Filled.Forward,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = metaAccent
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (message.forwardType == "REPLY") "Ответ" else "Переслано",
                                style = MaterialTheme.typography.labelSmall,
                                color = metaAccent
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (!message.quoteText.isNullOrBlank() || message.quoteAttachments.isNotEmpty()) {
                        QuoteView(
                            message = message,
                            textColor = textColor,
                            linkColor = linkColor,
                            accent = accent
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                    }

                    // Caption style: media first, text below — like MAX/TG.
                    // Text-only or link/file messages keep text first.
                    val mediaFirst = message.attachments.any { it.isPhoto || it.isVideo }
                    if (mediaFirst) {
                        if (message.attachments.isNotEmpty()) {
                            AttachmentsView(
                                attachments = message.attachments,
                                textColor = textColor,
                                messageId = message.id,
                                linkColor = linkColor,
                                busyKey = busyAttachmentKey,
                                attachmentProgress = attachmentProgress,
                                onOpen = { onOpenAttachment(message, it) },
                                onSave = { onSaveAttachment(message, it) }
                            )
                        }
                        if (message.text.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            MarkdownMessageText(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor,
                                linkColor = linkColor
                            )
                        }
                    } else {
                        if (message.text.isNotBlank()) {
                            MarkdownMessageText(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor,
                                linkColor = linkColor
                            )
                        }
                        if (message.attachments.isNotEmpty()) {
                            if (message.text.isNotBlank()) Spacer(modifier = Modifier.height(6.dp))
                            AttachmentsView(
                                attachments = message.attachments,
                                textColor = textColor,
                                messageId = message.id,
                                linkColor = linkColor,
                                busyKey = busyAttachmentKey,
                                attachmentProgress = attachmentProgress,
                                onOpen = { onOpenAttachment(message, it) },
                                onSave = { onSaveAttachment(message, it) }
                            )
                        }
                    }

                    if (message.reactions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(5.dp))
                        // Реакции под текстом: тап по «своей» снимает, по чужой/новой — ставит
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            message.reactions.take(8).forEach { r ->
                                val bg = when {
                                    r.mine && isFromMe -> chatColors.outgoingText.copy(alpha = 0.18f)
                                    r.mine -> accent.copy(alpha = 0.16f)
                                    isFromMe -> chatColors.outgoingText.copy(alpha = 0.08f)
                                    else -> textColor.copy(alpha = 0.07f)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(bg)
                                        .border(
                                            width = 1.dp,
                                            color = if (r.mine) metaAccent.copy(alpha = 0.32f)
                                            else textColor.copy(alpha = 0.10f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable { onReaction(r.emoji) }
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text(text = r.emoji, style = MaterialTheme.typography.labelMedium)
                                    if (r.count > 1) {
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = r.count.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = textColor.copy(alpha = 0.85f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // Время и статус — отдельной строкой справа, а не
                    // «приклеенными» к последнему слову.
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.edited) {
                            Text(
                                text = "изм.",
                                style = MaterialTheme.typography.labelSmall,
                                color = mutedColor
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = formatMessageTime(message.time),
                            style = MaterialTheme.typography.labelSmall,
                            color = mutedColor
                        )

                        if (isFromMe) {
                            Spacer(modifier = Modifier.width(4.dp))
                            if (message.state == Message.STATE_NOT_SENT) {
                                Text(
                                    text = "не отправлено",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chatColors.outgoingText
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Повторить отправку",
                                    tint = chatColors.outgoingText,
                                    modifier = Modifier.size(14.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = when (message.state) {
                                        Message.STATE_SENT, Message.STATE_DELIVERED, Message.STATE_READ ->
                                            if (message.state == Message.STATE_SENT) Icons.Filled.Done
                                            else Icons.Filled.DoneAll
                                        else -> Icons.Filled.Schedule
                                    },
                                    contentDescription = when (message.state) {
                                        Message.STATE_READ -> "Прочитано"
                                        Message.STATE_DELIVERED -> "Доставлено"
                                        Message.STATE_SENT -> "Отправлено"
                                        else -> "Отправляется"
                                    },
                                    tint = if (message.state == Message.STATE_READ) {
                                        chatColors.outgoingText
                                    } else {
                                        chatColors.outgoingMuted
                                    },
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Текст сообщения с базовым markdown и кликабельными ссылками.
 * Тап по ссылке уходит в LocalUriHandler (браузер/почта/телефон), ошибки
 * открытия не роняют экран — просто логируются.
 */
@Composable
private fun MarkdownMessageText(
    text: String,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    block: Boolean = true
) {
    val palette = MarkdownPalette(
        link = linkColor,
        codeBackground = color.copy(alpha = 0.14f),
        codeText = color,
        quote = color.copy(alpha = 0.75f),
        bullet = linkColor
    )
    val annotated = remember(text, style.fontSize, palette, block) {
        renderMarkdown(text, style.fontSize, palette, block)
    }
    val context = LocalContext.current
    val baseHandler = LocalUriHandler.current
    val safeHandler = remember(baseHandler, context) {
        object : UriHandler {
            override fun openUri(uri: String) {
                try {
                    baseHandler.openUri(uri)
                } catch (e: Exception) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        android.util.Log.w("MessageBubble", "no handler for $uri")
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides safeHandler) {
        Text(
            text = annotated,
            style = style,
            color = color,
            modifier = modifier,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}

@Composable
private fun QuoteView(message: Message, textColor: Color, linkColor: Color, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(textColor.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(modifier = Modifier.width(8.dp))
        val visual = message.quoteAttachments.firstOrNull {
            it.isPhoto || it.isVideo || it.isSticker
        }
        if (visual != null) {
            val model = when {
                visual.isSticker -> visual.url ?: visual.photoUrl
                visual.isVideo -> visual.thumbUrl ?: visual.preview
                else -> visual.photoUrl ?: visual.preview
            }
            AsyncImage(
                model = model,
                placeholder = previewPainter(visual.preview),
                error = previewPainter(visual.preview),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        val quote = message.quoteText
        if (!quote.isNullOrBlank()) {
            MarkdownMessageText(
                text = quote,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.85f),
                linkColor = linkColor,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                block = false
            )
        }
    }
}

@Composable
private fun AttachmentsView(
    attachments: List<Attachment>,
    textColor: Color,
    messageId: Long = 0L,
    linkColor: Color = Color.Unspecified,
    busyKey: String? = null,
    attachmentProgress: Float? = null,
    onOpen: (Attachment) -> Unit = {},
    onSave: (Attachment) -> Unit = {}
) {
    val photos = attachments.filter { it.isPhoto && (!it.photoUrl.isNullOrBlank() || it.preview != null) }
    val rest = attachments.filterNot { it.isPhoto && (!it.photoUrl.isNullOrBlank() || it.preview != null) }
    if (photos.size > 1) {
        PhotoGrid(
            photos,
            isBusy = { busyKey != null && busyKey == com.freemax.app.data.repository.attachmentKey(messageId, it) },
            onOpen = onOpen,
            onSave = onSave
        )
    } else {
        photos.forEach {
            PhotoView(
                att = it,
                busy = busyKey != null && busyKey == com.freemax.app.data.repository.attachmentKey(messageId, it),
                onOpen = onOpen,
                onSave = onSave
            )
        }
    }
    rest.forEach { att ->
        Spacer(modifier = Modifier.height(4.dp))
        val busy = busyKey != null && busyKey == com.freemax.app.data.repository.attachmentKey(messageId, att)
        when {
            att.isVideo -> VideoView(
                att = att,
                busy = busy,
                progress = if (busy) attachmentProgress else null,
                onOpen = { onOpen(att) },
                onSave = { onSave(att) }
            )
            att.isAudio -> AudioView(att, textColor, messageId, onSave = { onSave(att) })
            att.isFile -> FileView(
                att = att,
                textColor = textColor,
                busy = busy,
                progress = if (busy) attachmentProgress else null,
                onOpen = { onOpen(att) },
                onSave = { onSave(att) }
            )
            att.isSticker -> StickerView(att)
            att.isCall -> CallView(att, textColor)
            att.isContact -> ContactView(att, textColor)
            att.isLocation -> LocationView(att, textColor)
            att.isPoll -> PollView(att, textColor)
            att.isShare -> ShareView(att, textColor, messageId, onOpen, onSave)
            att.type == Attachment.TYPE_INLINE_KEYBOARD -> KeyboardView(att)
            att.isPhoto -> PhotoView(
                att = att,
                busy = busy,
                onOpen = onOpen,
                onSave = onSave
            )
            else -> att.name?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = textColor)
            }
        }
    }
}

@Composable
private fun previewPainter(bytes: ByteArray?) = remember(bytes) {
    try {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()?.let { bmp -> BitmapPainter(bmp) } }
    } catch (_: Exception) { null }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(
    model: Any?,
    placeholder: androidx.compose.ui.graphics.painter.Painter?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onLongClick: (() -> Unit)? = null
) {
    var showPreview by remember { mutableStateOf(false) }
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(model).crossfade(true).build(),
        placeholder = placeholder,
        error = placeholder,
        contentDescription = contentDescription,
        modifier = modifier.combinedClickable(
            onClick = { showPreview = true },
            onLongClick = onLongClick
        ),
        contentScale = contentScale
    )
    if (showPreview) {
        var scale by remember { mutableStateOf(1f) }
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }
        val state = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            if (scale > 1f) {
                offsetX += panChange.x
                offsetY += panChange.y
            } else {
                offsetX = 0f; offsetY = 0f
            }
        }
        Dialog(
            onDismissRequest = { showPreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .transformable(state)
                    .clickable { showPreview = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(model).build(),
                    placeholder = placeholder,
                    error = placeholder,
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                    contentScale = ContentScale.Fit
                )
                // Явная кнопка закрытия: не все догадываются, что по фото можно просто тапнуть.
                IconButton(
                    onClick = { showPreview = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Закрыть фото",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoView(
    att: Attachment,
    busy: Boolean = false,
    onOpen: (Attachment) -> Unit = {},
    onSave: (Attachment) -> Unit = {}
) {
    val ratio = if (att.width > 0 && att.height > 0) att.width.toFloat() / att.height else 4f / 3f
    Box(modifier = Modifier.fillMaxWidth()) {
        ZoomableImage(
            model = att.photoUrl ?: att.preview,
            placeholder = previewPainter(att.preview),
            contentDescription = "Фото",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio.coerceIn(0.4f, 2.5f))
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(8.dp)),
            onLongClick = { onSave(att) }
        )
        if (busy) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun PhotoGrid(
    photos: List<Attachment>,
    isBusy: (Attachment) -> Boolean = { false },
    onOpen: (Attachment) -> Unit = {},
    onSave: (Attachment) -> Unit = {}
) {
    val shown = photos.take(4)
    val extra = photos.size - shown.size
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { att ->
                    Box(modifier = Modifier.weight(1f)) {
                        ZoomableImage(
                            model = att.photoUrl ?: att.preview,
                            placeholder = previewPainter(att.preview),
                            contentDescription = "Фото",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                            onLongClick = { onSave(att) }
                        )
                        if (isBusy(att)) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                        if (extra > 0 && att == row.last() && row == shown.chunked(2).last()) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+$extra",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
                // Balance last odd row
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoView(
    att: Attachment,
    busy: Boolean = false,
    progress: Float? = null,
    onOpen: () -> Unit = {},
    onSave: () -> Unit = {}
) {
    val context = LocalContext.current
    val placeholder = previewPainter(att.preview)
    val model = att.thumbUrl ?: att.preview
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onSave)
    ) {
        if (model != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(model).crossfade(true).build(),
                placeholder = placeholder,
                error = placeholder,
                contentDescription = "Видео",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.White
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Скачать и открыть",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        if (att.durationMs > 0) {
            Text(
                text = fmtDuration(att.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileView(
    att: Attachment,
    textColor: Color,
    busy: Boolean = false,
    progress: Float? = null,
    onOpen: () -> Unit = {},
    onSave: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onSave)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = "Открыть файл",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = att.name ?: "Файл",
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (att.size > 0) {
                Text(
                    text = fmtSize(att.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f)
                )
            }
            if (busy) {
                Spacer(modifier = Modifier.height(4.dp))
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    )
                }
            }
        }
        if (!busy) {
            IconButton(
                onClick = onSave,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Скачать в Загрузки",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioView(att: Attachment, textColor: Color, messageId: Long = 0L, onSave: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    // Private owner-only STT: auto transcript under every voice message
    val sttViewModel: SttViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val sttEnabled by sttViewModel.enabled.collectAsState()
    val transcripts by sttViewModel.transcripts.collectAsState()
    val sttPending by sttViewModel.pending.collectAsState()
    val audioKey = if (att.id != 0L) att.id else messageId
    val transcript = transcripts[audioKey]
    val transcribing = sttEnabled && transcript == null && sttPending.contains(audioKey)
    android.util.Log.d("SttUi", "AudioView key=$audioKey enabled=$sttEnabled hasText=${transcript != null}")
    LaunchedEffect(audioKey, att.audioUrl, sttEnabled) {
        if (sttEnabled) sttViewModel.ensure(audioKey, att.audioUrl)
    }
    var playing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(att.durationMs) }
    val playerHolder = remember { arrayOfNulls<MediaPlayer>(1) }
    val canPlay = !att.audioUrl.isNullOrBlank()

    DisposableEffect(att.audioUrl) {
        onDispose {
            try {
                playerHolder[0]?.stop()
            } catch (_: Exception) {}
            try {
                playerHolder[0]?.release()
            } catch (_: Exception) {}
            playerHolder[0] = null
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            delay(250)
            try {
                val p = playerHolder[0]
                if (p != null) {
                    posMs = p.currentPosition.toLong()
                    if (p.duration > 0) durMs = p.duration.toLong()
                }
            } catch (_: Exception) {}
        }
    }
    fun toggle() {
        if (!canPlay) return
        val existing = playerHolder[0]
        if (existing != null && readyPlaying(existing)) {
            try {
                if (existing.isPlaying) {
                    existing.pause()
                    playing = false
                } else {
                    existing.start()
                    playing = true
                }
            } catch (_: Exception) {}
            return
        }
        if (preparing) return
        preparing = true
        scope.launch {
            try {
                val mp = MediaPlayer()
                mp.setDataSource(att.audioUrl)
                mp.setOnPreparedListener {
                    playerHolder[0] = it
                    preparing = false
                    try {
                        if (it.duration > 0) durMs = it.duration.toLong()
                        it.start()
                        playing = true
                    } catch (_: Exception) {}
                }
                mp.setOnCompletionListener {
                    playing = false
                    posMs = 0L
                    try { it.seekTo(0) } catch (_: Exception) {}
                }
                mp.setOnErrorListener { _, _, _ ->
                    preparing = false
                    playing = false
                    true
                }
                mp.prepareAsync()
            } catch (_: Exception) {
                preparing = false
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { toggle() }, onLongClick = onSave)
    ) {
        IconButton(onClick = { toggle() }, enabled = canPlay) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Пауза" else "Слушать",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            if (att.wave.isNotEmpty()) {
                val frac = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
                val played = MaterialTheme.colorScheme.primary
                val rest = textColor.copy(alpha = 0.3f)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .pointerInput(durMs) {
                            detectTapGestures { offset ->
                                val p = playerHolder[0] ?: return@detectTapGestures
                                try {
                                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                                    p.seekTo((durMs * f).toInt())
                                    posMs = (durMs * f).toLong()
                                } catch (_: Exception) {}
                            }
                        }
                ) {
                    val bars = att.wave.take(48)
                    if (bars.isNotEmpty()) {
                        val bw = size.width / bars.size
                        bars.forEachIndexed { i, v ->
                            val h = (v / 255f).coerceIn(0.08f, 1f) * size.height
                            drawRect(
                                color = if ((i + 1f) / bars.size <= frac) played else rest,
                                topLeft = androidx.compose.ui.geometry.Offset(i * bw + bw * 0.2f, (size.height - h) / 2),
                                size = androidx.compose.ui.geometry.Size(bw * 0.6f, h)
                            )
                        }
                    }
                }
            } else {
                LinearProgressIndicator(
                    progress = { if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = fmtDuration(posMs) + if (durMs > 0) " / " + fmtDuration(durMs) else "",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f)
            )
            if (sttEnabled) {
                if (transcript != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.9f)
                    )
                } else if (transcribing) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Распознаётся…",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

private fun readyPlaying(mp: MediaPlayer): Boolean {
    return try {
        mp.isPlaying || true
    } catch (_: Exception) { false }
}

@Composable
private fun StickerView(att: Attachment) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(att.url ?: att.photoUrl).crossfade(true).build(),
        contentDescription = "Стикер",
        modifier = Modifier.size(128.dp),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun CallView(att: Attachment, textColor: Color) {
    val parts = (att.extra ?: "").split("/")
    val isVideo = parts.getOrNull(0) == "VIDEO"
    val missed = parts.getOrNull(1) == "MISSED"
    val accent = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Call,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = when {
                    missed && isVideo -> "Пропущенный видеозвонок"
                    missed -> "Пропущенный звонок"
                    isVideo -> "Видеозвонок"
                    else -> "Аудиозвонок"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (missed) accent else textColor
            )
            if (att.durationMs > 0) {
                Text(
                    text = fmtDuration(att.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ContactView(att: Attachment, textColor: Color) {
    val context = LocalContext.current
    val phone = att.phone
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (!phone.isNullOrBlank()) {
                    it.clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                        } catch (_: Exception) {}
                    }
                } else it
            }
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (!att.photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = att.photoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = att.name ?: "Контакт",
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!phone.isNullOrBlank()) {
                Text(
                    text = phone,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun LocationView(att: Attachment, textColor: Color) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${att.name}?q=${att.name}")))
                } catch (_: Exception) {}
            }
    ) {
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = "Геопозиция", style = MaterialTheme.typography.bodyMedium, color = textColor)
            Text(
                text = att.name ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun PollView(att: Attachment, textColor: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.HowToVote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = att.title ?: "Опрос",
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
        }
        if (att.pollOptions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            att.pollOptions.forEachIndexed { i, opt ->
                Text(
                    text = "${i + 1}. $opt",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.85f),
                    modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ShareView(
    att: Attachment,
    textColor: Color,
    messageId: Long = 0L,
    onOpen: (Attachment) -> Unit = {},
    onSave: (Attachment) -> Unit = {}
) {
    val context = LocalContext.current
    // Nested forwarded content inside a share card
    val nestedDesc = att.description
    if (att.title == "Переслано" && att.nested.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(textColor.copy(alpha = 0.08f))
                .padding(8.dp)
        ) {
            if (!nestedDesc.isNullOrBlank()) {
                Text(text = nestedDesc, style = MaterialTheme.typography.bodyMedium, color = textColor)
            }
            AttachmentsView(
                attachments = att.nested,
                textColor = textColor,
                messageId = messageId,
                busyKey = null,
                onOpen = onOpen,
                onSave = onSave
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .let {
                if (!att.targetUrl.isNullOrBlank()) {
                    it.clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(att.targetUrl)))
                        } catch (_: Exception) {}
                    }
                } else it
            }
    ) {
        if (!att.thumbUrl.isNullOrBlank()) {
            AsyncImage(
                model = att.thumbUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = att.title ?: att.targetUrl ?: "Ссылка",
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        val shareDesc = att.description
        if (!shareDesc.isNullOrBlank()) {
            Text(
                text = shareDesc,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.7f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun KeyboardView(att: Attachment) {
    Column(modifier = Modifier.fillMaxWidth()) {
        att.keyboard.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { label ->
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

private fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0) return ""
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%d КБ".format(kb.toInt())
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f МБ".format(mb)
    return "%.1f ГБ".format(mb / 1024.0)
}

private fun formatMessageTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val date = Date(timestamp)
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
}
