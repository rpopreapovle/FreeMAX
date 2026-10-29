package com.freemax.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реальное наличие сети на устройстве, независимо от состояния WebSocket.
 *
 * Нужен, чтобы не писать «Нет сети», когда интернет есть, а сокет в этот
 * момент переподключается. Используем NET_CAPABILITY_INTERNET, а не
 * VALIDATED: captive portal не должен выглядеть как полное отсутствие сети.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _available = MutableStateFlow(check())
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _available.value = check()
        }

        override fun onLost(network: Network) {
            _available.value = check()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            _available.value = check()
        }

        override fun onUnavailable() {
            _available.value = check()
        }
    }

    init {
        try {
            manager?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // Разрешение/платформа не дали подписаться — останется начальное значение.
        }
    }

    private fun check(): Boolean {
        val cm = manager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
