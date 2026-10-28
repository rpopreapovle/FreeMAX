package com.freemax.app.ui.auth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

private const val TAG = "GeckoLogin"
private const val EXT_LOCATION = "resource://android/assets/maxauth/"
private const val EXT_ID = "maxauth@freemax.local"
private const val NATIVE_APP = "browser"
private const val LOGIN_URL = "https://web.max.ru"

@SuppressLint("SetJavaScriptEnabled", "WrongThread")
@Composable
fun GeckoLoginScreen(
    onAuthSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: WebViewAuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var engineError by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isDone) {
        if (uiState.isDone) onAuthSaved()
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val messageDelegate = remember {
        object : WebExtension.MessageDelegate {
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender
            ): GeckoResult<Any>? {
                try {
                    val json = message as? JSONObject ?: return null
                    if (json.optString("type") != "MAX_AUTH") return null
                    val auth = try {
                        JSONObject(json.optString("auth"))
                    } catch (_: Exception) {
                        return null
                    }
                    val token = auth.optString("token")
                    val viewerId = auth.optLong("viewerId", 0L)
                    Log.i(TAG, "Auth from extension for viewerId=$viewerId")
                    if (token.isNotBlank() && viewerId > 0) {
                        mainHandler.post {
                            viewModel.onAuthReceived(token, viewerId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onMessage failed: ${e.message}")
                }
                return null
            }
        }
    }

    var canGoBackState by remember { mutableStateOf(false) }

    val session = remember {
        GeckoSession().apply {
            setNavigationDelegate(object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean
                ) {
                    Log.i(TAG, "location: $url")
                }

                override fun onCanGoBack(
                    session: GeckoSession,
                    canGoBack: Boolean
                ) {
                    canGoBackState = canGoBack
                }
            })
            setProgressDelegate(object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    Log.i(TAG, "page started: $url")
                    isLoading = true
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    Log.i(TAG, "page stopped: success=$success")
                    isLoading = false
                }
            })
        }
    }

    BackHandler {
        if (canGoBackState) {
            try {
                session.goBack()
            } catch (_: Throwable) {
                onBack()
            }
        } else {
            onBack()
        }
    }

    LaunchedEffect(Unit) {
        try {
            val runtime = GeckoHolder.get(context)
            runtime.webExtensionController
                .ensureBuiltIn(EXT_LOCATION, EXT_ID)
                .accept({ ext ->
                    Log.i(TAG, "Extension installed, attaching delegate")
                    if (ext == null) {
                        Log.e(TAG, "ensureBuiltIn returned null extension")
                        mainHandler.post { engineError = "Расширение не установилось" }
                        return@accept
                    }
                    try {
                        session.webExtensionController.setMessageDelegate(
                            ext, messageDelegate, NATIVE_APP
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "setMessageDelegate failed: ${e.message}")
                        mainHandler.post { engineError = "Мост расширения: ${e.message}" }
                    }
                }, { e ->
                    Log.e(TAG, "ensureBuiltIn failed: ${e?.message}")
                    mainHandler.post { engineError = "Расширение: ${e?.message}" }
                })
            if (!session.isOpen) {
                try {
                    session.open(runtime)
                } catch (t: Throwable) {
                    Log.e(TAG, "session.open failed", t)
                    mainHandler.post { engineError = "Сессия: ${t.message}" }
                    return@LaunchedEffect
                }
            }
            try {
                session.loadUri(LOGIN_URL)
            } catch (t: Throwable) {
                Log.e(TAG, "loadUri failed", t)
                mainHandler.post { engineError = "Загрузка: ${t.message}" }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Gecko init failed", t)
            val msg = "Движок: ${t.message}"
            mainHandler.post { engineError = msg }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                session.close()
            } catch (_: Throwable) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // Runtime MUST exist before setSession (its static
                // accessibility init needs the app-shell context).
                try {
                    GeckoHolder.get(ctx)
                } catch (t: Throwable) {
                    Log.e(TAG, "Gecko runtime ensure failed", t)
                    mainHandler.post { engineError = "Движок: ${t.message}" }
                }
                GeckoView(ctx).apply {
                    try {
                        setSession(session)
                    } catch (t: Throwable) {
                        // Was: fatal ExceptionInInitializerError in
                        // SessionAccessibility (null app-shell context).
                        Log.e(TAG, "setSession failed", t)
                        mainHandler.post { engineError = "Экран: ${t.message}" }
                    }
                }
            }
        )

        if (isLoading || uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 96.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "FreeMAX",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator()
                }
            }
        }

        engineError?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}
