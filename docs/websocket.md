# WebSocket Lifecycle

Жизненный цикл WebSocket-соединения с MAX.

## Подключение

```
Клиент                          Сервер
  |                               |
  |--- GET /websocket ----------->|
  |    Upgrade: websocket         |
  |    Connection: Upgrade        |
  |    Sec-WebSocket-Key: ...     |
  |    Sec-WebSocket-Version: 13  |
  |                               |
  |<-- 101 Switching Protocols ---|
  |                               |
  |=== WebSocket established =====|
  |                               |
  |--- AUTH_REQUEST (17) -------->|  (первое сообщение)
  |<-- AUTH_RESPONSE (18) --------|
  |                               |
  |=== Authenticated ============|
  |                               |
  |--- PING --------------------->|
  |<-- PONG ----------------------|  (каждые 11-61 сек)
  |                               |
  |--- CHATS_LIST (53) --------->|
  |<-- CHATS_LIST_RESP -----------|
  |                               |
  |--- SUBSCRIBE (75) ----------->|  (подписка на обновления)
  |<-- SUBSCRIBE_RESP -------------|
  |                               |
  |<-- NOTIF_MESSAGE (125) -------|  (новое сообщение push)
  |<-- NOTIF_TYPING (126) --------|  (печатает)
  |<-- NOTIF_PRESENCE (129) ------|  (онлайн/офлайн)
```

## Heartbeat

Сервер отправляет ping каждые 11-61 секунд. Клиент отвечает pong.

```json
{"payload": {"method": 1, "update": {}}}
```

Ответ:
```json
{"payload": {"method": 1, "update": {}}}
```

Если pong не приходит > 60 сек — соединение считается мёртвым.

## Reconnect

При разрыве соединения клиент должен:

1. **Подождать** с exponential backoff:
   - 1 попытка: 2 сек
   - 2 попытка: 4 сек
   - 3 попытка: 8 сек
   - Максимум: 60 сек

2. **Переподключиться** к `wss://api.oneme.ru/websocket`

3. **Повторить авторизацию** (AUTH_REQUEST → AUTH_RESPONSE)

4. **Синхронизировать** пропущенные обновления:
   - `SYNC_SECTION` для каждого типа секции
   - `FOLDER_SYNC` для папок

## Timeout

| Параметр | Значение |
|----------|----------|
| Connect timeout | 15 сек |
| Ping interval min | 11 сек |
| Ping interval max | 61 сек |
| Fallback timeout | 21 сек |
| Reconnect base delay | 2 сек |
| Reconnect max delay | 60 сек |

## Состояния соединения

```
Disconnected
    ↓ (connect)
Connecting
    ↓ (connected)
Connected
    ↓ (auth request)
Authenticating
    ↓ (auth response)
Authenticated
    ↓ (subscribe)
Ready
    ↓ (disconnect/error)
Disconnected
```

## Пакетная синхронизация

После переподключения клиент отправляет:

```json
{"seq": N, "payload": {"method": 27, "req": {"type": "CHATS", "sync": {}}}}
```

Сервер отвечает всеми пропущенными обновлениями.
