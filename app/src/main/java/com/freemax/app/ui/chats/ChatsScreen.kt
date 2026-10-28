package com.freemax.app.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    onChatClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    onNewChatClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onAccountsClick: () -> Unit = {},
    viewModel: ChatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var menuChatId by remember { mutableStateOf<Long?>(null) }
    var deleteChatId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Text(
                        text = "Чаты",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onProfileClick) {
                        ChatAvatar(
                            title = uiState.selfName,
                            iconUrl = uiState.selfAvatarUrl,
                            size = 32.dp
                        )
                    }
                },
                actions = {
                    // Быстрый переключатель аккаунтов: инициалы + непрочитанные
                    BadgedBox(
                        badge = {
                            if (uiState.unreadTotal > 0) {
                                Badge {
                                    Text(
                                        uiState.unreadTotal.coerceAtMost(99).toString()
                                    )
                                }
                            }
                        }
                    ) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(32.dp)
                                .clickable(onClick = onAccountsClick)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = uiState.accountInitials,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Поиск"
                        )
                    }
                    IconButton(onClick = { viewModel.loadChats() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Обновить"
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                id = com.freemax.app.R.drawable.ic_settings_tabler
                            ),
                            contentDescription = "Настройки"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChatClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Новый чат"
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Офлайн-баннер: понятный текст, список при этом остаётся из кэша
            if (!uiState.online) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Нет сети — показаны сохранённые чаты. Отправка возобновится автоматически.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
            when {
                uiState.isLoading && uiState.chats.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.chats.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (uiState.online) "Пока нет чатов" else "Нет сети",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (uiState.online) {
                                "Начните переписку: найдите человека по имени или номеру телефона."
                            } else {
                                "Подключитесь к интернету — чаты подгрузятся сами."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        if (uiState.online) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = onNewChatClick) {
                                Text("Найти собеседника")
                            }
                        }
                    }
                }
                else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    itemsIndexed(
                        items = uiState.chats,
                        key = { _, chat -> chat.id }
                    ) { _, chat ->
                        val isPinned = viewModel.isPinned(chat.id)
                        Box {
                            ChatListItem(
                                chat = chat,
                                isPinned = isPinned,
                                isMuted = viewModel.isMuted(chat.id),
                                onClick = { onChatClick(chat.id) },
                                modifier = Modifier.combinedClickable(
                                    onClick = { onChatClick(chat.id) },
                                    onLongClick = { menuChatId = chat.id }
                                )
                            )
                            DropdownMenu(
                                expanded = menuChatId == chat.id,
                                onDismissRequest = { menuChatId = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (isPinned) "Открепить" else "Закрепить") },
                                    onClick = {
                                        viewModel.togglePin(chat.id)
                                        menuChatId = null
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (viewModel.isMuted(chat.id)) "Включить звук" else "Без звука") },
                                    onClick = {
                                        viewModel.toggleMute(chat.id)
                                        menuChatId = null
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (viewModel.isMuted(chat.id)) Icons.Filled.Notifications else Icons.Filled.NotificationsOff,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Удалить чат",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        deleteChatId = chat.id
                                        menuChatId = null
                                    }
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 81.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                }
            }
        }
    }

    val toDelete = deleteChatId
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteChatId = null },
            title = { Text("Удалить чат?") },
            text = { Text("Переписка пропадёт из списка. У собеседника она останется.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChat(toDelete)
                        deleteChatId = null
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteChatId = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}
