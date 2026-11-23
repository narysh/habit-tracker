# Развёртывание Habit Tracker на Linux

Для постоянной работы нужен включённый Linux-сервер с Docker и Compose v2.24.4 или новее, постоянным диском и стабильным публичным именем. Настрой записи A/AAAA на реально доступные адреса и входящие TCP 80/443. Caddy получает и обновляет сертификаты, при этом PostgreSQL и Ktor доступны только внутри сети Compose. Демо на Render Free использует отдельную базу и не подходит для хранения рабочих данных. [Caddy HTTPS](https://caddyserver.com/docs/quick-starts/https) · [Ограничения Render Free](https://render.com/docs/free)

## Подготовка и запуск

1. Размести проверенную версию репозитория в отдельном каталоге. Сохрани `.local/production.env` с правами `0600`. Возьми поля из `.env.example` и замени примеры реальными значениями. Сгенерируй разные случайные значения для `DB_PASSWORD` и `MCP_CLIENT_SECRET`, например двумя вызовами `openssl rand -hex 32`.
2. Укажи `PUBLIC_URL=https://tracker.example.net` с фактическим именем, без `/mcp` и завершающего `/`. Из него также берётся `ANDROID_PUBLIC_URL` для подписанного подтверждения адреса. Существующие APK с автоматическим подключением доверяют определённому ключу. При миграции сначала восстанови исходную пару `.connection-identity` из резерва. Для новой установки нужен APK, настроенный на новый публичный ключ, или ручное подключение по HTTPS.
3. Если подключаешь плагин ChatGPT, задай `PUBLIC_CHATGPT_PLUGIN=true`, `MCP_CLIENT_ID=habit-tracker-chatgpt` и `MCP_REDIRECT_URIS` с точным HTTPS callback из кабинета OpenAI. Добавь `OPENAI_APPS_CHALLENGE` после получения значения. Провайдеры Google, VK и Яндекс требуют свои настройки и ключи, если эти кнопки нужны на сервере. [Настройка плагина](CHATGPT_PLUGIN.md) · [Настройка входа](SOCIAL_AUTH.md)

Выполняй команды из корня репозитория в Bash. Настройки из окружения оболочки имеют приоритет над значениями из файла.

```bash
dc() { docker compose --env-file .local/production.env -f compose.yaml -f compose.production.yaml "$@"; }
dc config --quiet
dc up -d --build
dc ps
```

Проект `habit-tracker-production` создаёт собственные тома. `postgres-data` хранит PostgreSQL, `attachment-data` — вложения вместе с парой ключей идентичности. `caddy-data` хранит ключи и сертификаты HTTPS, `caddy-config` — сохранённое состояние Caddy. Сервисы запускаются после перезапуска Docker, если их ранее не остановили вручную. Для запуска после перезагрузки включи службу Docker. На опубликованном сервере оставь Caddy единственным входом к backend. Он заменяет заголовок `X-Real-IP` адресом сетевого клиента, а backend в режиме `TRUSTED_CLIENT_IP_HEADER=X-Real-IP` использует его для ограничения попыток входа. Если между Интернетом и Caddy появится CDN или другой прокси, схему доверия нужно перенастроить (например, на `CF-Connecting-IP`) и проверить заново.

## mTLS для подключения ChatGPT

Для публичной проверки OpenAI требует стабильный HTTPS через Streamable HTTP. Когда публичный прокси ведёт к частному MCP-серверу, OpenAI предписывает проверять свой клиентский сертификат. Обычный профиль Compose подходит для Android и изолированного тестового MCP. Для проверки плагина через OpenAI используй дополнительный профиль mTLS. [Развёртывание MCP](https://developers.openai.com/plugins/build/mcp-server#deploy-the-endpoint) · [Сертификаты OpenAI](https://developers.openai.com/plugins/build/auth#mutual-tls-mtls)

Скачай промежуточный сертификат OpenAI Connectors и сохрани его в `.local/openai-connectors-intermediate.pem`. Добавь третий YAML к функции `dc` перед запуском или пересозданием Caddy. [Скачать сертификат PEM](https://developers.openai.com/plugins/mtls/openai-connectors-mtls-ca.pem)

```bash
dc() { docker compose --env-file .local/production.env -f compose.yaml -f compose.production.yaml -f compose.openai-mtls.yaml "$@"; }
dc config --quiet
dc run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile
dc up -d --build
```

Caddy проверяет предъявленную клиентскую цепочку при установлении TLS и допускает запросы к `/mcp` только с DNS SAN `mtls.prod.connectors.openai.com`. OAuth продолжает определять пользователя и его разрешения. Android, страница согласия OAuth и проверка домена могут работать без клиентского сертификата. Запрос `/mcp` без клиентского сертификата получает `403` на уровне Caddy. Запрос с сертификатом OpenAI без токена должен получить `401` от backend. Для полного теста профиля нужны реальный вызов из ChatGPT и подтверждение создания задачи и заметки в Android. MCP Inspector и `scripts/test_public_plugin.py` запускай на отдельном HTTPS-стенде без этого профиля.

## Проверки и обновление

- Из другой сети проверь `https://tracker.example.net/readyz`. Ответ `200` подтверждает доступ к БД и возможность записи в файловое хранилище, `503` требует проверки зависимостей. `/health` отвечает за проверку самого HTTP-процесса. Дополнительно проверь оба `/.well-known/oauth-protected-resource` и `/.well-known/oauth-authorization-server`, которые должны указывать тот же HTTPS origin.
- Если HTTPS недоступен, проверь A/AAAA, входящие 80/443 и `dc logs --tail 100 caddy`. При `502`, `503` или проблемах входа проверь `dc ps` и `dc logs --tail 100 backend postgres`. Проверь свободное место перед импортом файлов или миграцией. При публикации адреса для текущего APK сначала сверь ключ и подпись сервера по `scripts/Publish-Connection.ps1`.
- Перед обновлением сохрани согласованную копию базы и томов. Затем выбери проверенный релиз и выполни `dc up -d --build`. Flyway применит миграции при запуске. После обновления проверь `/readyz`, вход, синхронизацию и скачивание тестового файла. При несовместимых миграциях восстанавливай старую версию с соответствующей копией данных. Смена домена меняет OAuth resource, поэтому ChatGPT-клиентам понадобится привязка к новому адресу.
- Перед выдачей доступа проверь реальный сценарий на двух аккаунтах и восстановление на чистом хосте. Настрой мониторинг `/readyz`, SSL и места на диске, а также контроль возраста резервных копий.

## Согласованная резервная копия

Делай копию в окно обслуживания. Следующие команды останавливают приём запросов и запись backend, чтобы дамп и файлы соответствовали друг другу. Перед запуском проверь, что рабочий проект запущен, каталог резервных копий доступен для записи, а другие процессы не пишут в базу и файловый том. Храни архивы и файл окружения в зашифрованном хранилище вне этого сервера. В них есть персональные данные, пароли сервисов и закрытые ключи. Сохрани также версию кода и настройки DNS, OAuth и реестра адресов.

```bash
umask 077
backup="$(mktemp -d /var/backups/habit-tracker.XXXXXXXX)"

(
  set -e
  install -m 600 .local/production.env "$backup/production.env"
  git rev-parse HEAD > "$backup/revision"
  trap 'dc up -d backend caddy' EXIT
  dc stop caddy backend
  dc exec -T postgres pg_dump -U habit_tracker -d habit_tracker -Fc > "$backup/postgres.dump"
  dc run --rm -T --no-deps --entrypoint tar backend -czf - -C /data/files . > "$backup/attachments.tar.gz"
  dc run --rm -T --no-deps --entrypoint tar caddy -czf - -C /data . > "$backup/caddy-data.tar.gz"
  (cd "$backup" && sha256sum production.env revision postgres.dump attachments.tar.gz caddy-data.tar.gz > SHA256SUMS)
)
```

Если копирование прервалось, проверь, что `dc up -d backend caddy` восстановил работу, и повтори с новым каталогом. Копия считается полученной после проверки `sha256sum -c SHA256SUMS`. Храни хотя бы одну копию вне хоста и периодически проверяй восстановление.

## Восстановление на чистом хосте

Используй новую установку с пустыми томами и той же версией кода, что указана в `revision`. Восстановление поверх действующей базы этим способом не предусмотрено. Для тренировочного восстановления используй синтетические данные. Для реального восстановления держи внешние порты закрытыми до проверок, сохрани исходный HTTPS origin и отключи прежний сервер перед сменой DNS. Для профиля mTLS заранее положи опубликованный OpenAI промежуточный сертификат в `.local`.

```bash
# Укажи полный путь к проверенному каталогу копии.
backup=/var/backups/habit-tracker.REPLACE
(
  set -e
  (cd "$backup" && sha256sum -c SHA256SUMS)
  mkdir -p .local
  install -m 600 "$backup/production.env" .local/production.env
  dc build backend
  dc up -d --wait postgres
  dc exec -T postgres pg_restore -U habit_tracker -d habit_tracker \
    --exit-on-error --single-transaction --no-owner --no-privileges < "$backup/postgres.dump"
  dc run --rm -T --no-deps --entrypoint tar backend -xzf - -C /data/files < "$backup/attachments.tar.gz"
  dc run --rm -T --no-deps --entrypoint tar caddy -xzf - -C /data < "$backup/caddy-data.tar.gz"
  dc run --rm -T --no-deps --entrypoint sh backend -ec \
    'test -s /data/files/.connection-identity/private.der && test -s /data/files/.connection-identity/public.der'
)
```

Копия `caddy-config` не нужна: Caddy читает конфигурацию из репозитория. После восстановления выполни `dc up -d`, проверь `/readyz`, подпись идентичности, вход в тестовый аккаунт, синхронизацию и загрузку восстановленного файла. Затем переключи входящий трафик. Резерв содержит серверные сессии на момент копирования. При восстановлении после инцидента отдельно отзови скомпрометированные сессии и замени затронутые секреты.
