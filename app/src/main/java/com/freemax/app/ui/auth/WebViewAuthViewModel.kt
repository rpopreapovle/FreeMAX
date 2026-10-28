package com.freemax.app.ui.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freemax.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WebViewAuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isDone: Boolean = false
)

@HiltViewModel
class WebViewAuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    companion object {
        private const val TAG = "WebViewAuthVM"
    }

    private val _uiState = MutableStateFlow(WebViewAuthUiState())
    val uiState: StateFlow<WebViewAuthUiState> = _uiState.asStateFlow()

    fun onError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun onAuthReceived(token: String, viewerId: Long) {
        Log.i(TAG, "Auth received for viewerId=$viewerId")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                authRepository.saveWebViewToken(token, viewerId)
                Log.i(TAG, "Token saved. Reconnecting with token...")
                authRepository.disconnect()
                authRepository.connect()
                _uiState.update { it.copy(isLoading = false, isDone = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
