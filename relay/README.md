# Серверная сторона Point

Здесь живут **две** вещи, и пока они рядом, а не одна вместо другой:

| что | файлы | состояние |
|---|---|---|
| **Сервер аккаунтов** — вход по Google, круг устройств, изоляция (#471) | `point_server/`, `tests/` | новое, клиентов ещё нет |
| **Слепой почтовый ящик** — сегодняшняя связь телефон↔ПК (#161) | `relay.py`, `start.sh`, `cert.pem` | работает, переезжает под аккаунты срезом #476 |

Проект целиком — `docs/superpowers/specs/2026-08-04-point-server.md`; порядок срезов — там же,
раздел 10.

---

# Сервер аккаунтов (#471)

**Аккаунт даёт право положить и забрать, но не право прочитать.** Этот срез не возит ни байта
содержимого вовсе: он знает, чьи устройства, — и всё. Ящики приезжают под аккаунты отдельно (#476),
и слепота при этом не теряется, потому что ключи рождаются у устройств (#474).

## Вход «как у телевизора»

Устройство в OAuth **не участвует**: `client_id`/`client_secret` Google живут только на сервере,
в файле службы. В APK и MSI их нет и быть не может — это и есть лечение общего секрета приложения
(#419), а не замена одного секрета другим.

```
устройство                     сервер                          Google
    │ POST /auth/start ──────────►│
    │ ◄── login_id + claim_token + код «K7-42Q» + адрес страницы входа
    │ открыть браузер ───────────►│ GET /login?d=<login_id>   (код сверяется глазами)
    │                             │ ── Authorization Code + PKCE ──►│
    │                             │ ◄──────── id_token (sub, email) │
    │ GET /auth/session/<login_id>│   (Authorization: Bearer <claim_token>)
    │ ◄── device_id + device_token — один раз, потом 404
```

`claim_token` отдельно от `login_id` нарочно: `login_id` лежит в адресе страницы, которую человек
открывает в браузере, и одного его для забора пропуска аккаунта быть не должно.

## Ручки

| Метод | Путь | Пропуск | Что делает |
|---|---|---|---|
| GET | `/health` | — | `ok` |
| POST | `/auth/start` | — | начать вход: `{kind: PHONE\|PC, name?, key_agree?, key_sign?}` |
| GET | `/login?d=<login_id>` | — | страница входа для человека (код + кнопка) |
| POST | `/login` | — | форма со страницы → редирект в Google (здесь рождаются `state` и PKCE) |
| GET | `/auth/callback` | — | Google возвращает человека сюда; страница «Готово» |
| GET | `/auth/session/<login_id>` | `Bearer <claim_token>` | 202 «ждём» / 200 пропуск / 404 «нет такого» |
| POST | `/enroll` | `Bearer <device_token>` | объявить кругу имя и **открытые** ключи устройства |
| GET | `/circle` | `Bearer` | устройства аккаунта: имя, вид, ключи, код сверки, «на связи» |
| GET | `/me` | `Bearer` | аккаунт и это устройство |
| POST | `/devices/<id>/revoke` | `Bearer` | отключить любое устройство круга (себя — это «Выйти») |
| DELETE | `/account` | `Bearer` | удалить всё моё немедленно |

Пропуск — непрозрачные 256 бит, в базе только SHA-256 от него. Не JWT: JWT нельзя отозвать, а
отзыв («потерял телефон») — то самое свойство, ради которого всё делается.

## Проверить руками

```bash
S=http://127.0.0.1:8080
START=$(curl -s -XPOST $S/auth/start -H 'Content-Type: application/json' -d '{"kind":"PHONE","name":"Пиксель"}')
echo "$START"                                    # login_id, claim_token, user_code, login_url
# открыть login_url в браузере, сверить код, войти через Google
curl -s "$S/auth/session/<login_id>" -H "Authorization: Bearer <claim_token>"   # 202 → 200
curl -s "$S/circle" -H "Authorization: Bearer <device_token>"
curl -s -XPOST "$S/devices/<id>/revoke" -H "Authorization: Bearer <device_token>"
```

## Запуск

```bash
python3 -m venv ~/point-server/venv && ~/point-server/venv/bin/pip install -r requirements.txt
set -a; . ./point-server.env; set +a        # образец — point-server.env.sample
~/point-server/venv/bin/python -m point_server        # 127.0.0.1:8080, access-log выключен
```

Снаружи сервер не виден: TLS терминирует Caddy с сертификатом Let's Encrypt — это срез #470, и до
него сервер щупается с самой машины. Логи: правило релея перенесено дословно — ни путей, ни имён,
ни адресов, `access_log=False`.

**Площадка та же, что у релея, — `35.185.31.106`** (решение владельца 04.08.2026). A-запись
`point.leerio.app` переставляется на неё владельцем; порт `80` нужен не под сайт, а под выдачу
сертификата (проверка ACME), `443` — под сам сервер, `8443` после переезда закрывается. Фаервол
GCP эти порты не блокирует — нужно, чтобы там слушала служба.

## Тесты

Без сети и без боевого сервера: вход по Google подменяется фейком того же шва, время — управляемыми
часами, база — файлом во временном каталоге.

```bash
python -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
cd relay && ../.venv/bin/python -m pytest tests -q
```

Гоняется в CI отдельной задачей `server` (`.github/workflows/ci.yml`).

---

# Point relay (#161 v2) — слепой почтовый ящик

A **blind store-and-forward mailbox** that makes the phone↔PC link reliable across firewalls,
changing IPs and different networks (LTE ↔ home Wi-Fi). Both devices connect **outbound** to it,
so neither needs an open inbound port. It never holds the pairing token or a plaintext object —
only ciphertext addressed by an opaque, token-derived mailbox id (`RelayCrypto` on the app side).

## Deployed on `35.185.31.106` (GCP project `leerio`)

- `relay.py` runs as an **unprivileged user process** (no root / no systemd) on **:8443**, TLS with
  a self-signed cert (IP SAN `35.185.31.106`), gated by a build-baked `X-Point-App` secret.
- Files under `~/point-relay/`: `relay.py`, `cert.pem` / `key.pem`, `secret`, `mbx/` (blobs), `start.sh`.
- Autostart: `start.sh` (in this dir) from cron — `*/2 * * * *` plus `@reboot`. It decides
  «already running?» **by the listening port**, not by process name.

  Why that matters (fixed 03.08.2026): the old check was
  `pgrep -f 'python3 .*point-relay/relay.py'`, and the cron line itself carried that very pattern
  in its arguments — so `pgrep` matched **the watchdog's own shell** and concluded the relay was
  alive. A relay that died stayed dead until the next reboot; the watchdog ran every two minutes
  and did nothing. Ловушка тихая: логи крона выглядят исправными.
- Verified locally: `health` / push → pull → ack / empty-after-ack / auth-reject all pass.

## The one thing that must be done in the cloud (done)

Правило фаервола создано владельцем 30.07.2026 — порт `8443` открыт, и «нет связи» с тех пор
означает **лежащий процесс**, а не закрытый порт. Отличить одно от другого можно ответом хоста:
закрытый фаервол молчит (таймаут), мёртвый процесс отвечает `connection refused`.

Историческая справка: the instance's gcloud lacks firewall scopes, so the relay port had to be
opened in the **GCP firewall of project `leerio`** (Console → VPC → Firewall → Create rule, or from a machine authed to leerio):

```
gcloud compute firewall-rules create point-relay \
  --project=leerio --direction=INGRESS --allow=tcp:8443 --source-ranges=0.0.0.0/0
```

Safe to open to `0.0.0.0/0`: the relay is TLS + app-secret + end-to-end encrypted, so it physically
cannot read the objects that cross it.

## Operate

- Logs: `~/point-relay/relay.log`
- Restart: `pkill -f point-relay/relay.py; ~/point-relay/start.sh`
- The app pins `cert.pem` (this dir) and sends `secret` as `X-Point-App`; both live in the app's
  **git-ignored** `local.properties` (`RELAY_APP_SECRET`, `RELAY_URL`).

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/health` | 200 `ok` (no auth) |
| POST | `/mbx/<id>` | body = ciphertext → 200 + `X-Blob-Id` (≤ 50 MB) |
| GET | `/mbx/<id>?wait=N` | oldest blob (+ `X-Blob-Id`) or 204 after N s long-poll |
| POST | `/mbx/<id>/ack` | `X-Blob-Id` header → 200 (delete; repeat ack still 200) |
| POST | `/d` | body = file (+ `X-Drop-Name` base64, `X-Drop-Mime`) → 200 + drop id |
| GET | `/d/<id>` | **no auth** — the file itself, for any browser (#388) |
| POST | `/u/<box>/open` | open a receiving box for 24 h (the phone does this) |
| GET | `/u/<box>` | **no auth** — a plain Russian upload page (#388) |
| POST | `/u/<box>` | **no auth** — `multipart/form-data` from that page → mailbox `<box>` |
| PUT | `/u/<box>?name=&mime=` | **no auth** — same, raw body (curl / scripts) |

All but `/health`, `/d/<id>` and `/u/<box>` require `X-Point-App: <secret>`.

### Drop: одной ссылкой в обе стороны (#388)

`/d` отдаёт файл человеку без Point; `/u` принимает файл ОТ него. Обе стороны платят одним и тем
же: у чужого браузера ключа пары устройств нет, поэтому **эти байты релей видит** — в отличие от
всего, что возят спаренные устройства. Пропуск — сам адрес (160 бит), срок — сутки.

Ящик приёма заводит телефон (`/u/<box>/open` с секретом): иначе запись без секрета позволяла бы
набивать диск ящиками, которых никто не ждёт. Файл ложится в тот же `mbx/<box>`, который телефон
уже слушает, кадром `[4 байта длины шапки][name=…\nmime=…][байты]` — тот же формат, что у пары
устройств, только без шифрования.

Проверить руками (`-k` — сертификат самоподписанный, он же пинится в приложении):

```bash
R=https://35.185.31.106:8443; S=<RELAY_APP_SECRET>; BOX=$(head -c 20 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=')
curl -sk -X POST -H "X-Point-App: $S" "$R/u/$BOX/open"      # завести ящик → ok
curl -sk "$R/u/$BOX" | head -5                               # страница приёма (HTML, по-русски)
curl -sk -F "f=@отчёт.pdf" "$R/u/$BOX" | grep -o Отправлено  # положить файл, как из браузера
curl -sk -H "X-Point-App: $S" "$R/mbx/$BOX?wait=5" -o frame.bin -D-   # телефон забирает кадр
```
