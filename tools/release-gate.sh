#!/usr/bin/env bash
# release-gate.sh — ворота перед публикацией: собрать начисто и проверить собранное.
#
# Зачем отдельно от release-scan.sh. Скан отвечает на вопрос «в ЭТОМ файле есть секрет?» — и
# только на него. Авария v0.2.0 случилась не потому, что скан ошибся, а потому, что публиковали
# не то, что собирали: к релизу руками приложили отладочный артефакт с живыми ключами. Ворота
# закрывают именно этот зазор — они сами собирают то, что будет опубликовано, и проверяют
# результат своей же сборки.
#
# Проход:
#   чистая копия → релизная сборка → скан репозитория → скан артефакта →
#   скан отладочных поверхностей → PASS / BLOCKED
#
# Любое сомнение — BLOCKED. «Не смогли проверить» здесь не равно «чисто»: непроверенное не
# публикуется, потому что цена ошибки — отозванный ключ и перевыложенный релиз.
#
# Использование:
#   bash tools/release-gate.sh              # полный проход, со сборкой
#   bash tools/release-gate.sh --no-build   # проверить уже собранное (быстрее; сборку не делает)
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
build=1
[ "${1:-}" = "--no-build" ] && build=0

blockers=()
block() { blockers+=("$1"); }
step() { printf '\n── %s\n' "$1"; }

# ── 1. Чистая копия ────────────────────────────────────────────────────────────────────────
#
# Незакоммиченное в рабочем дереве — это то, чего нет ни у кого, кроме автора: собранный из него
# артефакт невоспроизводим, и разбирать потом «что же там было» будет некому.
step "Чистая копия"
dirty="$(cd "$root" && git status --porcelain 2>/dev/null | grep -v '^?? ' || true)"
if [ -n "$dirty" ]; then
    block "рабочее дерево грязное — публикуется то, чего нет в истории:
$(printf '%s' "$dirty" | head -5)"
else
    echo "  чисто: HEAD $(cd "$root" && git rev-parse --short HEAD)"
fi

# ── 2. Релизная сборка ─────────────────────────────────────────────────────────────────────
#
# Собираем сами, а не берём готовое из outputs: лежащий там файл мог быть собран когда угодно,
# из чего угодно и с чем угодно внутри.
step "Релизная сборка"
if [ "$build" -eq 1 ]; then
    export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
    # Собирается и bundle: в Play уезжает именно он, и проверять надо то, что уедет,
    # а не его APK-двойника (#513).
    if (cd "$root" && ./gradlew --quiet clean :app:assembleRelease :app:bundleRelease >/tmp/release-gate-build.log 2>&1); then
        echo "  собрано начисто"
    else
        block "сборка не прошла — публиковать нечего (лог: /tmp/release-gate-build.log)"
    fi
else
    echo "  пропущена по --no-build: проверяется то, что лежит в outputs"
fi

apks=()
while IFS= read -r -d '' f; do apks+=("$f"); done < <(
    find "$root/app/build/outputs" -name '*.apk' -o -name '*.aab' 2>/dev/null | tr '\n' '\0'
)
release_apks=()
for f in "${apks[@]}"; do
    case "$f" in */release/*) release_apks+=("$f") ;; esac
done
[ "${#release_apks[@]}" -gt 0 ] || block "релизного артефакта нет — проверять нечего"

# ── 3. Скан репозитория ────────────────────────────────────────────────────────────────────
#
# Секрет в истории опаснее секрета в артефакте: артефакт отзывается перевыкладыванием, история —
# нет. Смотрим отслеживаемые файлы: `local.properties` git-ignored и в этот список не попадает.
step "Скан репозитория"
# Совпадения сверяются с тем же release-эталоном, что и артефакт: выдуманный ключ в тесте —
# законный житель репозитория, но вноситься он должен явно, одной строкой с причиной. Молча
# исключать целые каталоги нельзя: настоящий ключ, забытый в тесте, — точно такая же утечка.
tracked_hits="$(cd "$root" && git grep -hoIE 'AIza[0-9A-Za-z_-]{20,}|AQ\.[0-9A-Za-z_-]{20,}|gh[pousr]_[0-9A-Za-z]{20,}|sk-[A-Za-z0-9_-]{20,}|gsk_[0-9A-Za-z]{20,}|csk-[0-9A-Za-z_-]{20,}' -- \
    ':!tools/release-scan.sh' ':!tools/release-gate.sh' ':!docs/**' ':!**/*.md' 2>/dev/null | sort -u || true)"
unknown=()
while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    grep -qFx -- "$hit" "$here/release-scan-allowlist.txt" 2>/dev/null || unknown+=("$hit")
done <<< "$tracked_hits"
if [ "${#unknown[@]}" -gt 0 ]; then
    block "в отслеживаемых файлах строки, похожие на ключи и не внесённые в release-эталон:
$(printf '  %s\n' "${unknown[@]:0:5}" | cut -c1-100)"
else
    echo "  чисто"
fi
if (cd "$root" && git ls-files --error-unmatch local.properties >/dev/null 2>&1); then
    block "local.properties отслеживается git — там живут ключи"
else
    echo "  local.properties вне истории"
fi

# ── 4. Скан артефакта ──────────────────────────────────────────────────────────────────────
#
# Здесь же идёт сверка с фактическими значениями секретов (см. release-scan.sh): регулярка знает
# только те формы, которые в неё вписали, а `RELAY_APP_SECRET` — 48 символов без префикса —
# прошёл мимо неё и уехал в публичную сборку.
step "Скан артефакта"
if [ "${#release_apks[@]}" -gt 0 ]; then
    if bash "$here/release-scan.sh" "${release_apks[@]}"; then
        echo "  скан пройден"
    else
        block "скан артефакта не пройден (см. вывод выше)"
    fi
fi

# ── 5. Отладочные поверхности ──────────────────────────────────────────────────────────────
#
# Проверяется СОБРАННЫЙ манифест, а не исходники: `app/src/debug/AndroidManifest.xml` может быть
# сколь угодно правильным, а в артефакт компоненты попадут из другого варианта или из библиотеки.
step "Отладочные поверхности"
# Инструмент ищется там, где он бывает на всех трёх машинах: у владельца, на раннере и в
# контейнере. Один Windows-путь означал, что ворота нигде, кроме одной машины, не проходятся
# вовсе — и «непроверенное не публикуется» превращалось в «не публикуется ничего».
aapt=""
for candidate in \
    "${ANDROID_HOME:-}/build-tools" "${ANDROID_SDK_ROOT:-}/build-tools" \
    "$HOME/AppData/Local/Android/Sdk/build-tools" "$HOME/Android/Sdk/build-tools" \
    "$HOME/Library/Android/sdk/build-tools"
do
    [ -d "$candidate" ] || continue
    found="$(ls -d "$candidate"/*/aapt2 "$candidate"/*/aapt2.exe 2>/dev/null | sort | tail -1)"
    [ -n "$found" ] && { aapt="$found"; break; }
done

# Манифест бандла — не тот формат, что у APK: aapt2 его не читает, а имена компонентов лежат
# в нём строками. Разные носители одного и того же вопроса — разный способ спросить.
#
# Печатаемые куски достаются `tr`, а не `strings`: последнего нет в Git Bash у владельца, и
# ворота на его машине блокировали бандл всегда — той же бедой, что чинилась выше для aapt2.
# Проверка, которая не проходится там, где публикуют, не проверяет ничего.
manifest_of() {
    case "$1" in
        *.aab)
            local out; out="$(mktemp -d)"
            (cd "$out" && unzip -qo "$1" base/manifest/AndroidManifest.xml >/dev/null 2>&1) || return 1
            LC_ALL=C tr -cs '[:print:]' '\n' < "$out/base/manifest/AndroidManifest.xml" 2>/dev/null
            rm -rf "$out"
            ;;
        *) [ -n "$aapt" ] || return 1; "$aapt" dump xmltree --file AndroidManifest.xml "$1" 2>/dev/null ;;
    esac
}

if [ "${#release_apks[@]}" -gt 0 ]; then
    for f in "${release_apks[@]}"; do
        dump="$(manifest_of "$f" || true)"
        [ -n "$dump" ] || { block "$(basename "$f"): манифест не читается — непроверенное не публикуется"; continue; }
        printf '%s' "$dump" | grep -q 'SandboxActivity\|PrintProbeActivity' &&
            block "$(basename "$f"): в релизе отладочная поверхность (Sandbox/PrintProbe)"
        printf '%s' "$dump" | grep -q 'debuggable' &&
            block "$(basename "$f"): манифест объявляет debuggable"
        echo "  $(basename "$f"): отладочных компонентов нет"
    done
else
    block "релизных артефактов нет — отладочные поверхности не проверены"
fi

# ── Вердикт ────────────────────────────────────────────────────────────────────────────────
printf '\n'
if [ "${#blockers[@]}" -gt 0 ]; then
    echo "BLOCKED (${#blockers[@]}):"
    for b in "${blockers[@]}"; do echo "  ✗ $b"; done
    exit 1
fi
echo "PASS — можно выкладывать"
