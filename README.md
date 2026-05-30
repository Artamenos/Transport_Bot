# Transport Bot (Wi‑Fi)

## Запуск сервера

```powershell
.\gradlew.bat :server:installDist
cmd /c D:\cursovaya\server\build\install\server\bin\server.bat
```

Сервер слушает `0.0.0.0:8080` (доступен в вашей Wi‑Fi сети).

## Настройка клиента под IP ПК

В `gradle.properties` укажите IP ПК в вашей Wi‑Fi сети:

```ini
API_BASE_URL=http://192.168.1.75:8080/
```

## Установка приложения

```powershell
.\gradlew.bat :app:installDebug
```

Телефон и ПК должны быть в одной Wi‑Fi сети. Если подключение не проходит, проверьте правило фаервола для порта `8080`.

