#!/usr/bin/env bash
# release-scan.sh — проверка файлов, которые вот-вот станут публичным релизом (#306).
#
# ЭТО ПОСЛЕДНИЙ РУБЕЖ, А НЕ ОСНОВНАЯ ЗАЩИТА. Основная защита — сборкой:
#   * release-вариант физически не читает local.properties (data/build.gradle.kts, #305);
#   * релизный workflow (.github/workflows/release.yml) собирает на чистом раннере,
#     где local.properties нет вовсе, и debug-вариантов не собирает.
# Скан существует потому, что в v0.2.0 в публичный релиз руками попал debug-APK
# с живыми ключами: он ловит класс «в публикуемых файлах оказалось не то» — когда
# основные защиты уже обойдены (например, ассет к релизу приложили вручную).
#
# Использование:
#   bash tools/release-scan.sh [--content-only] [--allowlist файл] <файл> [<файл>...]
#
#   --content-only   проверять только содержимое (правила 1–2 ниже не применяются).
#                    Только для сборок, которые НЕ публикуются: например, CI-APK,
#                    который на чистом раннере по построению подписан отладочным
#                    fallback-ключом. Публикуемые файлы сканируй БЕЗ этого флага.
#   --allowlist f    взять release-эталон из другого файла (нужно тестам скрипта).
#
# Выход 0 — чисто, публиковать можно. Выход 1 — публиковать нельзя, причины в выводе.
# Найденные значения печатаются УСЕЧЁННО: лог CI не должен сам стать утечкой.
#
# Правила (одна реализация для CI и рук — вторая копия разошлась бы молча):
#   1) файл с «debug» в имени публиковать нельзя;
#   2) APK/AAB с отладочным сертификатом (CN=Android Debug — строка лежит в самом
#      файле и при v1-подписи в META-INF, и в блоке подписи v2/v3) публиковать нельзя;
#   3) внутри артефакта — включая вложенные apk/aab/jar/zip/msi/cab, их содержимое
#      разворачивается рекурсивно, потому что grep по сжатым байтам «чист» всегда —
#      не должно быть строк, похожих на секреты: AIza… (Google), gh[pousr]_… (GitHub),
#      sk-… (OpenAI-стиль; покрывает и sk-or-… OpenRouter). Каждое совпадение сверяется
#      с release-эталоном (tools/release-scan-allowlist.txt): известная безобидная
#      строка — пропускается, всё остальное — отказ. Вложенный архив узнаётся по
#      расширению, а у файлов без расширения — по магическим байтам: 7-Zip раскрывает
#      jpackage-MSI в блобы fileXXXX без имён, и jar'ы внутри иначе остались бы
#      непросмотренными (проверено на настоящем MSI: 42 jar-блоба из 187).
#   Развернуть вложенный архив нечем или вложенность глубже ожидаемой — тоже отказ:
#   «не смогли проверить» не равно «чисто».
set -euo pipefail

SECRET_RX='AIza[0-9A-Za-z_-]{20,}|gh[pousr]_[0-9A-Za-z]{20,}|sk-[A-Za-z0-9_-]{20,}'
MAX_DEPTH=4 # msi → cab → jar → содержимое; глубже наши артефакты не вложены

here="$(cd "$(dirname "$0")" && pwd)"
allowlist_file="$here/release-scan-allowlist.txt"
content_only=0
files=()
while [ $# -gt 0 ]; do
    case "$1" in
        --content-only) content_only=1; shift ;;
        --allowlist) allowlist_file="${2:?--allowlist требует файл}"; shift 2 ;;
        *) files+=("$1"); shift ;;
    esac
done
[ ${#files[@]} -gt 0 ] || { echo "Использование: bash tools/release-scan.sh [--content-only] <файл>..." >&2; exit 2; }

# Эталон: точные безобидные строки, по одной на строку; # — комментарий.
allow=""
[ -f "$allowlist_file" ] && allow=$(grep -v '^[[:space:]]*#' "$allowlist_file" | grep -v '^[[:space:]]*$' || true)
is_allowed() { [ -n "$allow" ] && printf '%s\n' "$allow" | grep -qFx -- "$1"; }

# 7-Zip: единственный инструмент, который читает и zip-семейство, и msi/cab.
SEVENZ=""
for c in 7z 7za "/c/Program Files/7-Zip/7z.exe"; do
    if command -v "$c" >/dev/null 2>&1; then SEVENZ="$c"; break; fi
done

failures=()
fail() { failures+=("$1"); }
redact() { local s="$1"; printf '%s… (%d симв.)' "${s:0:8}" "${#s}"; }

# Архив узнаётся по расширению; файл БЕЗ расширения — по магическим байтам
# (PK\x03\x04 = zip-семейство, MSCF = cab). Байты смотрим только у безрасширенных:
# после вскрытия jar'ов в дереве десятки тысяч *.class, и по одному спавну на файл
# скан стал бы часами.
looks_like_archive() { # $1 файл; 0 = да
    local b="${1##*/}" sig
    case "${b,,}" in
        *.apk | *.aab | *.jar | *.zip | *.msi | *.cab) return 0 ;;
        *.*) return 1 ;;
    esac
    sig=$(head -c 4 -- "$1" 2>/dev/null | od -An -tx1 | tr -d ' \n')
    [ "$sig" = "504b0304" ] || [ "$sig" = "4d534346" ]
}

extract() { # $1 архив → $2 каталог; 0 = развернули
    local f="$1" dest="$2" b rc
    b="${f##*/}"
    mkdir -p "$dest"
    if [ -n "$SEVENZ" ]; then # 7-Zip сам определяет формат, имя ему не нужно
        set +e; "$SEVENZ" x -y -o"$dest" "$f" >/dev/null 2>&1; rc=$?; set -e
        [ "$rc" -le 1 ] && return 0 # 1 = предупреждения, содержимое извлечено
    fi
    case "${b,,}" in
        *.msi | *.cab) return 1 ;; # msi/cab читает только 7-Zip
    esac
    if command -v unzip >/dev/null 2>&1; then # zip-семейство и zip-магия
        set +e; unzip -oqq "$f" -d "$dest" >/dev/null 2>&1; rc=$?; set -e
        [ "$rc" -le 1 ] && return 0
    fi
    return 1
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

i=0
for f in "${files[@]}"; do
    i=$((i + 1))
    name="$(basename "$f")"
    [ -f "$f" ] || { fail "$name: файла нет — сканировать нечего"; continue; }

    # 1) Имя: «debug» в публикуемом файле — сразу отказ, ровно авария v0.2.0.
    if [ "$content_only" -eq 0 ] && printf '%s' "$name" | grep -qi debug; then
        fail "$name: «debug» в имени — отладочные артефакты не публикуются"
    fi

    # 2) Подпись: отладочный сертификат в APK/AAB.
    case "$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]')" in
        *.apk|*.aab)
            if [ "$content_only" -eq 0 ] && grep -aq "Android Debug" "$f"; then
                fail "$name: подписан отладочным сертификатом (CN=Android Debug) — такое не публикуется; подпиши релизным ключом"
            fi
            ;;
    esac

    # Копия для разворачивания: сырые байты тоже сканируются (лежат в том же дереве).
    # Индекс в имени каталога: два файла с одинаковым именем не должны затереть друг друга.
    mkdir -p "$work/$i.d"
    cp -- "$f" "$work/$i.d/$name"
done

# 3) Развернуть всё вложенное. Развёрнутый архив переименовывается в *.raw:
#    следующий проход его не трогает, а сырые байты остаются в скане.
find_archives() { # заполняет archives[] непросмотренными архивами в $work
    archives=()
    local c
    while IFS= read -r -d '' c; do
        if looks_like_archive "$c"; then archives+=("$c"); fi
    done < <(find "$work" -type f ! -name '*.raw' ! -name '*.skipped' -print0)
    return 0 # статус последней проверки — не статус функции: set -e убил бы скрипт молча
}
depth=0
while [ "$depth" -lt "$MAX_DEPTH" ]; do
    find_archives
    [ "${#archives[@]}" -eq 0 ] && break
    for a in "${archives[@]}"; do
        if extract "$a" "$a.x"; then
            mv -- "$a" "$a.raw"
        else
            fail "$(basename "$a"): не смогли развернуть — установи 7-Zip (или unzip для zip-семейства); непроверенное не публикуется"
            mv -- "$a" "$a.skipped"
        fi
    done
    depth=$((depth + 1))
done
find_archives
[ "${#archives[@]}" -gt 0 ] && fail "вложенность архивов глубже $MAX_DEPTH уровней — так не собирается ни один наш артефакт; проверь руками: ${archives[0]}"

# Скан секретных форм по всему дереву (grep -a: бинарное читается как текст).
matches=$(cd "$work" && grep -aroE "$SECRET_RX" . 2>/dev/null || true)
if [ -n "$matches" ]; then
    declare -A seen=()
    while IFS= read -r line; do
        m="${line##*:}"
        p="${line%:"$m"}"; p="${p%:}"; p="${p#./}"
        is_allowed "$m" && continue
        key="$m"
        if [ -z "${seen[$key]:-}" ]; then
            seen[$key]=1
            fail "секретная форма $(redact "$m") — в $p; нет в release-эталоне (tools/release-scan-allowlist.txt) → публиковать нельзя. Живой ключ отзывается, безобидную строку можно внести в эталон."
        fi
    done <<< "$matches"
fi

echo
if [ "${#failures[@]}" -gt 0 ]; then
    echo "release-scan: ПУБЛИКОВАТЬ НЕЛЬЗЯ (${#failures[@]}):"
    for r in "${failures[@]}"; do echo "  ✗ $r"; done
    exit 1
fi
echo "release-scan: чисто (${#files[@]} файл(ов); скан — последний рубеж, основная защита — сборка без ключей)"
