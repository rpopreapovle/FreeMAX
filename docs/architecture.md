# FreeMAX Architecture

## Обзор

```
┌─────────────────────────────────────────────┐
│              Jetpack Compose UI             │
│         (Material 3, Dynamic Colors)        │
├─────────────────────────────────────────────┤
│         ViewModel + StateFlow               │
├─────────────────────────────────────────────┤
│         UseCase Layer (domain/)             │
├─────────────────────────────────────────────┤
│         Repository Layer (data/)            │
├─────────────────────────────────────────────┤
│         API Layer (freemax-api/)            │
│    ┌──────────────┬────────────────────┐    │
│    │  WebSocket   │    REST API        │    │
│    │  OkHttp      │    OkHttp          │    │
│    │  JSON        │    JSON            │    │
│    └──────────────┴────────────────────┘    │
├─────────────────────────────────────────────┤
│  wss://api.oneme.ru/websocket               │
│  https://api.mycdn.me                       │
└─────────────────────────────────────────────┘
```

## Модули

### `freemax-api` (pure Kotlin JVM)

Независимый от Android модуль. Содержит:

- **protocol/** — опкоды, сериализация JSON, конечный автомат соединения
- **model/** — data-классы (Chat, Message, Contact, User, Attachment)
- **transport/** — WebSocket-клиент (OkHttp), HTTP-клиент
- **MaxClient** — публичный API модуля

### `app` (Android)

UI-слой приложения:

- **core/** — network, storage (Keystore), util
- **data/** — repository, DI (Hilt modules)
- **domain/** — use cases
- **ui/** — Compose screens, ViewModels, navigation

## Зависимости

```
app ──depends on──> freemax-api
app ──uses──> Android SDK, Compose, Hilt
freemax-api ──uses──> OkHttp, kotlinx.serialization, Coroutines
```

## Навигация

```
AuthScreen ──(login success)──> ChatsScreen ──(tap chat)──> ChatScreen
                                        └──(settings)──> SettingsScreen
```

## Хранение данных

| Данные | Где | Как |
|--------|-----|-----|
| Токены (uid, token, sessionKey) | Keystore → AES → EncryptedStorage | EncryptedSharedPreferences / Keystore напрямую |
| Список чатов | In-memory (ViewModel) | Загрузка через WebSocket |
| Сообщения | In-memory (ViewModel) | Загрузка через WebSocket |
| Настройки | EncryptedSharedPreferences | mute, theme, desktop mode |

## Состояния (ConnectionState)

```
Disconnected → Connecting → Connected → Authenticating → Authenticated → Ready
                                 ↓                              ↓
                           Reconnecting ←──────────────── Disconnected
```

## Потоки данных

### Новые сообщения

```
WebSocket (push: NOTIF_MESSAGE)
  → MaxClient
    → MessageRepository
      → ChatViewModel (StateFlow)
        → ChatScreen (Compose)
```

### Отправка сообщения

```
ChatScreen (user input)
  → ChatViewModel.sendMessage()
    → SendMessageUseCase
      → MessageRepository
        → MaxClient.send(SEND_MESSAGE)
          → WebSocket
```
