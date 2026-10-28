package com.freemax.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onNavigateToWebView: (() -> Unit)? = null,
    onNavigateToPasteToken: () -> Unit = {},
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.step) {
        if (uiState.step == AuthStep.Done) onLoginSuccess()
    }

    LaunchedEffect(Unit) {
        viewModel.connect()
    }

    // If already logged in, show loading and auto-navigate (no flash of login form)
    if (uiState.step == AuthStep.Done) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FreeMAX") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Вход в MAX",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = if (onNavigateToWebView != null) {
                    "Войдите через официальный сайт MAX"
                } else {
                    "Войдите, вставив токен из браузера: откройте web.max.ru, " +
                        "найдите токен в инструментах разработчика и вставьте его на следующем экране"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Button(
                onClick = onNavigateToWebView ?: onNavigateToPasteToken,
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(if (onNavigateToWebView != null) "Войти" else "Войти по токену")
                }
            }

            if (onNavigateToWebView != null) {
                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = onNavigateToPasteToken) {
                    Text(
                        "Не открывается? Вставить токен вручную",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
