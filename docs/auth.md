# Авторизация MAX

Флоу входа в аккаунт MAX через WebSocket.

## Фаза 1: Запрос SMS-кода

Клиент → Сервер:
```json
{
  "seq": 1,
  "payload": {
    "method": 17,
    "req": {
      "phone": "+79001234567",
      "type": 0,
      "language": "ru",
      "captchaToken": null
    }
  }
}
```

Сервер → Клиент:
```json
{
  "seq": 1,
  "payload": {
    "method": 18,
    "resp": {
      "token": "abc123...",
      "trackId": "track_456..."
    }
  }
}
```

## Фаза 2: Подтверждение SMS-кода

Клиент → Сервер:
```json
{
  "seq": 2,
  "payload": {
    "method": 18,
    "req": {
      "token": "abc123...",
      "verifyCode": "1234",
      "authTokenType": "CHECK_CODE"
    }
  }
}
```

### Успешный ответ

```json
{
  "seq": 2,
  "payload": {
    "method": 18,
    "resp": {
      "uid": 1234567890,
      "token": "session_token_...",
      "sessionKey": "session_key_...",
      "firstName": "Иван",
      "lastName": "Иванов",
      "photoId": "...",
      "status": "ok"
    }
  }
}
```

### Ответ: требует регистрацию

```json
{
  "seq": 2,
  "payload": {
    "method": 23,
    "resp": {
      "token": "reg_token_...",
      "trackId": "track_789...",
      "status": "register_required"
    }
  }
}
```

## Фаза 3: Регистрация (если новый аккаунт)

Клиент → Сервер:
```json
{
  "seq": 3,
  "payload": {
    "method": 23,
    "req": {
      "token": "reg_token_...",
      "tokenType": "REGISTER",
      "firstName": "Иван",
      "lastName": "Иванов",
      "photoId": null
    }
  }
}
```

Сервер → Клиент:
```json
{
  "seq": 3,
  "payload": {
    "method": 23,
    "resp": {
      "uid": 1234567890,
      "token": "session_token_...",
      "sessionKey": "session_key_...",
      "status": "ok"
    }
  }
}
```

## Фаза 4: Вход с паролем (2FA)

Если у аккаунта установлен пароль:

```json
{
  "seq": 4,
  "payload": {
    "method": 115,
    "req": {
      "trackId": "track_789...",
      "password": "my_password"
    }
  }
}
```

## Хранение токенов

| Токен | Назначение | Хранение |
|-------|-----------|----------|
| `uid` | ID пользователя | Keystore |
| `token` | Сессионный токен | Keystore (AES) |
| `sessionKey` | Ключ сессии | Keystore (AES) |

## Выход из аккаунта

```json
{
  "seq": N,
  "payload": {
    "method": 116,
    "req": {
      "trackId": "...",
      "delete": false
    }
  }
}
```

После этого клиент удаляет все локальные данные и закрывает WebSocket.
