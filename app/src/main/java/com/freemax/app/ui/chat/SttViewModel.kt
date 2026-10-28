package com.freemax.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freemax.app.core.storage.SecureStorage
import com.freemax.app.data.repository.SttRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared within a chat screen: STT toggle state + transcripts cache.
 * Disabled builds (no API key) report enabled=false so no STT UI ever shows.
 */
@HiltViewModel
class SttViewModel @Inject constructor(
    private val sttRepository: SttRepository,
    secureStorage: SecureStorage
) : ViewModel() {

    private val _enabled = MutableStateFlow(
        secureStorage.isSttEnabled() && sttRepository.isAvailable()
    )
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val transcripts: StateFlow<Map<Long, String>> = sttRepository.transcripts
    val pending: StateFlow<Set<Long>> = sttRepository.pending

    init {
        viewModelScope.launch {
            secureStorage.sttFlow.collect {
                _enabled.value = it && sttRepository.isAvailable()
            }
        }
    }

    fun ensure(audioKey: Long, audioUrl: String?) {
        sttRepository.ensure(audioKey, audioUrl)
    }
}
