package com.freemax.app.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.freemax.app.ui.theme.avatarBackground
import com.freemax.app.ui.theme.avatarForeground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets,
                title = { Text(title) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
private fun Avatar(
    name: String,
    photoUrl: String?,
    size: Int = 96,
    onClick: (() -> Unit)? = null,
    online: Boolean = false,
    colorKey: Long = 0L
) {
    Box(modifier = Modifier.size(size.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(avatarBackground(colorKey, name))
                .let { if (onClick != null) it.clickable(onClick = onClick) else it },
            contentAlignment = Alignment.Center
        ) {
            if (!photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.displaySmall,
                    color = avatarForeground(colorKey, name)
                )
            }
        }
        if (online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size * 0.24f).dp)
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

@Composable
fun ContactProfileScreen(
    contactId: Long,
    onBack: () -> Unit,
    onOpenDialog: (Long) -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val contact by viewModel.contact.collectAsState()
    val presence by viewModel.presence.collectAsState()
    val online by viewModel.online.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(contactId) {
        viewModel.loadContact(contactId)
    }

    ProfileScaffold(title = "Профиль", onBack = onBack) {
        Spacer(modifier = Modifier.height(16.dp))
        Avatar(
            name = contact?.name ?: "…",
            photoUrl = contact?.photoUrl,
            colorKey = contactId
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = contact?.name ?: "Загрузка…",
            style = MaterialTheme.typography.headlineSmall
        )
        // Онлайн / последний визит
        val statusText = when {
            contactId in online -> "онлайн"
            (presence[contactId] ?: 0L) > 0L -> "был(а) в сети ${formatPresence(presence[contactId]!!)}"
            else -> null
        }
        if (statusText != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                color = if (contactId in online) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        contact?.about?.let { about ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        contact?.phone?.let { phone ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = phone,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    try {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                    } catch (_: Exception) {}
                }
            )
        }
        contact?.description?.takeIf { it.isNotBlank() }?.let { desc ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "О себе",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        val dialog = remember(contact) {
            viewModel.findDialogWith(contactId)
        }
        if (dialog != null) {
            Button(onClick = { onOpenDialog(dialog.id) }) {
                Text("Открыть диалог")
            }
        } else {
            Text(
                text = "Диалога пока нет. Начните переписку с другого клиента " +
                    "(по номеру телефона) — после первого сообщения чат появится здесь.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
fun OwnProfileScreen(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val self by viewModel.selfProfile.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val picked by viewModel.pickedAvatar.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var firstName by remember(self?.contactId) { mutableStateOf(self?.firstName ?: "") }
    var lastName by remember(self?.contactId) { mutableStateOf(self?.lastName ?: "") }

    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.pickAvatar(uri)
    }

    LaunchedEffect(self?.contactId) {
        viewModel.clearAvatar()
        viewModel.clearSaveError()
    }

    ProfileScaffold(title = "Мой профиль", onBack = onBack) {
        Spacer(modifier = Modifier.height(16.dp))
        Avatar(
            name = self?.name ?: "…",
            photoUrl = picked?.previewUrl ?: self?.photoUrl,
            onClick = if (editing) ({ pickImage.launch("image/*") }) else null,
            colorKey = self?.contactId ?: 0L
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (!editing) {
            Text(
                text = self?.name ?: "Загрузка…",
                style = MaterialTheme.typography.headlineSmall
            )
            self?.phone?.let { phone ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = phone,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            self?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "О себе",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Это вы",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = {
                firstName = self?.firstName ?: ""
                lastName = self?.lastName ?: ""
                editing = true
            }) {
                Text("Редактировать")
            }
        } else {
            OutlinedTextField(
                value = firstName,
                onValueChange = { firstName = it },
                label = { Text("Имя") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = lastName,
                onValueChange = { lastName = it },
                label = { Text("Фамилия") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Тапните по фото, чтобы сменить аватар",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            saveError?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = err, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.saveSelfProfile(
                            firstName, lastName,
                            picked?.file, picked?.previewUrl
                        )
                        editing = false
                    },
                    enabled = !saving
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Сохранить")
                    }
                }
                OutlinedButton(
                    onClick = {
                        editing = false
                        viewModel.clearAvatar()
                        viewModel.clearSaveError()
                    },
                    enabled = !saving
                ) {
                    Text("Отмена")
                }
            }
        }
    }
}

private fun formatPresence(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L -> "только что"
        diff < 3_600_000L -> "${diff / 60_000L} мин назад"
        diff < 24 * 3_600_000L ->
            java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru")).format(java.util.Date(ts))
        else ->
            java.text.SimpleDateFormat("d MMM", java.util.Locale("ru")).format(java.util.Date(ts))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    chatId: Long,
    onBack: () -> Unit,
    onMemberClick: (contactId: Long, isSelf: Boolean) -> Unit = { _, _ -> },
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsState()
    // Title straight from the repository — no URL encoding, no "+" instead of spaces
    val chatTitle = remember(chats, chatId) {
        chats.find { it.id == chatId || it.cid == chatId }?.title.orEmpty()
    }
    val context = LocalContext.current
    val members by viewModel.members.collectAsState()
    val presence by viewModel.presence.collectAsState()
    val online by viewModel.online.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val picked by viewModel.pickedAvatar.collectAsState()
    val currentIcon by viewModel.chatIcon.collectAsState()
    val canEdit = remember(chatId) { viewModel.canEditChat(chatId) }
    val chat = remember(chats, chatId) {
        chats.find { it.id == chatId || it.cid == chatId }
    }
    val inviteLink = chat?.link
    var editing by remember { mutableStateOf(false) }
    var title by remember(chatId) { mutableStateOf(chatTitle) }
    var memberQuery by remember(chatId) { mutableStateOf("") }
    val visibleMembers = remember(members, memberQuery) {
        val q = memberQuery.trim()
        if (q.isBlank()) members
        else members.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.phone?.contains(q) == true ||
                it.about?.contains(q, ignoreCase = true) == true
        }
    }

    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.pickAvatar(uri)
    }

    LaunchedEffect(chatId) {
        viewModel.loadMembers(chatId)
        viewModel.loadChatIcon(chatId)
        viewModel.refreshGroupInfo(chatId)
        viewModel.clearAvatar()
        viewModel.clearSaveError()
    }

    // Присутствие и подробные карточки участников — по возможностям протокола:
    // CONTACTS_PRESENCE (35) и LOAD_CONTACT_INFO (198).
    LaunchedEffect(members.map { it.contactId }) {
        if (members.isNotEmpty()) {
            val ids = members.map { it.contactId }
            viewModel.requestMembersPresence(ids)
            viewModel.enrichMembers(ids)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets,
                title = { Text(chatTitle.ifBlank { "Группа" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.loadMembers(chatId)
                        viewModel.refreshGroupInfo(chatId)
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Обновить данные группы"
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (members.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Avatar(
                            name = chatTitle,
                            photoUrl = picked?.previewUrl ?: currentIcon,
                            size = 96,
                            onClick = if (editing && canEdit) ({ pickImage.launch("image/*") }) else null,
                            colorKey = chatId
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (!editing) {
                            Text(
                                text = chatTitle.ifBlank { "Группа" },
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = buildString {
                                    append("Участников: ${members.size}")
                                    val onlineCount = members.count { it.contactId in online }
                                    if (onlineCount > 0) append(" · онлайн: $onlineCount")
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (canEdit) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(onClick = {
                                    title = chatTitle
                                    editing = true
                                }) {
                                    Text("Редактировать")
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("Название") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            )
                            saveError?.let { err ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = err, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.saveGroup(
                                            chatId, title,
                                            picked?.file, picked?.previewUrl
                                        )
                                        editing = false
                                    },
                                    enabled = !saving
                                ) {
                                    if (saving) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text("Сохранить")
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        editing = false
                                        viewModel.clearAvatar()
                                        viewModel.clearSaveError()
                                    },
                                    enabled = !saving
                                ) {
                                    Text("Отмена")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                // Ссылка-приглашение: показываем и копируем
                if (!inviteLink.isNullOrBlank()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Ссылка-приглашение",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = inviteLink,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    try {
                                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                        cm?.setPrimaryClip(
                                            android.content.ClipData.newPlainText("MAX invite", inviteLink)
                                        )
                                        android.widget.Toast.makeText(
                                            context, "Ссылка скопирована", android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } catch (_: Exception) {}
                                }) {
                                    Text("Копировать")
                                }
                            }
                            // Поделиться ссылкой
                            TextButton(onClick = {
                                try {
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, inviteLink)
                                    }
                                    context.startActivity(
                                        android.content.Intent.createChooser(send, "Пригласить в чат")
                                    )
                                } catch (_: Exception) {}
                            }) {
                                Text("Поделиться")
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = "Участники: ${members.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (members.size > 6) {
                    item {
                        OutlinedTextField(
                            value = memberQuery,
                            onValueChange = { memberQuery = it },
                            placeholder = { Text("Поиск участника") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null
                                )
                            },
                            singleLine = true,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                if (visibleMembers.isEmpty() && memberQuery.isNotBlank()) {
                    item {
                        Text(
                            text = "Никого не найдено",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                        )
                    }
                }
                items(visibleMembers, key = { it.contactId }) { m ->
                    val isOnline = m.contactId in online
                    val seen = presence[m.contactId] ?: 0L
                    val statusText = when {
                        m.isSelf -> "Вы"
                        isOnline -> "онлайн"
                        seen > 0L -> "был(а) в сети ${formatPresence(seen)}"
                        else -> null
                    }
                    val about = m.about ?: m.description
                    ListItem(
                        modifier = Modifier.clickable {
                            onMemberClick(m.contactId, m.isSelf)
                        },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (m.isSelf) "${m.name} (вы)" else m.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (m.role == "OWNER" || m.role == "ADMIN") {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (m.role == "OWNER") "владелец" else "админ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        },
                        supportingContent = {
                            Column {
                                if (statusText != null) {
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isOnline) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!about.isNullOrBlank()) {
                                    Text(
                                        text = about,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (!m.phone.isNullOrBlank()) {
                                    Text(
                                        text = m.phone,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        leadingContent = {
                            Avatar(
                                name = m.name,
                                photoUrl = m.photoUrl,
                                size = 44,
                                online = isOnline,
                                colorKey = m.contactId
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Открыть профиль",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
                }
            }
        }
    }
}
