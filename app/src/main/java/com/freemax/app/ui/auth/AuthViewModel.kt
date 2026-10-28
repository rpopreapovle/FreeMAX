package com.freemax.app.ui.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freemax.api.protocol.ConnectionState
import com.freemax.api.protocol.Opcodes
import com.freemax.api.protocol.WsFrame
import com.freemax.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val phone: String = "",
    val code: String = "",
    val trackId: String? = null,
    val step: AuthStep = AuthStep.Phone,
    val error: String? = null,
    val isLoading: Boolean = false
)

enum class AuthStep {
    Phone,
    Code,
    Register,
    Password,
    Done
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    companion object {
        private const val TAG = "AuthVM"
    }

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        observeConnectionState()
        observeFrames()
        if (authRepository.isLoggedIn()) {
            _uiState.update { it.copy(step = AuthStep.Done) }
        }
    }

    fun connect() {
        Log.i(TAG, "connect() called")
        authRepository.connect()
    }

    fun updatePhone(phone: String) {
        _uiState.update { it.copy(phone = phone, error = null) }
    }

    fun updateCode(code: String) {
        _uiState.update { it.copy(code = code, error = null) }
    }

    fun requestCode() {
        val phone = _uiState.value.phone
        if (phone.isBlank()) {
            _uiState.update { it.copy(error = "Введите номер телефона") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            authRepository.requestCode(phone)
        }
    }

    fun verifyCode() {
        val code = _uiState.value.code
        if (code.isBlank()) {
            _uiState.update { it.copy(error = "Введите код") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            authRepository.verifyCode(code)
        }
    }

    fun register(firstName: String, lastName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            authRepository.register(firstName, lastName)
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            authRepository.state.collect { state ->
                Log.i(TAG, "ConnectionState: $state")
                _uiState.update { it.copy(connectionState = state) }
                when (state) {
                    is ConnectionState.Ready -> {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    is ConnectionState.Error -> {
                        Log.e(TAG, "Connection error: ${state.message}", state.cause)
                        _uiState.update {
                            it.copy(isLoading = false, error = state.message)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeFrames() {
        viewModelScope.launch {
            authRepository.observeFrames().collect { frame ->
                handleFrame(frame)
            }
        }
    }

    private fun handleFrame(frame: WsFrame) {
        Log.i(TAG, "handleFrame: cmd=${frame.cmd} seq=${frame.seq} opcode=0x${frame.opcode.toString(16)} payload=${frame.payload?.keys}")

        when (frame.opcode) {
            Opcodes.LOGIN -> handleLoginResponse(frame)
            Opcodes.AUTH_SYNC -> handleAuthSyncResponse(frame)
            Opcodes.PHONE_AUTH_REQUEST -> handlePhoneAuthResponse(frame)
            Opcodes.AUTH_EMAIL_VERIFY -> handleVerifyResponse(frame)
            Opcodes.AUTH_REQUEST -> handleLegacyAuthResponse(frame)
            Opcodes.AUTH -> handleLegacyAuthResponse(frame)
            Opcodes.REGISTER -> handleRegisterResponse(frame)
            else -> {
                Log.d(TAG, "Unhandled opcode: 0x${frame.opcode.toString(16)}")
            }
        }
    }

    private fun handleLoginResponse(frame: WsFrame) {
        val payload = frame.payload
        Log.i(TAG, "Login response: cmd=${frame.cmd} keys=${payload?.keys}")

        if (frame.cmd == 3) {
            // Error response
            val error = payload?.get("error")?.toString()
            val message = payload?.get("message")?.toString()
            val title = payload?.get("title")?.toString()
            Log.e(TAG, "LOGIN ERROR: error=$error message=$message title=$title")
            _uiState.update {
                it.copy(isLoading = false, error = "Login failed: $message")
            }
            return
        }

        val savedAuth = payload?.get("savedAuth")
        if (savedAuth != null && savedAuth != Unit) {
            Log.i(TAG, "Already logged in")
            _uiState.update { it.copy(step = AuthStep.Done, isLoading = false) }
        } else {
            Log.i(TAG, "Not logged in, waiting for user to enter phone")
        }
    }

    private fun handleAuthSyncResponse(frame: WsFrame) {
        val payload = frame.payload
        Log.i(TAG, "AuthSync response: cmd=${frame.cmd} keys=${payload?.keys}")
        if (frame.cmd == 3) {
            val error = payload?.get("error")?.toString()
            val message = payload?.get("message")?.toString()
            Log.e(TAG, "AUTH_SYNC ERROR: error=$error message=$message")
            _uiState.update { it.copy(isLoading = false, error = "Auth failed: $message") }
            return
        }
        // Success: contains profile, chats, contacts, etc.
        if (payload?.containsKey("profile") == true) {
            Log.i(TAG, "AuthSync success, profile + chats received")
            _uiState.update { it.copy(step = AuthStep.Done, isLoading = false) }
        }
    }

    private fun handlePhoneAuthResponse(frame: WsFrame) {
        val payload = frame.payload ?: return
        Log.i(TAG, "Phone auth response: keys=${payload.keys}")

        val trackId = payload["trackId"]?.toString()
        if (trackId != null) {
            authRepository.saveTrackId(trackId)
            _uiState.update {
                it.copy(step = AuthStep.Code, trackId = trackId, isLoading = false)
            }
        } else {
            val error = payload["error"]?.toString() ?: payload["message"]?.toString()
            _uiState.update {
                it.copy(isLoading = false, error = error ?: "Ошибка отправки кода")
            }
        }
    }

    private fun handleVerifyResponse(frame: WsFrame) {
        val payload = frame.payload ?: return
        Log.i(TAG, "Verify response: status=${payload["status"]} keys=${payload.keys}")

        val status = payload["status"]?.toString()
        when (status) {
            "ok" -> {
                val token = payload["token"]?.toString() ?: ""
                val sessionKey = payload["sessionKey"]?.toString() ?: ""
                val userId = (payload["uid"] as? Number)?.toLong() ?: 0L
                authRepository.saveSession(token, sessionKey, userId)
                _uiState.update { it.copy(step = AuthStep.Done, isLoading = false) }
            }
            "register_required" -> {
                val trackId = payload["trackId"]?.toString() ?: ""
                authRepository.saveTrackId(trackId)
                _uiState.update {
                    it.copy(step = AuthStep.Register, trackId = trackId, isLoading = false)
                }
            }
            else -> {
                val error = payload["error"]?.toString() ?: payload["message"]?.toString()
                _uiState.update {
                    it.copy(isLoading = false, error = error ?: "Ошибка верификации")
                }
            }
        }
    }

    private fun handleLegacyAuthResponse(frame: WsFrame) {
        val payload = frame.payload ?: return
        Log.i(TAG, "Legacy auth response: status=${payload["status"]} keys=${payload.keys}")

        val status = payload["status"]?.toString()
        when (status) {
            "ok" -> {
                val token = payload["token"]?.toString() ?: ""
                val sessionKey = payload["sessionKey"]?.toString() ?: ""
                val userId = (payload["uid"] as? Number)?.toLong() ?: 0L
                authRepository.saveSession(token, sessionKey, userId)
                _uiState.update { it.copy(step = AuthStep.Done, isLoading = false) }
            }
            "register_required" -> {
                val trackId = payload["trackId"]?.toString() ?: ""
                authRepository.saveTrackId(trackId)
                _uiState.update {
                    it.copy(step = AuthStep.Register, trackId = trackId, isLoading = false)
                }
            }
            else -> {
                val error = payload["error"]?.toString() ?: payload["message"]?.toString()
                _uiState.update {
                    it.copy(isLoading = false, error = error ?: "Ошибка авторизации")
                }
            }
        }
    }

    private fun handleRegisterResponse(frame: WsFrame) {
        val payload = frame.payload ?: return
        Log.i(TAG, "Register response: keys=${payload.keys}")

        val token = payload["token"]?.toString() ?: ""
        val sessionKey = payload["sessionKey"]?.toString() ?: ""
        val userId = (payload["uid"] as? Number)?.toLong() ?: 0L
        authRepository.saveSession(token, sessionKey, userId)
        _uiState.update { it.copy(step = AuthStep.Done, isLoading = false) }
    }
}
