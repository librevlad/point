#!/usr/bin/env bash
# Выложить сервер Point на боевую машину и убедиться, что он поднялся.
#
# До 10.08.2026 деплоя не было вовсе: код правился в репозитории, а на сервере лежала копия
# от 5 августа. Из-за этого страница приёма файла (#723) неделю жила только в git, а приём
# отвечал 500 на каждую загрузку — `python-multipart` был объявлен в requirements и не
# установлен. Скрипт закрывает ровно этот разрыв: объявленное и работающее сходятся одной
# командой, а не памятью человека.
#
#   bash tools/deploy-server.sh              # выложить и перезапустить
#   bash tools/deploy-server.sh --check      # только сравнить, что разошлось
#
# Доступ: ssh-ключ пользователя `user` на боевой машине, оттуда sudo. Сама служба живёт под
# `librevlad` — файлы кладутся от его имени, чтобы сторож в кроне (`*/2 * * * * start.sh`)
# продолжал поднимать её после перезагрузки.
set -eu

HOST="${POINT_SERVER_HOST:-user@35.185.31.106}"
DST="${POINT_SERVER_DIR:-/home/librevlad/point-server}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/relay"
CHECK=false
[ "${1:-}" = "--check" ] && CHECK=true

# Отпечаток выкладываемого кода (#1231): коммит последней правки серверных файлов. Именно его
# сервер потом называет в /health, а прогон CI сверяет с main — расхождение видно само, без
# чужой памяти. Правки, не попавшие ни в один коммит, названы прямо: выкладывать их можно,
# делать вид, что на сервере main, — нельзя.
# Имя нарочно не `STAMP`: так на той стороне зовётся отметка времени для имени резервной копии.
DEPLOYED=$(git -C "$ROOT" log -1 --format=%H -- relay/point_server relay/requirements.txt relay/point-server-start.sh 2>/dev/null || true)
[ -n "$DEPLOYED" ] || DEPLOYED="неизвестен"
if [ -n "$(git -C "$ROOT" status --porcelain -- relay/point_server relay/requirements.txt relay/point-server-start.sh 2>/dev/null)" ]; then
  DEPLOYED="$DEPLOYED+правки"
fi

say() { printf '→ %s\n' "$1"; }

say "что разошлось"
# `sha256sum` на Windows помечает бинарный режим звёздочкой (`hash *path`), на сервере — нет:
# без нормализации разошедшимся выглядит каждый файл.
hashes() { awk '{name=$2; sub(/^\*/, "", name); print substr($1,1,16), name}' | sort -k2; }

LOCAL=$(cd "$SRC" && sha256sum point_server/*.py requirements.txt | hashes)
REMOTE=$(ssh -o BatchMode=yes "$HOST" "sudo bash -c 'cd $DST && sha256sum point_server/*.py requirements.txt'" 2>/dev/null | hashes || true)
DIFF=$(diff <(echo "$LOCAL") <(echo "$REMOTE") || true)
if [ -z "$DIFF" ]; then
  echo "  сервер и репозиторий совпадают"
  $CHECK && exit 0
else
  echo "$DIFF" | sed 's/^/  /'
fi
$CHECK && exit 0

say "сборка посылки"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
tar czf "$TMP/point-deploy.tgz" -C "$SRC" \
  $(cd "$SRC" && ls point_server/*.py) requirements.txt point-server-start.sh

say "загрузка"
scp -q -o BatchMode=yes "$TMP/point-deploy.tgz" "$HOST:/tmp/point-deploy.tgz"

say "выкладка и перезапуск"
# Пароль/ключи (`point-server.env`) и данные (`blobs`, `point.db`) не трогаются вовсе.
# Строка "uvicorn point_server.main" нарочно собирается из кусков: `pkill -f` по целой строке
# убивал сам ssh-сеанс, в чьей команде она встречалась (найдено при первом деплое).
ssh -o BatchMode=yes "$HOST" "set -e
  STAMP=\$(date +%Y%m%d-%H%M%S)
  sudo -u librevlad tar czf $DST/backup-\$STAMP.tgz -C $DST point_server requirements.txt start.sh
  rm -rf /tmp/point-new && mkdir -p /tmp/point-new && tar xzf /tmp/point-deploy.tgz -C /tmp/point-new
  for f in /tmp/point-new/point_server/*.py; do
    sudo install -o librevlad -g librevlad -m 644 \"\$f\" $DST/point_server/\$(basename \$f)
  done
  sudo install -o librevlad -g librevlad -m 644 /tmp/point-new/requirements.txt $DST/requirements.txt
  sudo install -o librevlad -g librevlad -m 755 /tmp/point-new/point-server-start.sh $DST/start.sh
  printf '%s\n' '$DEPLOYED' | sudo -u librevlad tee $DST/deployed.txt >/dev/null
  sudo rm -rf $DST/point_server/__pycache__
  sudo -u librevlad bash -c 'cd $DST && ./.venv/bin/pip install -q -r requirements.txt'
  sudo pkill -f \"uvicorn point_\"'server.main' || true
  sleep 2
  sudo -u librevlad setsid nohup $DST/start.sh >/dev/null 2>&1 < /dev/null &
  sleep 10
  ss -ltn | grep -q '127.0.0.1:8080' || { echo 'сервер не поднялся'; sudo tail -20 $DST/server.log; exit 1; }
  rm -rf /tmp/point-new /tmp/point-deploy.tgz"

say "проверка снаружи"
ANSWER=$(curl -s -m 20 -w '\n%{http_code}' https://point.leerio.app/health)
STATUS=${ANSWER##*$'\n'}
SAYS=${ANSWER%$'\n'*}
[ "$STATUS" = "200" ] || { echo "  /health отвечает $STATUS" >&2; exit 1; }
echo "  /health 200 — сервер живой"
# Сервер обязан назвать ровно тот код, который сейчас выложили: иначе выкладка «прошла», а
# что на машине — снова неизвестно (#1231).
[ "${SAYS#ok }" = "$DEPLOYED" ] || { echo "  сервер называет не тот код: ${SAYS#ok } вместо $DEPLOYED" >&2; exit 1; }
echo "  сервер называет выложенный код: $DEPLOYED"
