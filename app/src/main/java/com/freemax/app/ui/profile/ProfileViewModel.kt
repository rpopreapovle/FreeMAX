package com.freemax.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freemax.app.data.repository.ChatRepository
import com.freemax.app.data.repository.ContactInfo
import com.freemax.app.data.repository.MemberInfo
import com.freemax.app.data.repository.SelfProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val uploadRepository: com.freemax.app.data.repository.UploadRepository,
    private val maxClient: com.freemax.api.MaxClient
) : ViewModel() {

    private val _saving = kotlinx.coroutines.flow.MutableStateFlow(false)
    val saving: kotlinx.coroutines.flow.StateFlow<Boolean> = _saving.asStateFlow()

    private val _saveError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val saveError: kotlinx.coroutines.flow.StateFlow<String?> = _saveError.asStateFlow()

    fun clearSaveError() {
        _saveError.value = null
    }

    data class PickedAvatar(
        val file: com.freemax.app.data.repository.UploadRepository.ResolvedFile,
        val previewUrl: String
    )

    private val _pickedAvatar = kotlinx.coroutines.flow.MutableStateFlow<PickedAvatar?>(null)
    val pickedAvatar: kotlinx.coroutines.flow.StateFlow<PickedAvatar?> = _pickedAvatar.asStateFlow()

    fun pickAvatar(uri: android.net.Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val rf = uploadRepository.resolve(uri, "image/*") ?: return@launch
            // Avatars must be images
            if (!rf.mime.startsWith("image/")) return@launch
            _pickedAvatar.value = PickedAvatar(rf, uri.toString())
        }
    }

    fun clearAvatar() {
        _pickedAvatar.value = null
    }

    val selfProfile: StateFlow<SelfProfile?> = chatRepository.selfProfile
    val members: StateFlow<List<MemberInfo>> = chatRepository.members
    val chats: StateFlow<List<com.freemax.api.model.Chat>> = chatRepository.chats
    val presence: StateFlow<Map<Long, Long>> = chatRepository.presence
    val online: StateFlow<Set<Long>> = chatRepository.online

    private val _contact = MutableStateFlow<ContactInfo?>(null)
    val contact: StateFlow<ContactInfo?> = _contact.asStateFlow()

    fun loadContact(contactId: Long) {
        viewModelScope.launch {
            val cached = chatRepository.getContact(contactId)
            if (cached != null) _contact.value = cached
            // Force a refresh when there is no «О себе» yet: description may not
            // arrive with the chat-list contacts payload.
            val needRefresh = cached?.description == null
            chatRepository.ensureContacts(listOf(contactId), force = needRefresh)
            try { chatRepository.requestPresence(listOf(contactId)) } catch (_: Exception) {}
            // Re-read after fetch; contacts flow will also refresh titles
            val attempts = if (needRefresh) 5 else 1
            repeat(attempts) { i ->
                kotlinx.coroutines.delay(if (i == 0) 1500L else 800L)
                val cur = chatRepository.getContact(contactId)
                if (cur != null) {
                    _contact.value = cur
                    if (cur.description != null) return@launch
                }
            }
        }
    }

    fun loadMembers(chatId: Long) {
        chatRepository.loadMembers(chatId)
    }

    /** Обновить карточку группы на сервере (opcode GET_CHAT_INFO). */
    fun refreshGroupInfo(chatId: Long) {
        if (!clientConnected()) return
        try { maxClient.getChatInfo(chatId) } catch (_: Exception) {}
    }

    /** Присутствие участников: онлайн / последний визит. */
    fun requestMembersPresence(contactIds: Collection<Long>) {
        if (contactIds.isEmpty()) return
        try { chatRepository.requestPresence(contactIds) } catch (_: Exception) {}
    }

    /** Догрузить «О себе», телефон и аватары участников (LOAD_CONTACT_INFO). */
    fun enrichMembers(contactIds: Collection<Long>) {
        if (contactIds.isEmpty()) return
        viewModelScope.launch {
            try { chatRepository.ensureContacts(contactIds, force = true) } catch (_: Exception) {}
        }
    }

    private val _chatIcon = MutableStateFlow<String?>(null)
    val chatIcon: StateFlow<String?> = _chatIcon.asStateFlow()

    fun loadChatIcon(chatId: Long) {
        viewModelScope.launch {
            chatRepository.chats.collect { chats ->
                chats.find { it.id == chatId || it.cid == chatId }?.let {
                    _chatIcon.value = it.iconUrl
                }
            }
        }
    }

    fun findDialogWith(contactId: Long) = chatRepository.chats.value.firstOrNull { chat ->
        chat.type == 1 && chatRepository.dialogPeerId(chat) == contactId
    }

    fun canEditChat(chatId: Long): Boolean {
        val chat = chatRepository.chats.value.find {
            it.id == chatId || it.cid == chatId
        } ?: return false
        return chatRepository.canEditChat(chat)
    }

    /** Save own profile: name (+ optional new avatar image bytes). */
    fun saveSelfProfile(
        firstName: String,
        lastName: String,
        avatar: com.freemax.app.data.repository.UploadRepository.ResolvedFile?,
        avatarPreviewUrl: String? = null
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _saving.value = true
            _saveError.value = null
            try {
                var photoToken: String? = null
                if (avatar != null) {
                    photoToken = uploadRepository.uploadAvatar(avatar, forProfile = true)
                    if (photoToken == null) {
                        _saveError.value = "Не загрузилось фото"
                        _saving.value = false
                        return@launch
                    }
                }
                maxClient.updateProfile(
                    firstName = firstName.trim().takeIf { it.isNotBlank() },
                    lastName = lastName.trim().takeIf { it.isNotBlank() },
                    photoToken = photoToken,
                    avatarType = if (photoToken != null) "USER_AVATAR" else null
                )
                // Optimistic local update; server confirms via AUTH_SYNC/profile pushes
                val prev = chatRepository.selfProfile.value
                if (prev != null) {
                    val fullName = listOf(
                        firstName.trim().takeIf { it.isNotBlank() } ?: prev.firstName.orEmpty(),
                        lastName.trim().takeIf { it.isNotBlank() } ?: prev.lastName.orEmpty()
                    ).filter { it.isNotBlank() }.joinToString(" ").ifBlank { prev.name }
                    chatRepository.updateSelfProfile(
                        prev.copy(
                            name = fullName,
                            firstName = firstName.trim().takeIf { it.isNotBlank() } ?: prev.firstName,
                            lastName = lastName.trim().takeIf { it.isNotBlank() } ?: prev.lastName,
                            photoUrl = avatarPreviewUrl ?: prev.photoUrl
                        )
                    )
                }
                kotlinx.coroutines.delay(2000)
                if (clientConnected()) {
                    // Re-pull chats to refresh titles/avatars from server truth
                    try {
                        chatRepository.loadChats()
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                _saveError.value = "Ошибка: ${e.message}"
            } finally {
                _saving.value = false
            }
        }
    }

    /** Save group title (+ optional new avatar). */
    fun saveGroup(
        chatId: Long,
        title: String,
        avatar: com.freemax.app.data.repository.UploadRepository.ResolvedFile?,
        avatarPreviewUrl: String? = null
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _saving.value = true
            _saveError.value = null
            try {
                var photoToken: String? = null
                if (avatar != null) {
                    photoToken = uploadRepository.uploadAvatar(avatar, forProfile = false)
                    if (photoToken == null) {
                        _saveError.value = "Не загрузилось фото"
                        _saving.value = false
                        return@launch
                    }
                }
                val cleanTitle = title.trim().takeIf { it.isNotBlank() }
                if (cleanTitle == null && photoToken == null) {
                    _saving.value = false
                    return@launch
                }
                maxClient.editChat(chatId, title = cleanTitle, photoToken = photoToken)
                chatRepository.updateChatLocal(chatId, title = cleanTitle, iconUrl = avatarPreviewUrl)
                kotlinx.coroutines.delay(2000)
                try {
                    maxClient.getChatInfo(chatId)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                _saveError.value = "Ошибка: ${e.message}"
            } finally {
                _saving.value = false
            }
        }
    }

    private fun clientConnected(): Boolean {
        return try {
            maxClient.isConnected()
        } catch (_: Exception) {
            false
        }
    }
}
