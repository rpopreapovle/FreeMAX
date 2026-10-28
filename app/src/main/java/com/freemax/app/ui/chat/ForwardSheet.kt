package com.freemax.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freemax.app.ui.chats.ChatsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardSheet(
    onDismiss: () -> Unit,
    onPickChat: (Long) -> Unit,
    chatsViewModel: ChatsViewModel = hiltViewModel()
) {
    val uiState by chatsViewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Text(
            text = "Переслать в…",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(uiState.chats, key = { it.id }) { chat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickChat(chat.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.freemax.app.ui.chats.ChatAvatar(
                        title = chat.title,
                        iconUrl = chat.iconUrl,
                        size = 44.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = chat.title.ifBlank { "Без названия" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}
