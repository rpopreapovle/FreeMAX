package com.freemax.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freemax.app.ui.theme.LocalChatColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onProfileClick: (HeaderTarget, String) -> Unit = { _, _ -> },
    onOpenContact: (Long) -> Unit = {},
    onSearchClick: (Long) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val chatColors = LocalChatColors.current
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var followBottom by remember { mutableStateOf(true) }
    var unseenCount by remember { mutableIntStateOf(0) }
    var lastSeenSize by remember { mutableIntStateOf(0) }
    var didInitialJump by remember { mutableStateOf(false) }

    // Timeline with WhatsApp-style day separators
    val timeline = remember(uiState.messages, uiState.firstUnreadId) {
        buildTimeline(uiState.messages, uiState.firstUnreadId)
    }
    var menuMessage by remember { mutableStateOf<com.freemax.api.model.Message?>(null) }
    var deleteMessage by remember { mutableStateOf<com.freemax.api.model.Message?>(null) }
    var forwardMessage by remember { mutableStateOf<com.freemax.api.model.Message?>(null) }
    var editMessage by remember { mutableStateOf<com.freemax.api.model.Message?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Скачивание/сохранение вложений: прогресс и ошибки — через снэкбар
    LaunchedEffect(uiState.toastId) {
        val msg = uiState.toast ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeToast()
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.addAttaches(listOf(uri))
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.addAttaches(listOf(uri))
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.addAttaches(listOf(uri))
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.addAttaches(listOf(uri))
    }

    // Камера: снимаем во внутренний кэш через FileProvider
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) {
            viewModel.addAttaches(listOf(uri))
        } else {
            try {
                uri?.path?.let { java.io.File(it).delete() }
            } catch (_: Exception) {}
        }
        cameraUri = null
    }
    fun launchCamera() {
        try {
            val dir = java.io.File(context.cacheDir, "images").apply { mkdirs() }
            val f = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", f
            )
            cameraUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            android.util.Log.e("ChatScreen", "camera launch failed", e)
        }
    }

    // Track whether the user sits at the bottom (realtime follow mode)
    LaunchedEffect(listState.isScrollInProgress, uiState.messages.size) {
        if (!listState.isScrollInProgress) {
            val layout = listState.layoutInfo
            val total = layout.totalItemsCount
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            followBottom = total == 0 || lastVisible >= total - 3
            if (followBottom) unseenCount = 0
        }
    }
    // First load: jump to the unread divider if there is one, else to the bottom
    LaunchedEffect(timeline.isNotEmpty()) {
        if (timeline.isNotEmpty() && !didInitialJump) {
            didInitialJump = true
            followBottom = true
            unseenCount = 0
            lastSeenSize = uiState.messages.size
            try {
                val unreadIdx = uiState.firstUnreadId?.let { id ->
                    timeline.indexOfFirst { it is TimelineItem.Unread || (it is TimelineItem.Msg && it.message.id == id) }
                } ?: -1
                if (unreadIdx > 0) {
                    followBottom = false
                    listState.scrollToItem((unreadIdx - 1).coerceAtLeast(0))
                } else {
                    listState.scrollToItem(timeline.size - 1)
                }
            } catch (_: Exception) {}
        }
    }
    // Smart scroll: follow only when at bottom, else count unseen
    LaunchedEffect(uiState.messages.size) {
        val size = uiState.messages.size
        if (size == 0) {
            lastSeenSize = 0
            unseenCount = 0
            didInitialJump = false
        } else if (size > lastSeenSize) {
            if (followBottom && didInitialJump) {
                listState.animateScrollToItem(timeline.size - 1)
                unseenCount = 0
            } else if (didInitialJump) {
                unseenCount += size - lastSeenSize
            }
            lastSeenSize = size
        } else {
            lastSeenSize = size
        }
    }

    // Pagination upward: user reached the top of the loaded page.
    // Keys intentionally exclude messages/loading: a completed page must NOT
    // re-fire by itself (no cascade), only the next scroll gesture does.
    // loadMore() itself guards single-flight and exhaustion.
    LaunchedEffect(listState.firstVisibleItemIndex, listState.isScrollInProgress, didInitialJump) {
        if (didInitialJump && !listState.isScrollInProgress && listState.firstVisibleItemIndex <= 3) {
            viewModel.loadMore()
        }
    }

    val scope = rememberCoroutineScope()
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!followBottom && (unseenCount > 0 || uiState.messages.isNotEmpty())) {
                SmallFloatingActionButton(
                    onClick = {
                        followBottom = true
                        unseenCount = 0
                        scope.launch {
                            if (timeline.isNotEmpty()) {
                                try {
                                    listState.animateScrollToItem(timeline.size - 1)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowDownward,
                            contentDescription = "Вниз"
                        )
                        if (unseenCount > 0) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = unseenCount.coerceAtMost(99).toString())
                        }
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = uiState.headerTarget != null,
                                onClick = {
                                    uiState.headerTarget?.let { onProfileClick(it, uiState.chatTitle) }
                                }
                            )
                    ) {
                        com.freemax.app.ui.chats.ChatAvatar(
                            title = uiState.chatTitle,
                            iconUrl = uiState.chatIconUrl,
                            size = 38.dp,
                            isSaved = uiState.isSavedMessages,
                            colorKey = uiState.chatId,
                            online = uiState.chatStatusText == "онлайн"
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = uiState.chatTitle.ifEmpty {
                                    if (uiState.isSavedMessages) "Избранное" else "Чат"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            val status = uiState.typingText ?: uiState.chatStatusText
                            if (status != null) {
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (status == "онлайн") MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onSearchClick(uiState.chatId) }) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Поиск в чате"
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding()
            ) {
                // Realtime typing indicator
                val typing = uiState.typingText
                if (typing != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = typing,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                val reply = uiState.replyTo
                val replyName = reply?.let { r ->
                    if (r.userId == uiState.viewerId) "Вы"
                    else uiState.senderNames[r.userId]
                        ?: if (!uiState.isGroup) uiState.chatTitle.ifBlank { null } else null
                }
                MessageInput(
                    onSend = { viewModel.sendMessage(it) },
                    replyTo = reply,
                    replySenderName = replyName,
                    onCancelReply = { viewModel.setReply(null) },
                    pendingAttaches = uiState.pendingAttaches,
                    onRemoveAttach = { viewModel.removeAttach(it) },
                    onPickPhoto = { pickPhoto.launch("image/*") },
                    onPickVideo = { pickVideo.launch("video/*") },
                    onPickAudio = { pickAudio.launch("audio/*") },
                    onPickFile = { pickFile.launch("*/*") },
                    isUploading = uiState.isUploading,
                    uploadProgress = uiState.uploadProgress,
                    draftKey = uiState.chatId,
                    initialText = uiState.draft,
                    onTextChange = { viewModel.onDraftChange(it) },
                    onVoiceRecorded = { uri, durationMs ->
                        viewModel.addAttaches(listOf(uri), voiceDurationMs = durationMs)
                    },
                    onPickCamera = { launchCamera() }
                )
            }
        }
    ) { padding ->
        when {
            uiState.isLoading && uiState.messages.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.messages.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (!uiState.isLoading) "Здесь пока пусто" else "Загрузка…",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Напишите первое сообщение — оно появится здесь и у собеседника.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(chatColors.background)
                        .padding(padding)
                ) {
                    if (uiState.isLoadingMore) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                    timeline.forEach { item ->
                        when (item) {
                            is TimelineItem.Day -> {
                                stickyHeader(key = "day-${item.dayKey}") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = item.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                                .border(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                                )
                                                .padding(horizontal = 14.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                            is TimelineItem.Unread -> {
                                item(key = "unread-divider") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        HorizontalDivider(
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        )
                                        Text(
                                            text = "Новые сообщения",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 10.dp)
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        )
                                    }
                                }
                            }
                            is TimelineItem.Msg -> {
                                val message = item.message
                                item(key = "msg-${message.id}") {
                                    val senderName = uiState.senderNames[message.userId]
                                    BoxWithConstraints {
                                        val menuOffset = DpOffset(
                                            x = if (message.userId == uiState.viewerId) {
                                                (maxWidth - 300.dp).coerceAtLeast(0.dp)
                                            } else 8.dp,
                                            y = 0.dp
                                        )
                                        MessageBubble(
                                            message = message,
                                            isFromMe = message.userId == uiState.viewerId,
                                            senderName = senderName,
                                            senderAvatarUrl = uiState.senderAvatars[message.userId],
                                            isGroup = uiState.isGroup,
                                            showSenderName = item.showHeader,
                                            showAvatar = item.showAvatar,
                                            modifier = Modifier.padding(
                                                start = 8.dp,
                                                end = 8.dp,
                                                top = if (item.showHeader) 8.dp else 0.dp
                                            ),
                                            onReply = { viewModel.setReply(it) },
                                            onMenu = { menuMessage = it },
                                            onRetry = { viewModel.retryMessage(it) },
                                            onReaction = { emoji -> viewModel.toggleReaction(message, emoji) },
                                            onSenderClick = { uid -> onOpenContact(uid) },
                                            onOpenAttachment = { m, a -> viewModel.openAttachment(m, a) },
                                            onSaveAttachment = { m, a -> viewModel.saveAttachment(m, a) },
                                            busyAttachmentKey = uiState.busyAttachmentKey,
                                            attachmentProgress = uiState.attachmentProgress
                                        )
                                        DropdownMenu(
                                            expanded = menuMessage?.id == message.id,
                                            onDismissRequest = { menuMessage = null },
                                            offset = menuOffset,
                                            modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)
                                        ) {
                                            // Быстрые реакции
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                listOf("👍", "❤️", "😂", "😮", "😢", "🔥").forEach { emoji ->
                                                    Text(
                                                        text = emoji,
                                                        style = MaterialTheme.typography.titleLarge,
                                                        modifier = Modifier
                                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                                            .clickable {
                                                                if (message.id != 0L) {
                                                                    viewModel.toggleReaction(message, emoji)
                                                                }
                                                                menuMessage = null
                                                            }
                                                            .padding(6.dp)
                                                    )
                                                }
                                            }
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text("Ответить") },
                                                leadingIcon = {
                                                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                                                },
                                                onClick = {
                                                    viewModel.setReply(message)
                                                    menuMessage = null
                                                }
                                            )
                                            if (message.userId == uiState.viewerId && message.text.isNotBlank()) {
                                                DropdownMenuItem(
                                                    text = { Text("Изменить") },
                                                    leadingIcon = {
                                                        Icon(Icons.Filled.Edit, contentDescription = null)
                                                    },
                                                    onClick = {
                                                        editMessage = message
                                                        menuMessage = null
                                                    }
                                                )
                                            }
                                            if (message.text.isNotBlank()) {
                                                DropdownMenuItem(
                                                    text = { Text("Копировать текст") },
                                                    leadingIcon = {
                                                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                                    },
                                                    onClick = {
                                                        try {
                                                            context.getSystemService(android.content.ClipboardManager::class.java)
                                                                ?.setPrimaryClip(
                                                                    android.content.ClipData.newPlainText("MAX", message.text)
                                                                )
                                                        } catch (e: Exception) {
                                                            android.util.Log.e("ChatScreen", "clipboard failed", e)
                                                        }
                                                        menuMessage = null
                                                    }
                                                )
                                            }
                                            val downloadable = message.attachments.firstOrNull {
                                                it.isFile || it.isVideo || it.isAudio || it.isPhoto
                                            }
                                            if (downloadable != null) {
                                                DropdownMenuItem(
                                                    text = { Text("Скачать в «Загрузки»") },
                                                    leadingIcon = {
                                                        Icon(Icons.Filled.Download, contentDescription = null)
                                                    },
                                                    onClick = {
                                                        viewModel.saveAttachment(message, downloadable)
                                                        menuMessage = null
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("Переслать") },
                                                leadingIcon = {
                                                    Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null)
                                                },
                                                onClick = {
                                                    forwardMessage = message
                                                    menuMessage = null
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Filled.Delete,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                },
                                                onClick = {
                                                    deleteMessage = message
                                                    menuMessage = null
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }

    // Delete confirm: у себя always, у всех only for own messages
    val toDelete = deleteMessage
    if (toDelete != null) {
        val mine = toDelete.userId == uiState.viewerId
        var forAll by remember(toDelete.id) { mutableStateOf(mine) }
        AlertDialog(
            onDismissRequest = { deleteMessage = null },
            title = { Text("Удалить сообщение?") },
            text = {
                if (mine) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { forAll = false }
                        ) {
                            RadioButton(selected = !forAll, onClick = { forAll = false })
                            Text("Удалить у меня")
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { forAll = true }
                        ) {
                            RadioButton(selected = forAll, onClick = { forAll = true })
                            Text("Удалить у всех")
                        }
                    }
                } else {
                    Text("Сообщение будет удалено только у вас.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMessage(toDelete, forAll)
                        deleteMessage = null
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteMessage = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Forward target picker
    if (forwardMessage != null) {
        ForwardSheet(
            onDismiss = { forwardMessage = null },
            onPickChat = { chatId ->
                forwardMessage?.let { viewModel.forwardMessage(it, chatId) }
                forwardMessage = null
            }
        )
    }

    // Edit own message (opcode 67)
    val toEdit = editMessage
    if (toEdit != null) {
        var editText by remember(toEdit.id) { mutableStateOf(toEdit.text) }
        AlertDialog(
            onDismissRequest = { editMessage = null },
            title = { Text("Изменить сообщение") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.editMessage(toEdit, editText.trim())
                        editMessage = null
                    },
                    enabled = editText.isNotBlank()
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { editMessage = null }) { Text("Отмена") }
            }
        )
    }
}

sealed interface TimelineItem {
    data class Day(val dayKey: String, val label: String) : TimelineItem
    data object Unread : TimelineItem
    data class Msg(
        val message: com.freemax.api.model.Message,
        /** Первое сообщение серии: над пузырём показываем имя автора. */
        val showHeader: Boolean = true,
        /** Последнее сообщение серии: рядом с пузырём показываем аватар. */
        val showAvatar: Boolean = true
    ) : TimelineItem
}

private const val GROUP_WINDOW_MS = 5 * 60_000L

private fun isSameGroup(
    first: com.freemax.api.model.Message?,
    second: com.freemax.api.model.Message?
): Boolean {
    if (first == null || second == null) return false
    if (first.isService || second.isService) return false
    if (first.userId != second.userId) return false
    val gap = second.time - first.time
    if (gap !in 0..GROUP_WINDOW_MS) return false
    if (first.time == 0L || second.time == 0L) return true
    return dayKey(first.time) == dayKey(second.time)
}

private fun buildTimeline(
    messages: List<com.freemax.api.model.Message>,
    firstUnreadId: Long?
): List<TimelineItem> {
    if (messages.isEmpty()) return emptyList()
    val out = ArrayList<TimelineItem>(messages.size + 4)
    var currentDay: String? = null
    for ((index, m) in messages.withIndex()) {
        if (m.time != 0L) {
            val key = dayKey(m.time)
            if (key != currentDay) {
                currentDay = key
                out.add(TimelineItem.Day(key, dayLabel(m.time)))
            }
        }
        if (firstUnreadId != null && m.id == firstUnreadId) {
            out.add(TimelineItem.Unread)
        }
        out.add(
            TimelineItem.Msg(
                message = m,
                showHeader = !isSameGroup(messages.getOrNull(index - 1), m),
                showAvatar = !isSameGroup(m, messages.getOrNull(index + 1))
            )
        )
    }
    return out
}

private fun dayKey(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    return "%04d-%02d-%02d".format(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH),
        cal.get(java.util.Calendar.DAY_OF_MONTH)
    )
}

private fun dayLabel(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance()
    val today = java.util.Calendar.getInstance()
    cal.timeInMillis = timestamp
    if (isSameDay(cal, today)) return "Сегодня"
    today.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (isSameDay(cal, today)) return "Вчера"
    val fmt = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("ru"))
    val s = fmt.format(java.util.Date(timestamp))
    return s.replaceFirstChar { it.uppercase() }
}

private fun isSameDay(a: java.util.Calendar, b: java.util.Calendar): Boolean {
    return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
        a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
}
