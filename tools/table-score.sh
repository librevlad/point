#!/usr/bin/env bash
# Счёт таблицы ПО РЕЗУЛЬТАТУ действия «В Excel» (#262).
#
# Схему готовности таблице завести нельзя — честного факта «в документе есть таблица» не
# существует, и такая схема считала бы пустую готовность. Поэтому меряется файл, который человек
# открыл: сколько строк документа в нём нашлось, сколько потерялось, сколько придумано, какая доля
# сверенных ячеек совпала с эталоном и — главное — сколько расхождений прошло МОЛЧА, без ⚠.
#
# `.xlsx` — это zip с `xl/worksheets/sheet1.xml`, поэтому библиотек не нужно: unzip + awk достают
# ячейки. Но СЧИТАЕТ метрику Kotlin (`:core:flow` → `scoreTable`), а не awk: у числа обязана быть
# одна реализация, и она же под тестами. Вторая копия правил в шелле разошлась бы с первой молча —
# ровно та болезнь, от которой метрика и лечит.
#
#   bash tools/table-score.sh out/23.xlsx tools/corpus/23.expected.tsv [out/report.md]
#
# Рядом с .xlsx остаётся `.tsv` — дословно извлечённая таблица: улика, по которой можно проверить
# сам счёт, а не верить ему на слово.
set -u
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# Цвет заливки, которым `OoxmlSpreadsheetWriter` показывает человеку «модель не уверена»: знака «⚠»
# в тексте ячейки нет, он снимается писателем и живёт СТИЛЕМ (см. `xlsx-to-tsv.awk`). Значение
# продублировано здесь сознательно и не молча: `OoxmlSpreadsheetWriterTest` держит его контрактом с
# этим харнессом и падает, если палитра уедет.
FLAG_FILL=FFFFD199

XLSX="${1:-}"
EXPECTED="${2:-}"
REPORT="${3:-}"
ROOT=$(cd "$(dirname "$0")/.." && pwd)

if [ -z "$XLSX" ] || [ -z "$EXPECTED" ]; then
  echo "нужно: bash tools/table-score.sh <файл.xlsx> <эталон.tsv> [отчёт.md]" >&2
  exit 2
fi
[ -s "$XLSX" ] || { echo "нет файла таблицы: $XLSX" >&2; exit 1; }
[ -s "$EXPECTED" ] || { echo "нет эталона: $EXPECTED" >&2; exit 1; }

# Дальше счёт идёт из корня репозитория (там живёт ./gradlew), поэтому относительные пути,
# переданные из чужого каталога, сначала становятся абсолютными — иначе они молча уехали бы.
abs() { case "$1" in /*|[A-Za-z]:*) printf '%s' "$1" ;; *) printf '%s/%s' "$PWD" "$1" ;; esac; }
XLSX=$(abs "$XLSX")
EXPECTED=$(abs "$EXPECTED")
[ -z "$REPORT" ] || REPORT=$(abs "$REPORT")

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
if ! unzip -p "$XLSX" xl/worksheets/sheet1.xml > "$WORK/sheet1.xml" 2>/dev/null || [ ! -s "$WORK/sheet1.xml" ]; then
  echo "в $XLSX нет xl/worksheets/sheet1.xml — это не таблица" >&2
  exit 1
fi
# Общая строковая таблица появляется, когда файл пересохранён Excel'ом; Point пишет строки прямо
# в ячейку. Нет — не беда, но молча выдать пустую таблицу вместо непрочитанной нельзя.
unzip -p "$XLSX" xl/sharedStrings.xml > "$WORK/sharedStrings.xml" 2>/dev/null || : > "$WORK/sharedStrings.xml"
# Палитра: по ней «⚠» возвращается из стиля в текст. Её потеря тихо превратила бы все честные
# предупреждения в молчаливые расхождения — то есть испортила бы главное число метрики в худшую
# сторону, ничем себя не выдав. Поэтому оба случая говорятся словами в отчёт.
unzip -p "$XLSX" xl/styles.xml > "$WORK/styles.xml" 2>/dev/null || : > "$WORK/styles.xml"
WARN=""
if [ ! -s "$WORK/styles.xml" ]; then
  WARN="_в .xlsx нет xl/styles.xml — пометки ⚠ восстанавливать нечем, все расхождения посчитаны молчаливыми_"
elif ! grep -qi "$FLAG_FILL" "$WORK/styles.xml"; then
  WARN="_в xl/styles.xml нет заливки $FLAG_FILL — палитра писателя разошлась с харнессом (tools/table-score.sh), пометки ⚠ не восстановлены_"
fi
[ -z "$WARN" ] || echo "$WARN" >&2

TSV="${XLSX%.*}.tsv"
awk -v flagfill="$FLAG_FILL" -f "$ROOT/tools/xlsx-to-tsv.awk" \
  "$WORK/sharedStrings.xml" "$WORK/styles.xml" "$WORK/sheet1.xml" > "$TSV" || {
  echo "не разобрал sheet1.xml" >&2
  exit 1
}

OUT="$REPORT"
if [ -z "$OUT" ]; then OUT="$WORK/report.md"; fi
: > "$OUT.part"

# Пути к JVM идут в виде «C:/…», а не «/c/…»: MSYS-путь Java прочитает как папку на текущем диске
# и молча не найдёт файл. Преобразование явное, потому что MSYS_NO_PATHCONV его выключил.
win() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
if ! (cd "$ROOT" && ./gradlew --console=plain -q :core:flow:scoreTable \
  -Ptable="$(win "$TSV")" -Pexpected="$(win "$EXPECTED")" -Preport="$(win "$OUT.part")") > "$WORK/gradle.log" 2>&1; then
  echo "_счёт не посчитан: не поднялся ./gradlew (JAVA_HOME=$JAVA_HOME); таблица снята в ${TSV}_" >> "$OUT"
  echo "счёт не посчитан — таблица снята дословно в $TSV" >&2
  tail -20 "$WORK/gradle.log" >&2
  exit 1
fi
# Предупреждение идёт ПЕРЕД числами: оно говорит, чему в них верить нельзя.
[ -z "$WARN" ] || echo "$WARN" >> "$OUT"
cat "$OUT.part" >> "$OUT"
rm -f "$OUT.part"
[ -n "$REPORT" ] || cat "$OUT"
