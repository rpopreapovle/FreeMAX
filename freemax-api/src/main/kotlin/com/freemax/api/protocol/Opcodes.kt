package com.freemax.api.protocol

object Opcodes {
    // System
    const val PING = 1
    const val PONG = 1
    const val LOGIN = 6
    const val SEND_EVENTS = 5
    const val SET_PUSH_TOKEN = 20
    const val UPDATE_SETTINGS = 22
    const val SYNC_SECTION = 27
    const val GET_OWN_PROFILE = 80
    const val GET_CONFIG = 96
    const val GET_CONFIG_FULL = 97

    // Auth
    const val AUTH_REQUEST = 17
    const val AUTH = 18
    const val AUTH_SYNC = 19
    const val REGISTER = 23
    const val AUTH_CHECK = 101
    const val AUTH_CHECK_STATE = 104
    const val AUTH_PASSWORD = 107
    const val AUTH_HINT = 108
    const val AUTH_EMAIL = 109
    const val AUTH_EMAIL_VERIFY = 110
    const val AUTH_CAPABILITIES = 111
    const val AUTH_TYPE_CHECK = 112
    const val SET_PASSWORD = 113
    const val LOGIN_PASSWORD = 115
    const val DELETE_ACCOUNT = 116
    const val PHONE_AUTH_REQUEST = 224
    const val PHONE_AUTH_START = 288
    const val PHONE_AUTH_CHECK = 289
    const val PHONE_AUTH_CHECK2 = 291

    // Chats
    const val LOAD_CHATS = 48
    const val LOAD_MORE_CHATS = 53
    const val EDIT_CHAT = 55
    const val OPEN_CHAT = 58
    const val MARK_CHATS_SEEN = 62
    const val SEARCH_CHATS = 68
    const val GET_CHAT_INFO = 72
    const val SHOW_HIDE_CHAT = 86
    const val LOAD_CHATS_BATCH = 258

    // Messages (verified: 66=delete {chatId,messageIds,forMe}, forwards go via 64+link)
    const val LOAD_MESSAGES = 49
    const val LOAD_MESSAGES_CONTEXT = 51
    const val DELETE_CHAT = 54
    const val JOIN_CHAT = 57
    const val SEND_MESSAGE = 64
    const val SEND_TYPING = 65
    const val DELETE_MESSAGES = 66
    const val EDIT_MESSAGE = 67
    const val LOAD_MESSAGES_BATCH = 71
    const val SEARCH_MESSAGES = 73
    const val MARK_MESSAGES_READ = 74
    const val GET_MESSAGE = 87
    const val SEARCH_MESSAGES_FULL = 89
    const val GET_MESSAGE_71 = 71

    // Read status
    const val MARK_READ = 50
    const val CHAT_ACCESS = 65

    // Notifications (verified against live pushes:
    // 128={chatId,message,...} (new + edited), 129={chatId,userId} typing, 130={chatId,userId,mark})
    const val NOTIF_MESSAGE = 128
    const val NOTIF_MESSAGE_EDIT = 128
    const val NOTIF_TYPING = 129
    const val NOTIF_MARK = 127
    const val NOTIF_PRESENCE = 132
    const val NOTIF_REACTION = 156
    const val NOTIF_MSG_DELETE = 142
    const val NOTIF_MSG_DELETE_RANGE = 140
    const val NOTIF_ATTACH = 136
    const val NOTIF_READ = 130
    const val NOTIF_CONFIG = 130
    const val NOTIF_CHAT = 131

    // Contacts
    const val LOAD_CONTACTS = 32
    const val CONTACTS_PRESENCE = 35
    const val LOAD_PARTICIPANTS = 39
    const val LOOKUP_PHONE = 46
    const val SEARCH_PARTICIPANTS = 59
    const val SEARCH_ALL = 60
    const val LOAD_CONTACT_INFO = 198

    // Media
    const val GET_ATTACHMENTS = 28
    const val GET_UPLOAD_SLOT = 82
    const val FILE_UPLOAD_SLOT = 87
    // Photo upload slot (same numeric opcode as GET_OWN_PROFILE, routed by payload)
    const val PHOTO_UPLOAD_SLOT = 80
    // Ссылки на видео (web.max.ru: cmd(83,{videoId,token,chatId,postId,messageId}) -> MP4_*)
    const val VIDEO_UPLOAD = 83
    // Ссылка на файл (web.max.ru: cmd(88,{fileId,chatId,messageId,itemType}) -> url)
    const val FILE_DOWNLOAD = 88
    const val DELETE_STICKERS = 199
    const val DOWNLOAD_MEDIA = 202
    const val LOAD_MEDIA_LIST = 203
    const val FAVORITE_STICKERS_BATCH = 259
    const val CLEAR_RECENT_STICKERS = 261
    // Ссылки на аудио (web.max.ru: cmd(301,{audioId,chatId,messageId,token}) -> m4a/mp3/opus)
    const val AUDIO_UPLOAD = 301

    // Reactions
    const val ADD_REACTION = 178
    const val REMOVE_REACTION = 179
    const val LOAD_REACTIONS = 180
    const val LOAD_REACTION_DETAILS = 181

    // Links
    const val RESOLVE_LINK = 56
    const val RESOLVE_LINKS_BATCH = 70
    const val RESOLVE_DEEP_LINK = 89
    const val LOAD_URL_PREVIEW = 105

    // Folders
    const val FOLDER_SYNC = 272
    const val LOAD_FOLDERS = 273
    const val EDIT_FOLDER = 274
    const val LOAD_FOLDER_CHATS = 300

    // Stories
    const val LOAD_STORIES_CURSOR = 208
    const val LOAD_STORIES = 209
    const val LOAD_STORIES_BATCH = 210
    const val LOAD_STORY_REACTIONS = 212
    const val REACT_TO_STORY = 213
    const val LOAD_STORIES_BY_IDS = 220

    // Polls
    const val VOTE_POLL = 304
    const val LOAD_POLL_ANSWERS = 305
    const val LOAD_POLL_RESULTS = 306

    // Bots
    const val BOT_COMMAND = 78
    const val BOT_ACTION = 84
    const val GET_BOT_INFO = 106
    const val BOT_STARTED = 119
    const val GET_BOT_PROFILE = 145
    const val OPEN_BOT = 160

    // Calls
    const val GET_CALL_HISTORY = 76
    const val GET_CONVERSATION = 85
    const val VIDEO_CHAT_START = 92
    const val VIDEO_CHAT_JOIN = 94

    // Misc
    const val LOAD_PARTICIPANTS_39 = 39
    const val MANAGE_MEMBERS = 77
    const val LOAD_COMMENTS = 91
    const val COMMENT_ON_MESSAGE = 161
    const val SUBSCRIBE_UNSUBSCRIBE = 75
    const val REPORT_MESSAGE = 94
    const val LOAD_ORGANIZATIONS = 256
    const val BANNERS_SYNC = 302
    const val COMPLAIN_REASONS_SYNC = 162
    const val CALL_HISTORY_SYNC = 163
    const val GET_UNKNOWN = 158
}
