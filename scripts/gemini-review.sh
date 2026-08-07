#!/usr/bin/env bash
# Independent AI code review of the current branch via the Gemini CLI.
#
#   bash scripts/gemini-review.sh [base-ref]     # base defaults to origin/main
#
# Reads GEMINI_API_KEY from the environment or local.properties (git-ignored).
# Read-only (--approval-mode plan): Gemini never edits, only reviews. The model
# defaults to gemini-flash-latest (GEMINI_REVIEW_MODEL overrides) because the
# CLI's default gemini-2.5-pro is unavailable on new free-tier keys.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
BASE="${1:-origin/main}"
MODEL="${GEMINI_REVIEW_MODEL:-gemini-flash-latest}"

if [ -z "${GEMINI_API_KEY:-}" ]; then
  GEMINI_API_KEY="$(grep -E '^GEMINI_API_KEY=' "$ROOT/local.properties" 2>/dev/null | cut -d= -f2- || true)"
fi
[ -n "${GEMINI_API_KEY:-}" ] || { echo "GEMINI_API_KEY not set (put it in local.properties)"; exit 1; }
export GEMINI_API_KEY

git fetch -q origin main 2>/dev/null || true
DIFF="$(git diff "$BASE"...HEAD)"
[ -n "$DIFF" ] || { echo "No changes vs $BASE — nothing to review."; exit 0; }

read -r -d '' PROMPT <<'EOF' || true
Ты — старший ревьювер Kotlin/Android-проекта Point (Jetpack Compose, Hilt, чистая
архитектура: :core:model ← :core:flow ← {:data, :executors, :core:ui} ← :app).
Отревьюй git-diff, поданный на stdin. Особое внимание к инвариантам проекта:
- Capability (декларация «что») ≠ Realizer (поведение «как») — реализацию не тащить в UI/граф;
- Flow Graph выводится, не хранится; :core:model и :core:flow — без единого android.*;
- каждый side-effect за интерфейсом (сеть/файлы/фреймворк), в тестах — fakes;
- ошибки не глотать (sealed ActionResult); первый экран без I/O и без LLM.
Ищи: баги/краши, нарушения инвариантов, edge-cases, слабое/отсутствующее покрытие тестами,
дублирование, утечки ресурсов. Формат: краткий список проблем «severity (blocker/major/minor/nit)
— file:line — суть — как чинить». Если чисто — скажи прямо. Только ревью, код не переписывай.
EOF

printf '%s' "$DIFF" | gemini -m "$MODEL" --approval-mode plan --skip-trust -o text -p "$PROMPT"
