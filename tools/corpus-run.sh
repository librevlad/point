#!/usr/bin/env bash
# Прогон корпуса через Point на эмуляторе (#262), расширенный для движка v3:
# на кадр — дословный текст, СЛОЙ АТОМОВ (atoms-last.tsv) и журнал метаданных (flow-snapshot).
# Ничего не выдумывает: в отчёт идёт только то, что вернуло устройство.
set -u
export ANDROID_HOME="${ANDROID_HOME:-C:/Users/User/AppData/Local/Android/Sdk}"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
A="$ANDROID_HOME/platform-tools/adb.exe"
OUT="$1"; shift
mkdir -p "$OUT"
: > "$OUT/report.md"

for img in "$@"; do
  name=$(basename "$img"); name="${name%.*}"
  echo "### $name" >> "$OUT/report.md"

  "$A" push "$img" /data/local/tmp/c.jpg > /dev/null 2>&1
  "$A" shell "run-as com.point cp /data/local/tmp/c.jpg files/c.jpg"
  "$A" shell am force-stop com.point > /dev/null 2>&1
  "$A" shell rm -f /sdcard/Android/data/com.point/files/atoms-last.tsv > /dev/null 2>&1
  "$A" logcat -c
  "$A" shell am start -a android.intent.action.SEND -t image/jpeg \
    --eu android.intent.extra.STREAM "file:///data/user/0/com.point/files/c.jpg" \
    -n com.point/.ShareActivity > /dev/null 2>&1

  # OCR на софтверном эмуляторе идёт десятками секунд — ждём строку в логе, не «на глаз».
  line=""
  for _ in $(seq 1 40); do
    line=$("$A" logcat -d 2>/dev/null | grep -F "OCR done" | tail -1)
    [ -n "$line" ] && break
    sleep 5
  done
  echo "\`${line:-нет строки OCR в логе}\`" >> "$OUT/report.md"

  sleep 12  # дать энричерам дописать метаданные и журнал

  # Слой атомов — фикстурный канал движка v3 (#257): слово+bbox+conf дословно.
  "$A" exec-out cat /sdcard/Android/data/com.point/files/atoms-last.tsv > "$OUT/$name.atoms.tsv" 2>/dev/null
  [ -s "$OUT/$name.atoms.tsv" ] || rm -f "$OUT/$name.atoms.tsv"

  # Дословный текст из scratch (самый свежий .txt).
  f=$("$A" shell "run-as com.point ls -t files/scratch 2>/dev/null" | tr -d '\r' | grep -m1 '[.]txt$')
  if [ -n "$f" ]; then
    "$A" exec-out run-as com.point cat "files/scratch/$f" > "$OUT/$name.txt" 2>/dev/null
    echo '```' >> "$OUT/report.md"
    head -c 1200 "$OUT/$name.txt" >> "$OUT/report.md"
    echo '' >> "$OUT/report.md"
    echo '```' >> "$OUT/report.md"
  else
    echo "_текста нет (гейт мусора или OCR не сработал)_" >> "$OUT/report.md"
  fi

  # Журнал флоу: метаданные объекта — вход метрики «действие без правок» (actionReadiness).
  "$A" exec-out run-as com.point cat files/flow-snapshot.json > "$OUT/$name.flow.json" 2>/dev/null
  [ -s "$OUT/$name.flow.json" ] || rm -f "$OUT/$name.flow.json"

  "$A" exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
  echo "" >> "$OUT/report.md"
done
echo "DONE $(date)" >> "$OUT/report.md"
