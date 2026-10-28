package com.freemax.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freemax.app.core.storage.SecureStorage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onProfileClick: () -> Unit = {},
    onAccountsClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showLogoutDialog by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets,
                title = { Text("Настройки") },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                val self by viewModel.selfProfile.collectAsState(initial = null)
                ListItem(
                    headlineContent = { Text(self?.name ?: "Мой профиль") },
                    supportingContent = { Text(self?.phone ?: "Имя, телефон, фото") },
                    leadingContent = {
                        com.freemax.app.ui.chats.ChatAvatar(
                            title = self?.name.orEmpty(),
                            iconUrl = self?.photoUrl,
                            size = 48.dp
                        )
                    },
                    modifier = Modifier.clickable { onProfileClick() }
                )
            }
            if (uiState.sttAvailable) {
                item {
                    SettingsSection(title = "Голосовые сообщения")
                }
                item {
                    ListItem(
                        headlineContent = { Text("Распознавание речи") },
                        supportingContent = { Text("Текст под каждым голосовым") },
                        trailingContent = {
                            Switch(
                                checked = uiState.sttEnabled,
                                onCheckedChange = { viewModel.setSttEnabled(it) }
                            )
                        }
                    )
                }
            }

            item {
                SettingsSection(title = "Оформление")
            }
            item {
                ThemeOption(
                    title = "Системная",
                    subtitle = "Как в системе",
                    selected = uiState.themeMode == SecureStorage.THEME_SYSTEM,
                    onClick = { viewModel.setThemeMode(SecureStorage.THEME_SYSTEM) }
                )
                ThemeOption(
                    title = "Светлая",
                    subtitle = "Всегда светлая тема",
                    selected = uiState.themeMode == SecureStorage.THEME_LIGHT,
                    onClick = { viewModel.setThemeMode(SecureStorage.THEME_LIGHT) }
                )
                ThemeOption(
                    title = "Тёмная",
                    subtitle = "Всегда тёмная тема",
                    selected = uiState.themeMode == SecureStorage.THEME_DARK,
                    onClick = { viewModel.setThemeMode(SecureStorage.THEME_DARK) }
                )
            }

            item {
                SettingsSection(title = "Аккаунт")
            }
            item {
                ListItem(
                    headlineContent = { Text("Аккаунты") },
                    supportingContent = { Text("Переключение, добавление, токены") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.ManageAccounts,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.clickable { onAccountsClick() }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(uiState.phone ?: "Неизвестный номер") },
                    supportingContent = { Text("ID: ${uiState.userId}") }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Очистить кэш изображений") },
                    supportingContent = {
                        Text(if (uiState.cacheCleared) "Кэш очищен" else "Удаляет загруженные фото и стикеры")
                    },
                    modifier = Modifier.clickable { viewModel.clearCoilDiskCache(context) }
                )
            }

            item {
                SettingsSection(title = "Данные")
            }
            item {
                ListItem(
                    headlineContent = { Text("Очистить весь кэш") },
                    supportingContent = {
                        Text(
                            if (uiState.cacheCleared) "Кэш очищен"
                            else "Сообщения, чаты и черновики${if (uiState.cacheSizeText.isNotBlank()) " · ${uiState.cacheSizeText}" else ""}"
                        )
                    },
                    modifier = Modifier.clickable { viewModel.clearFullCache(context) }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Автоочистка кэша") },
                    supportingContent = {
                        Text(
                            when (uiState.autoClearDays) {
                                0 -> "Выключена"
                                7 -> "Удалять чаты без активности 7 дней"
                                30 -> "Удалять чаты без активности 30 дней"
                                else -> "Удалять чаты без активности ${uiState.autoClearDays} дн."
                            }
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = {
                            val next = when (uiState.autoClearDays) {
                                0 -> 7
                                7 -> 30
                                30 -> 90
                                else -> 0
                            }
                            viewModel.setAutoClearDays(next)
                        }) {
                            Text(
                                when (uiState.autoClearDays) {
                                    0 -> "Выкл"
                                    7 -> "7 дн"
                                    30 -> "30 дн"
                                    else -> "90 дн"
                                }
                            )
                        }
                    }
                )
            }

            item {
                SettingsSection(title = "Безопасность")
            }
            item {
                var showSetPin by remember { mutableStateOf(false) }
                var showDisablePin by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = {
                        Text(if (uiState.appLockEnabled) "Блокировка приложения включена" else "Блокировка приложения")
                    },
                    supportingContent = {
                        Text(
                            if (uiState.appLockEnabled) "Спросить PIN при запуске"
                            else "PIN-код при запуске приложения"
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = uiState.appLockEnabled,
                            onCheckedChange = { on ->
                                if (on) showSetPin = true else showDisablePin = true
                            }
                        )
                    }
                )
                if (showSetPin) {
                    PinDialog(
                        title = "Новый PIN",
                        onDismiss = { showSetPin = false },
                        onConfirm = { pin ->
                            viewModel.setAppLockPin(pin)
                            showSetPin = false
                        }
                    )
                }
                if (showDisablePin) {
                    PinDialog(
                        title = "Введите текущий PIN",
                        onDismiss = { showDisablePin = false },
                        onConfirm = { pin ->
                            if (viewModel.checkPin(pin)) {
                                viewModel.disableAppLock()
                                showDisablePin = false
                            }
                        },
                        validate = { viewModel.checkPin(it) }
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = {
                        Text("Выйти из аккаунта", color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable { showLogoutDialog = true }
                )
            }

            item {
                SettingsSection(title = "О приложении")
            }
            item {
                ListItem(
                    headlineContent = { Text("FreeMAX") },
                    supportingContent = { Text("Версия 0.1.0 (тест) • FOSS-клиент MAX") }
                )
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Выйти?") },
            text = { Text("Нужно будет войти заново через сайт.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                        onLoggedOut()
                    }
                ) {
                    Text("Выйти", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** Диалог ввода PIN: 4–8 цифр, с проверкой при отключении. */
@Composable
private fun PinDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    validate: ((String) -> Boolean)? = null
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v ->
                        pin = v.filter { it.isDigit() }.take(8)
                        error = null
                    },
                    singleLine = true,
                    placeholder = { Text("PIN (4–8 цифр)") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        pin.length < 4 -> error = "Минимум 4 цифры"
                        validate != null && !validate(pin) -> error = "Неверный PIN"
                        else -> onConfirm(pin)
                    }
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun ThemeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            RadioButton(selected = selected, onClick = onClick)
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
