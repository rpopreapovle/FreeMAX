package com.freemax.app.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freemax.app.data.repository.AttachKind
import com.freemax.app.data.repository.ChatRepository
import com.freemax.app.data.repository.MessageRepository
import com.freemax.app.data.repository.UploadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharePayload(
    val text: String = "",
    val uris: List<Uri> = emptyList()
) {
    val isEmpty: Boolean get() = text.isBlank() && uris.isEmpty()
}

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val uploadRepository: UploadRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    val chats = chatRepository.chats

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _sent = MutableStateFlow(false)
    val sent: StateFlow<Boolean> = _sent.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var payload: SharePayload = SharePayload()

    init {
        // Показываем и кэшированные чаты, и подтягиваем свежий список.
        try { chatRepository.loadChats() } catch (_: Exception) {}
    }

    fun setPayload(value: SharePayload) {
        payload = value
    }

    fun send(chatId: Long) {
        if (_sending.value || _sent.value) return
        if (payload.isEmpty) {
            _error.value = "Нечего отправлять"
            return
        }
        _sending.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cid = System.currentTimeMillis()
                val wireAttachments = mutableListOf<Map<String, Any?>>()
                for (uri in payload.uris) {
                    val resolved = uploadRepository.resolve(uri)
                        ?: throw IllegalStateException("Не удалось прочитать файл")
                    val kind = when {
                        resolved.mime.startsWith("image/") -> AttachKind.PHOTO
                        resolved.mime.startsWith("video/") -> AttachKind.VIDEO
                        resolved.mime.startsWith("audio/") -> AttachKind.AUDIO
                        else -> AttachKind.FILE
                    }
                    val wire = uploadRepository.upload(kind, resolved, cid)
                        ?: throw IllegalStateException("Не удалось загрузить «${resolved.name}»")
                    wireAttachments += wire
                }
                if (wireAttachments.isEmpty() && payload.text.isBlank()) {
                    throw IllegalStateException("Нечего отправлять")
                }
                messageRepository.sendMessage(
                    chatId = chatId,
                    text = payload.text,
                    attaches = wireAttachments,
                    cid = cid
                )
                _sent.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось отправить"
            } finally {
                _sending.value = false
            }
        }
    }
}
