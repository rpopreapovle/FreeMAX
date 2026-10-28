package com.freemax.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freemax.app.data.repository.SearchEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onOpenContact: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showJoinDialog by remember { mutableStateOf(false) }
    var joinLink by remember { mutableStateOf("") }
    var joinStatus by remember { mutableStateOf<String?>(null) }

    // Диалог «вступить по ссылке»
    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false; joinStatus = null },
            title = { Text("Вступить по ссылке") },
            text = {
                Column {
                    OutlinedTextField(
                        value = joinLink,
                        onValueChange = { joinLink = it },
                        placeholder = { Text("https://max.ru/join/…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    joinStatus?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.joinByLink(joinLink)
                        joinStatus = "Запрос отправлен — чат появится в списке"
                    },
                    enabled = joinLink.isNotBlank()
                ) {
                    Text("Вступить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false; joinStatus = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets,
                title = {
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = { viewModel.onQueryChange(it) },
                        placeholder = { Text("Люди, чаты, каналы, сообщения") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (uiState.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "Очистить"
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.query.isBlank() && uiState.history.isNotEmpty() -> {
                // История поиска (локально) + недавние чаты
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "История поиска",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearHistory() }) {
                                Text("Очистить")
                            }
                        }
                    }
                    items(uiState.history, key = { "h$it" }) { q ->
                        ListItem(
                            headlineContent = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                            },
                            modifier = Modifier.clickable { viewModel.onQueryChange(q) }
                        )
                    }
                    if (uiState.results.isNotEmpty()) {
                        item {
                            Text(
                                text = "Ваши чаты",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(
                            uiState.results.take(30),
                            key = { entry -> "h" + entryKey(entry) }
                        ) { entry ->
                            SearchResultItem(entry, onOpenChat, onOpenContact) {
                                viewModel.rememberQuery(uiState.query)
                            }
                        }
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Вступить по ссылке") },
                            leadingContent = {
                                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                            },
                            modifier = Modifier.clickable { showJoinDialog = true }
                        )
                    }
                }
            }
            uiState.isSearching && uiState.results.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.results.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (uiState.query.isBlank()) "Начните вводить для поиска" else "Ничего не найдено",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(uiState.results, key = { entryKey(it) }) { entry ->
                        SearchResultItem(entry, onOpenChat, onOpenContact) {
                            viewModel.rememberQuery(uiState.query)
                        }
                    }
                    if (uiState.isSearching) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Вступить по ссылке") },
                            leadingContent = {
                                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                            },
                            modifier = Modifier.clickable { showJoinDialog = true }
                        )
                    }
                }
            }
        }
    }
}

private fun entryKey(entry: SearchEntry): String = when (entry) {
    is SearchEntry.ChannelEntry -> "c${entry.chat.id}"
    is SearchEntry.PersonEntry -> "p${entry.contact.id}"
    is SearchEntry.LocalChatEntry -> "l${entry.chat.id}"
    is SearchEntry.MessageEntry -> "m${entry.chatId}:${entry.messageId}"
}

@Composable
private fun SearchResultItem(
    entry: SearchEntry,
    onOpenChat: (Long) -> Unit,
    onOpenContact: (Long) -> Unit,
    onRemember: () -> Unit
) {
    when (entry) {
        is SearchEntry.LocalChatEntry -> {
            val chat = entry.chat
            ListItem(
                leadingContent = {
                    com.freemax.app.ui.chats.ChatAvatar(
                        title = chat.title,
                        iconUrl = chat.iconUrl,
                        size = 44.dp
                    )
                },
                headlineContent = {
                    Text(
                        chat.title.ifBlank { "Без названия" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Text(
                        "Ваш чат",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.clickable { onRemember(); onOpenChat(chat.id) }
            )
        }
        is SearchEntry.ChannelEntry -> {
            val chat = entry.chat
            ListItem(
                leadingContent = {
                    com.freemax.app.ui.chats.ChatAvatar(
                        title = chat.title,
                        iconUrl = chat.iconUrl,
                        size = 44.dp
                    )
                },
                headlineContent = {
                    Text(
                        chat.title.ifBlank { "Без названия" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Text(
                        entry.description.ifBlank { "Канал" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.clickable { onRemember(); onOpenChat(chat.id) }
            )
        }
        is SearchEntry.PersonEntry -> {
            val c = entry.contact
            ListItem(
                leadingContent = {
                    com.freemax.app.ui.chats.ChatAvatar(
                        title = c.name,
                        iconUrl = c.photoUrl,
                        size = 44.dp
                    )
                },
                headlineContent = {
                    Text(
                        c.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = c.phone?.let { { Text(it) } },
                modifier = Modifier.clickable { onRemember(); onOpenContact(c.id) }
            )
        }
        is SearchEntry.MessageEntry -> {
            ListItem(
                leadingContent = {
                    com.freemax.app.ui.chats.ChatAvatar(
                        title = entry.chatTitle,
                        iconUrl = null,
                        size = 44.dp
                    )
                },
                headlineContent = {
                    Text(
                        entry.text.ifBlank { "Вложение" },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    val time = if (entry.time > 0) {
                        java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru"))
                            .format(java.util.Date(entry.time))
                    } else ""
                    Text(
                        text = listOfNotNull(
                            entry.chatTitle.takeIf { it.isNotBlank() },
                            entry.senderName,
                            time.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                modifier = Modifier.clickable {
                    onRemember()
                    // 0 — валидный чат «Избранное»; «Сообщение» без чата не открываем.
                    if (entry.chatId != 0L || entry.chatTitle != "Сообщение") onOpenChat(entry.chatId)
                }
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}
