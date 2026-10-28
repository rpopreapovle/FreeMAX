package com.freemax.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freemax.app.core.storage.MaxAccount

/**
 * Аккаунты FreeMAX: список сохранённых сессий, переключение, добавление,
 * редактирование токена (в том числе просмотр и копирование текущего).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    viewModel: AccountsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var editing by remember { mutableStateOf<MaxAccount?>(null) }
    var renaming by remember { mutableStateOf<MaxAccount?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showTokenFor by remember { mutableStateOf<MaxAccount?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets,
                title = { Text("Аккаунты") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить аккаунт")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (uiState.accounts.isEmpty()) {
                item {
                    Text(
                        "Аккаунтов нет. Нажми + и вставь JSON из браузера:\n" +
                            "localStorage.getItem('__oneme_auth')",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(uiState.accounts.size) { index ->
                val account = uiState.accounts[index]
                val isActive = account.viewerId == uiState.activeId
                ListItem(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                account.name.ifBlank { "ID ${account.viewerId}" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isActive) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Активный",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    supportingContent = {
                        Column {
                            Text("ID: ${account.viewerId}")
                            if (account.phone.isNotBlank()) Text(account.phone)
                            Text(
                                "Токен: ${account.token.take(10)}…${account.token.takeLast(6)}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { showTokenFor = account }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Показать и скопировать токен")
                            }
                            IconButton(onClick = { editing = account }) {
                                Icon(Icons.Default.VpnKey, contentDescription = "Изменить токен")
                            }
                            IconButton(onClick = { renaming = account }) {
                                Icon(Icons.Default.DriveFileRenameOutline, contentDescription = "Переименовать")
                            }
                            IconButton(onClick = { viewModel.remove(account.viewerId) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Удалить",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        if (!isActive) viewModel.switchAccount(account.viewerId)
                        else onBack()
                    }
                )
                HorizontalDivider()
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Тап по аккаунту — переключиться. Карандаш — изменить токен, " +
                        "иконка копирования — посмотреть и скопировать, корзина — удалить.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showAdd) {
        TokenDialog(
            title = "Добавить аккаунт",
            initialToken = "",
            initialId = "",
            onDismiss = { showAdd = false },
            onConfirm = { raw, token, id ->
                if (viewModel.addAccount(raw, token, id)) showAdd = false
            }
        )
    }

    editing?.let { account ->
        TokenDialog(
            title = "Токен аккаунта ${account.viewerId}",
            initialToken = account.token,
            initialId = account.viewerId.toString(),
            onDismiss = { editing = null },
            onConfirm = { _, token, _ ->
                viewModel.updateToken(account.viewerId, token.trim())
                editing = null
            }
        )
    }

    showTokenFor?.let { account ->
        AlertDialog(
            onDismissRequest = { showTokenFor = null },
            title = { Text("Токен ${account.viewerId}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        account.token,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, account.token)
                    showTokenFor = null
                }) { Text("Скопировать") }
            },
            dismissButton = { TextButton(onClick = { showTokenFor = null }) { Text("Закрыть") } }
        )
    }

    renaming?.let { account ->
        var name by remember { mutableStateOf(account.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Название аккаунта") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(account.viewerId, name)
                    renaming = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } }
        )
    }

    uiState.error?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissMessages() },
            title = { Text("Ошибка") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { viewModel.dismissMessages() }) { Text("Ок") } }
        )
    }
    uiState.info?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissMessages() },
            title = { Text("Готово") },
            text = { Text(info) },
            confirmButton = { TextButton(onClick = { viewModel.dismissMessages() }) { Text("Ок") } }
        )
    }
}

/** Диалог ввода токена: JSON из браузера или токен + ID вручную. */
@Composable
private fun TokenDialog(
    title: String,
    initialToken: String,
    initialId: String,
    onDismiss: () -> Unit,
    onConfirm: (rawJson: String, token: String, viewerId: Long) -> Unit
) {
    var raw by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(initialToken) }
    var viewerId by remember { mutableStateOf(initialId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    label = { Text("JSON из браузера (__oneme_auth)") },
                    placeholder = { Text("{\"token\":\"...\",\"viewerId\":123}") },
                    minLines = 3,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("…или вручную:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = false,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = viewerId,
                    onValueChange = { viewerId = it },
                    label = { Text("Viewer ID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(raw.trim(), token.trim(), viewerId.trim().toLongOrNull() ?: 0L)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("MAX token", text))
}
