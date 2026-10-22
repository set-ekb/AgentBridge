# AgentBridge ↔ OpenCode: первый этап (локальная связь)

Ветка: `feature/opencode-local-link`. Этот этап только читает состояние OpenCode.
**Никаких заданий ИИ, Android-действий, команд shell, root или управления сессиями** шлюз не принимает.

## Уже существующие компоненты

- AgentBridge 0.8.9-local — APK, общая память, голос, маршрутизация провайдеров и MCP.
- OpenCode 1.19.3 — процесс `opencode serve --hostname 127.0.0.1 --port 4096`.
- Basic-пароль OpenCode остаётся **только в Termux**.
- Лёгкий Python-шлюз на `127.0.0.1:4097` использует **отдельный ключ сопряжения**.
- APK сохраняет лишь ключ сопряжения в Android Keystore через имеющийся `AgentPrefs`.

## Подготовка в Termux

1. Не закрывайте текущий сервер OpenCode на 4096.
2. Проверьте закрытые права у `~/.config/agentbridge-opencode/password` (0600).
3. Получите скрипт из этой ветки в Termux (например, скопируйте файл `scripts/opencode_bridge_gateway.py` из GitHub).
4. Запустите скрипт отдельной сессией Termux:

```bash
python scripts/opencode_bridge_gateway.py
```

5. Ключ сопряжения сохраняется в
   `~/.config/agentbridge-opencode/pairing-token`.
   **Не публикуйте его и не отправляйте в чат или GitHub.**
   Введите значение один раз в AgentBridge → Настройки → OpenCode · Termux.
6. Нажмите «Проверить связь без обращений к модели».
   При работающем OpenCode увидите версию, число активных сессий, и статус защищённого канала.

Никакая авторизация OpenAI или пароль OpenCode в приложение не копируется.

## Контракт шлюза

`GET http://127.0.0.1:4097/status`, заголовок `Authorization: Bearer <pairing-token>`.

Ответ при успешном чтении OpenCode:

```json
{"gateway":"agentbridge-opencode","mode":"read_only","healthy":true,"version":"1.19.3","active_sessions":0}
```

Без правильного токена — 401. Других GET-маршрутов, команд и POST-операций нет.
Сервер слушает **только loopback**, не доступен из локальной сети.
Два запроса к OpenCode — `/global/health` и `/session/status`; они не расходуют лимит модели.

## Безопасность и ограничения

- Статус шлюза **не** означает, что модель может выполнять задачи в Android Bridge.
- Текущая серверная часть MCP AgentBridge слушает 0.0.0.0:8080 и имеет отдельный Bearer-токен. В этом этапе шлюз его не использует.
- До полноценного исполнения требуется проверка: Android permissions, правила подтверждений, root, безопасный доступ к MCP и запрет повторного выполнения действий после сетевой ошибки.
- Не запускать CI и не публиковать APK автоматически.
- Проверить на телефоне обновление текущего APK, сохранение памяти/Android Keystore и работу защищённой сети.
- Для отката закрыть Python-шлюз; прежние провайдеры и MCP не изменены.

## Локальные проверки

```bash
python -m py_compile scripts/opencode_bridge_gateway.py
python -m unittest discover -s scripts -p 'test_opencode_gateway.py'
```

Android: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` при наличии SDK 35/JDK 17.
