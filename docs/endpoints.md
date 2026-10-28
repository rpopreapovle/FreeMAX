# MAX API Endpoints

## REST API (для видеозвонков)

Базовый URL: `https://api.mycdn.me`

Все запросы — POST с form-data параметрами.

### Авторизация

| Метод | Параметры | Ответ |
|-------|-----------|-------|
| `auth.anonymLogin` | `session_data` (JSON), `application_key` | `session_key`, `uid` |

### Звонки

| Метод | Параметры | Описание |
|-------|-----------|----------|
| `vchat.startConversation` | `conversationId`, `isVideo`, `joiningAllowed`, `protocolVersion`, `capabilities`, `payload` | Начать звонок |
| `vchat.joinConversation` | `conversationId`, `isVideo`, `protocolVersion`, `capabilities`, `chatId` | Присоединиться к звонку |
| `vchat.joinConversationByLink` | `joinLink`, `isVideo`, `protocolVersion`, `capabilities`, `anonymToken` | Войти по ссылке |
| `vchat.hangupConversation` | `conversationId`, `reason`, `application_key`, `session_key` | Завершить звонок |
| `vchat.getConversationParams` | `anonymToken`, `conversationId` | ICE-серверы, endpoint |
| `vchat.getAnonymTokenByLink` | `joinLink`, `anonymName` | Анонимный токен |
| `vchat.createJoinLink` | `conversationId` | Создать ссылку |
| `vchat.removeJoinLink` | `conversationId` | Удалить ссылку |
| `vchat.getInbounds` | (нет) | Входящие звонки |
| `vchat.removeHistoryRecords` | `recordIds` | Удалить историю |
| `vchat.clientStats` | `data` (JSON) | Статистика клиента |

### Система

| Метод | Параметры | Описание |
|-------|-----------|----------|
| `system.getInfo` | (нет) | Информация о сервере |

## WebSocket Signaling (для видеозвонков)

Дополнительный WebSocket для WebRTC-сигналинга.

URL: динамический, строится из `endpoint` в параметрах звонка.

### Команды

| Команда | Описание |
|---------|----------|
| `accept-call` | Принять звонок |
| `add-participant` | Добавить участника |
| `remove-participant` | Удалить участника |
| `hangup` | Завершить |
| `transmit-data` | Отправить ICE/SDP |
| `change-media-settings` | Изменить настройки медиа |
| `switch-micro` | Переключить микрофон |
| `switch-topology` | Переключить топологию (P2P/SFU) |
| `chat-message` | Сообщение в звонке |
| `hold` | Удержание |
| `mute-participant` | Заглушить участника |
| `pin-participant` | Закрепить видео |

## WebTransport (альтернатива WebSocket)

Для современных браузеров используется WebTransport вместо WebSocket.

| Параметр | Описание |
|----------|----------|
| URL | динамический (`wts://`) |
| Сжатие | gzip, deflate |
| Fallback | WebSocket |

## Тестовые окружения

| Сервер | URL |
|--------|-----|
| Production | `wss://api.oneme.ru/websocket` |
| Test | `apitest.ok.ru` |
| Video Test | `videotestapi.ok.ru` |
| Calls Test | `calls-test.okcdn.ru` |
| Beta | `api-beta.calls-test.oneme.ru` |
