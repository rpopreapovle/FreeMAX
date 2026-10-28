package com.freemax.app.ui.auth

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

private const val TAG = "PasteToken"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteTokenScreen(
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: WebViewAuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var jsonInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    var viewerIdInput by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("json") } // "json" or "manual"
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    LaunchedEffect(uiState.isDone) {
        if (uiState.isDone) onLoginSuccess()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Вход по токену") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Извлеките токен из браузера",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "1. Откройте web.max.ru в браузере\n" +
                        "2. Войдите в аккаунт\n" +
                        "3. Откройте консоль разработчика (F12)\n" +
                        "4. Вставьте localStorage.getItem('__oneme_auth') и нажмите Enter\n" +
                        "5. Скопируйте результат в поле ниже",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Mode selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = mode == "json",
                    onClick = { mode = "json" },
                    label = { Text("JSON") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = mode == "manual",
                    onClick = { mode = "manual" },
                    label = { Text("Вручную") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (mode == "json") {
                OutlinedTextField(
                    value = jsonInput,
                    onValueChange = { jsonInput = it },
                    label = { Text("Вставьте JSON из скрипта") },
                    placeholder = { Text("{\"token\":\"...\",\"viewerId\":\"...\"}") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                )
            } else {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = viewerIdInput,
                    onValueChange = { viewerIdInput = it },
                    label = { Text("Viewer ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    val token: String
                    val viewerId: Long

                    if (mode == "json") {
                        try {
                            val json = org.json.JSONObject(jsonInput.trim())
                            token = json.getString("token")
                            viewerId = json.getString("viewerId").toLong()
                        } catch (e: Exception) {
                            Log.e(TAG, "JSON parse error", e)
                            viewModel.onError("Неверный формат JSON: ${e.message}")
                            return@Button
                        }
                    } else {
                        token = tokenInput.trim()
                        val vid = viewerIdInput.trim().toLongOrNull()
                        if (vid == null || vid <= 0) {
                            viewModel.onError("Неверный Viewer ID")
                            return@Button
                        }
                        viewerId = vid
                    }

                    if (token.isBlank()) {
                        viewModel.onError("Введите токен")
                        return@Button
                    }

                    Log.i(TAG, "Token received for viewerId=$viewerId")
                    viewModel.onAuthReceived(token, viewerId)
                },
                enabled = !uiState.isLoading && (
                    (mode == "json" && jsonInput.isNotBlank()) ||
                    (mode == "manual" && tokenInput.isNotBlank() && viewerIdInput.isNotBlank())
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Войти")
                }
            }
        }
    }
}
