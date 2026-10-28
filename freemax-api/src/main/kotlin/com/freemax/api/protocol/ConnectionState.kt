package com.freemax.api.protocol

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data object Authenticating : ConnectionState()
    data object Authenticated : ConnectionState()
    data object Ready : ConnectionState()
    data class Reconnecting(val attempt: Int) : ConnectionState()
    data class Error(val message: String, val cause: Throwable? = null) : ConnectionState()

    fun isActive(): Boolean = this is Connected || this is Ready || this is Authenticated

    companion object {
        fun initial(): ConnectionState = Disconnected
    }
}

sealed class AuthState {
    data object Idle : AuthState()
    data object CodeSent : AuthState()
    data object CodeVerified : AuthState()
    data object Registering : AuthState()
    data object LoggedIn : AuthState()
    data class Error(val message: String) : AuthState()
}
