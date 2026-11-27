#!/usr/bin/env bash
# Ключи пробных AI-действий (#477) — в окружение службы сервера Point.
#
# Проба отвечает через Groq и Cloudflare Workers AI: оба письменно обещают не учиться на
# присланном (relay/point_server/ai.py). Ключи берутся из local.properties, недостающие — из
# хранилища ключей JARVIS ~/.secrets/keys.env, и уходят на сервер через stdin ssh: ни на экране,
# ни в командной строке, ни в журнале их нет. На сервере заменяются только строки POINT_AI_*.
#
#   bash tools/server-ai-keys.sh     # затем bash tools/deploy-server.sh — служба перечитает окружение
set -eu

# `bash` в PowerShell — это Linux из WSL: у него свой ssh, без ключа к серверу и без его
# отпечатка («Host key verification failed», 27.11.2026). Скрипт живёт в Git Bash.
if grep -qi microsoft /proc/version 2>/dev/null; then
  echo "это bash из WSL — у него нет ключа к серверу. Запустите в PowerShell так:"
  echo '  & "C:\Program Files\Git\bin\bash.exe" tools/server-ai-keys.sh'
  exit 2
fi

HOST="${POINT_SERVER_HOST:-user@35.185.31.106}"
DST="${POINT_SERVER_DIR:-/home/librevlad/point-server}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

val() {
  local v
  v=$(grep -E "^$1=" "$ROOT/local.properties" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r' || true)
  [ -n "$v" ] || v=$(grep -E "^$1=" "$HOME/.secrets/keys.env" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r' || true)
  printf '%s' "$v"
}

G=$(val GROQ_API_KEY)
C=$(val CLOUDFLARE_API_KEY)
A=$(val CLOUDFLARE_ACCOUNT_ID)
if [ -z "$G" ] && { [ -z "$C" ] || [ -z "$A" ]; }; then
  echo "нет ни ключа Groq, ни ключа и номера аккаунта Cloudflare — пробе нечем отвечать"
  exit 2
fi

printf 'POINT_AI_GROQ_KEY=%s\nPOINT_AI_CLOUDFLARE_KEY=%s\nPOINT_AI_CLOUDFLARE_ACCOUNT=%s\n' "$G" "$C" "$A" |
  ssh -o BatchMode=yes "$HOST" "sudo -u librevlad sh -c 'umask 077; f=$DST/point-server.env; t=\$(mktemp); grep -v ^POINT_AI_ \"\$f\" > \"\$t\" || true; cat >> \"\$t\"; cat \"\$t\" > \"\$f\"; rm -f \"\$t\"'"

say() { [ -n "$1" ] && echo есть || echo нет; }
echo "ключи пробы на сервере: Groq — $(say "$G"), Cloudflare — $(say "$C$A")"
echo "дальше: bash tools/deploy-server.sh (перезапуск службы перечитает окружение)"
