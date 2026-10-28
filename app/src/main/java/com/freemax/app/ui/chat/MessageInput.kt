package com.freemax.app.ui.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.freemax.api.model.Message
import com.freemax.app.data.repository.AttachKind
import com.freemax.app.data.repository.PendingAttach

@Composable
fun MessageInput(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    replyTo: Message? = null,
    replySenderName: String? = null,
    onCancelReply: () -> Unit = {},
    pendingAttaches: List<PendingAttach> = emptyList(),
    onRemoveAttach: (Uri) -> Unit = {},
    onPickPhoto: () -> Unit = {},
    onPickVideo: () -> Unit = {},
    onPickAudio: () -> Unit = {},
    onPickFile: () -> Unit = {},
    isUploading: Boolean = false,
    uploadProgress: Float? = null,
    /** Ключ чата: при смене чата поле пересоздаётся с его черновиком. */
    draftKey: Long = 0L,
    initialText: String = "",
    onTextChange: (String) -> Unit = {},
    /** Готовое голосовое: uri + длительность мс. */
    onVoiceRecorded: (Uri, Long) -> Unit = { _, _ -> },
    onPickCamera: () -> Unit = {}
) {
    var text by remember(draftKey) { mutableStateOf(initialText) }
    val focusManager = LocalFocusManager.current
    var showAttachMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val recorder = remember(draftKey) { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableIntStateOf(0) }

    // Разрешение на микрофон
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (recorder.start()) {
                recording = true
                recSeconds = 0
            }
        }
    }

    // Таймер записи
    LaunchedEffect(recording) {
        while (recording) {
            kotlinx.coroutines.delay(500)
            recSeconds = (recorder.elapsedMs() / 1000L).toInt()
        }
    }
    DisposableEffect(draftKey) {
        onDispose { if (recorder.isRecording) recorder.cancel() }
    }

    fun startRecording() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        if (recorder.start()) {
            recording = true
            recSeconds = 0
        }
    }

    fun finishRecording(send: Boolean) {
        recording = false
        if (!send) {
            recorder.cancel()
            return
        }
        val res = recorder.stop()
        if (res != null) {
            onVoiceRecorded(res.first, res.second)
        }
    }

    fun doSend() {
        if ((text.isNotBlank() || pendingAttaches.isNotEmpty()) && !isUploading) {
            onSend(text)
            text = ""
            focusManager.clearFocus()
        }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Reply preview
            if (replyTo != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = replySenderName ?: "Сообщение",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = replyTo.text.ifBlank { "Вложение" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onCancelReply) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Отменить ответ",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Upload progress animation
            if (isUploading) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { uploadProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (uploadProgress != null) {
                        Text(
                            text = "Загрузка… ${(uploadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }
                HorizontalDivider()
            }

            // Pending attachments chips
            if (pendingAttaches.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pendingAttaches, key = { it.uri.toString() }) { p ->
                        AttachChip(attach = p, onRemove = { onRemoveAttach(p.uri) })
                    }
                }
                HorizontalDivider()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                if (recording) {
                    // Идёт запись голосового: отмена / таймер / отправка
                    FilledTonalIconButton(onClick = { finishRecording(false) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Отменить запись"
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = "●",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "%d:%02d".format(recSeconds / 60, recSeconds % 60),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Запись голосового…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledIconButton(onClick = { finishRecording(true) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить голосовое"
                        )
                    }
                } else {
                    // Скрепка всегда слева — как в Conversations/Telegram:
                    // структура поля не меняется от того, пустое оно или нет.
                    FilledTonalIconButton(
                        onClick = { showAttachMenu = true },
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                id = com.freemax.app.R.drawable.ic_plus_tabler
                            ),
                            contentDescription = "Прикрепить",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 40.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = text,
                                onValueChange = {
                                    text = it
                                    onTextChange(it)
                                },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                    MaterialTheme.colorScheme.primary
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Default
                                ),
                                minLines = 1,
                                maxLines = 6,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            if (text.isEmpty()) {
                                Text(
                                    text = "Сообщение",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }

                    if (text.isBlank() && pendingAttaches.isEmpty()) {
                        FilledTonalIconButton(
                            onClick = { startRecording() },
                            modifier = Modifier.size(44.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Голосовое сообщение",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else {
                        FilledIconButton(
                            onClick = { doSend() },
                            enabled = !isUploading,
                            modifier = Modifier.size(44.dp)
                        ) {
                            if (isUploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Отправить",
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAttachMenu) {
        AttachPickerDialog(
            onDismiss = { showAttachMenu = false },
            onPickCamera = { showAttachMenu = false; onPickCamera() },
            onPickPhoto = { showAttachMenu = false; onPickPhoto() },
            onPickVideo = { showAttachMenu = false; onPickVideo() },
            onPickAudio = { showAttachMenu = false; onPickAudio() },
            onPickFile = { showAttachMenu = false; onPickFile() }
        )
    }
}

@Composable
private fun AttachChip(attach: PendingAttach, onRemove: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 5.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            if (attach.kind == AttachKind.PHOTO) {
                AsyncImage(
                    model = attach.uri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(48.dp)
                        .padding(8.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = attach.name ?: attach.kind.name.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Убрать",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachPickerDialog(
    onDismiss: () -> Unit,
    onPickCamera: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = "Прикрепить",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
            )
            AttachOption(Icons.Filled.CameraAlt, "Камера", onPickCamera)
            AttachOption(Icons.Filled.Image, "Фото", onPickPhoto)
            AttachOption(Icons.Filled.Videocam, "Видео", onPickVideo)
            AttachOption(Icons.Filled.Audiotrack, "Аудио", onPickAudio)
            AttachOption(Icons.Filled.AttachFile, "Файл", onPickFile)
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text("Отмена")
            }
        }
    }
}

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
