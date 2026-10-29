package com.freemax.app.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freemax.app.AppLockScreen
import com.freemax.app.core.storage.SecureStorage
import com.freemax.app.ui.chats.ChatAvatar
import com.freemax.app.ui.theme.FreeMAXTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Приём системного «Поделиться» из других приложений.
 * Показывает выбор чата и отправляет текст и/или вложения.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject lateinit var secureStorage: SecureStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val payload = payloadFromIntent(intent)
        setContent {
            FreeMAXTheme {
                val lockEnabled by secureStorage.appLockFlow.collectAsState()
                var unlocked by remember { mutableStateOf(false) }
                if (lockEnabled && !unlocked) {
                    AppLockScreen(
                        check = { pin ->
                            runCatching { secureStorage.checkAppLockPin(pin) }
                                .getOrDefault(false)
                        },
                        onUnlocked = { unlocked = true }
                    )
                } else {
                    ShareScreen(
                        payload = payload,
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    private fun payloadFromIntent(intent: Intent?): SharePayload {
        if (intent == null) return SharePayload()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val body = when {
            text.isNotBlank() -> text
            subject.isNotBlank() -> subject
            else -> ""
        }
        val uris = mutableListOf<Uri>()
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uris += it }
            }
        }
        @Suppress("DEPRECATION")
        when (intent.action) {
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { uris += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            }
        }
        return SharePayload(text = body, uris = uris.distinct())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareScreen(
    payload: SharePayload,
    onClose: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val sent by viewModel.sent.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(payload) { viewModel.setPayload(payload) }
    LaunchedEffect(sent) { if (sent) onClose() }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets,
                title = { Text("Отправить в FreeMAX") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Закрыть"
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
                .padding(horizontal = 16.dp)
        ) {
            if (sending) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Отправляю…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (!payload.text.isNullOrBlank() || payload.uris.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Что отправить",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (payload.text.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = payload.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        payload.uris.forEach { uri ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "📎 ${displayName(context, uri)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            error?.let { message ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Куда отправить",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (chats.isEmpty()) {
                Text(
                    text = "Чаты ещё загружаются…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(chats, key = { it.id }) { chat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !sending && !sent) {
                                    viewModel.send(chat.id)
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ChatAvatar(
                                title = chat.title,
                                iconUrl = chat.iconUrl,
                                size = 44.dp,
                                isSaved = chat.isSavedMessages,
                                colorKey = chat.id
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = chat.title.ifBlank { "Без названия" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (chat.description.isNotBlank()) {
                                    Text(
                                        text = chat.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 60.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Файл"
    } catch (_: Exception) {
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Файл"
    }
}
