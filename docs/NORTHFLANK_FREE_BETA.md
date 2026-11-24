# Постоянно доступное бесплатное демо на Northflank

Эта схема подходит для демонстрации и закрытых технических проверок без карты: backend работает на Northflank Sandbox, а PostgreSQL и вложения — в отдельном бесплатном проекте Neon. Northflank прямо не рекомендует Sandbox для production-приложений. Здесь нет production SLA, гарантий доступности и гарантированного резервного восстановления. План включает до $20 ресурсов Northflank в месяц; не переключайся на Pay as you go и не добавляй карту. Проверяй Usage и актуальные ограничения в аккаунте. [Тариф Northflank](https://northflank.com/pricing), [ограничения бесплатного тарифа](https://northflank.com/docs/v1/application/billing/pricing-on-northflank)

Neon Free ограничен 100 CU-часами, 1 ГБ PostgreSQL и 5 ГБ объектного хранилища на проект. База автоматически приостанавливается после периода простоя (по умолчанию пять минут) и может проснуться с небольшой задержкой. Это дополнительное ограничение демо, не храни здесь единственную копию важных данных. [Лимиты Neon Free](https://neon.com/blog/neon-free-plan-1-gb-per-project), [автоприостановка Neon](https://neon.com/blog/new-usage-based-pricing)

## 1. Подготовь Neon

Создай отдельный проект Neon и приватное S3-совместимое хранилище объектов. Сохрани connection string PostgreSQL с `sslmode=require`, endpoint, bucket и пару ключей. Не включай public-read. Храни эти значения только в секретах Northflank, не в репозитории, APK или переписке.

## 2. Подготовь секреты сервера

Из корня проекта выполни:

```powershell
pwsh -File .\scripts\New-HostedConnectionKeys.ps1
```

Скрипт создаст постоянную пару `CONNECTION_PRIVATE_KEY_BASE64` и `CONNECTION_PUBLIC_KEY_BASE64` в игнорируемом `.local/hosted-connection-keys.env`. Не пересоздавай её после запуска. Для `MCP_CLIENT_SECRET` сгенерируй отдельное случайное значение длиной не менее 32 символов и храни его в менеджере секретов.

## 3. Создай сервис Northflank

В Northflank создай проект в Sandbox и один **Combined service** из публичного репозитория `narysh/habit-tracker`, ветка `master`:

- Build type: Dockerfile.
- Dockerfile: `backend/Dockerfile`.
- Build context: корень репозитория (`.`).
- Публичный HTTP-порт: `8081`.
- Readiness check: `/readyz`.
- Масштаб: одна реплика и минимальный доступный план. Не увеличивай ресурсы и не создавай второй сервис без проверки Usage.

Публичный порт получает автоматически созданный HTTPS-домен `code.run` с TLS. Скопируй точный origin из **Networking**; формат имени документация задаёт как `[port]--[service]--[random].code.run`. Не составляй адрес вручную. [Настройка публичных портов](https://northflank.com/docs/v1/application/network/configure-ports)

Отключи автоматическую доставку (CD) до проверки backend CI. Новый URL порта будет доступен в настройках сервиса даже если первый запуск ещё не прошёл проверку готовности.

## 4. Добавь переменные окружения

Добавь их в **Secrets / Environment** Combined service. Значение `PUBLIC_URL` и `ANDROID_PUBLIC_URL` — один и тот же точный HTTPS origin из Northflank, без завершающего `/`.

```text
HOSTED_MODE=true
PUBLIC_CHATGPT_PLUGIN=true
PUBLIC_URL=<Northflank HTTPS origin>
ANDROID_PUBLIC_URL=<тот же HTTPS origin>
PORT=8081
MCP_CLIENT_ID=habit-tracker-chatgpt
MCP_CLIENT_SECRET=<случайный секрет не короче 32 символов>
MCP_REDIRECT_URIS=https://chatgpt.com/connector_platform_oauth_redirect
DATABASE_URL=<Neon PostgreSQL URL с sslmode=require>
S3_ENDPOINT=<Neon Object Storage endpoint>
S3_BUCKET=<private bucket>
S3_ACCESS_KEY=<Neon access key>
S3_SECRET_KEY=<Neon secret key>
S3_REGION=auto
CONNECTION_PRIVATE_KEY_BASE64=<из локального файла>
CONNECTION_PUBLIC_KEY_BASE64=<из локального файла>
TRUSTED_CLIENT_IP_HEADER=X-Envoy-External-Address
MAINTENANCE_INTERVAL_SECONDS=3600
SOCIAL_CALLBACK_BASE=https://narysh.github.io/habit-tracker
```

Northflank ставит `X-Envoy-External-Address` на публичных HTTP-запросах и заменяет переданное клиентом значение; это позволяет ограничивать попытки входа по реальному IP. Не используй `X-Forwarded-For`: в нём может быть переданный клиентом список адресов. [Сетевые заголовки Northflank](https://northflank.com/docs/v1/application/network/networking-on-northflank)

`MAINTENANCE_INTERVAL_SECONDS=3600` нужен для бесплатного Neon: фоновые задачи чистки не должны опрашивать базу каждые 30 секунд и мешать ей приостанавливаться. В hosted-режиме backend использует часовой интервал по умолчанию; очередь с большой пачкой удаления дочищается сразу. Удалённые вложения без активных ссылок могут оставаться в хранилище до часа.

Переменные Google и Яндекса добавляй только после создания OAuth-клиентов с точными callback URL из [инструкции по соцвходу](SOCIAL_AUTH.md). Вход по email и паролю работает без них. VK требует отдельного приложения и не считается настроенным.

Сохрани переменные и запусти сервис. После появления статуса **Live** проверь ответы:

```powershell
$origin = "https://точное-имя-сервиса.code.run"
Invoke-RestMethod "$origin/health"
Invoke-RestMethod "$origin/readyz"
Invoke-RestMethod "$origin/.well-known/oauth-authorization-server"
curl.exe -i -X POST "$origin/mcp" -H "Content-Type: application/json" --data "{}"
```

Последняя команда без токена должна получить `401`, не данные.

## 5. Подключи Android и ChatGPT

Для внутренней проверки собери debug APK, указав полученный HTTPS origin:

```powershell
.\gradlew.bat :android-app:assembleDebug "-PhabitTrackerOrigin=$origin"
```

Debug APK не является подписанным релизом. Для распространения нужен постоянный ключ подписи и production-проверки; см. [сборку релиза](ANDROID_RELEASE.md).

В Android настройках подключения укажи этот же адрес и проверь регистрацию, офлайн-правку и повторную синхронизацию. Затем подключи MCP к ChatGPT через веб-клиент и проверь чтение/запись синтетической записи. На телефоне используй **Поделиться → Habit Tracker**: мобильный ChatGPT не подключает пользовательские MCP-приложения.

Этот вариант не готов для предоставления конечным пользователям. Для такого запуска нужны production-хостинг с понятной оплатой и SLA, автоматические резервные копии PostgreSQL и файлов с проверкой восстановления, production OAuth, политика конфиденциальности, контакты поддержки и план миграции/уведомления при сбое. Не помещай личные или важные данные на демо-сервер.
