package com.freemax.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Поиск сообщений внутри одного чата (opcode 73).
 * Тап по результату открывает чат (позиционирование по сообщению — позже).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSearchScreen(
    chatId: Long,
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        viewModel.onChatQueryChange(chatId, query)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets,
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Поиск в чате") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(imageVector = Icons.Filled.Clear, contentDescription = "Очистить")
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
            uiState.isChatSearching && uiState.chatResults.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            query.trim().length < 2 -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Введите минимум 2 символа",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            uiState.chatResults.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    item {
                        Text(
                            text = "Найдено: ${uiState.chatResults.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    items(uiState.chatResults, key = { "m${it.messageId}" }) { m ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = m.text.ifBlank { "Вложение" },
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                val time = if (m.time > 0) {
                                    java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru"))
                                        .format(java.util.Date(m.time))
                                } else ""
                                Text(
                                    text = listOfNotNull(m.senderName, time.takeIf { it.isNotBlank() })
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.rememberQuery(query)
                                onOpenChat(chatId)
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}
