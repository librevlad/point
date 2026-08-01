#!/usr/bin/env bash
# Прогон корпуса через Point на устройстве (#262).
#
# Меряет ДВЕ величины, потому что это два разных обещания человеку:
#   1) что Point понял САМ, без сети — режим по умолчанию;
#   2) что он понимает после ОДНОГО явного тапа по действию — режим `--tap "Название"`.
# Смешивать их в одно число нельзя.
#
# На кадр снимается дословно: текст из scratch, слой атомов (слово+bbox+уверенность),
# журнал метаданных объекта (вход метрики) и снимок экрана. Ничего не выдумывает.
set -u
export ANDROID_HOME="${ANDROID_HOME:-C:/Users/User/AppData/Local/Android/Sdk}"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
A="$ANDROID_HOME/platform-tools/adb.exe"

TAP=""
if [ "${1:-}" = "--tap" ]; then TAP="$2"; shift 2; fi
OUT="$1"; shift
mkdir -p "$OUT"
: > "$OUT/report.md"

# Софтверный эмулятор регулярно вешает поверх приложения диалог «System UI isn't responding»
# (заметка про эмулятор в #257): он перехватывает тапы, и замер молча меряет пустоту. Поэтому
# перед тапом помеха убирается, и это записывается в отчёт, а не проглатывается.
dismiss_anr() {
  local dump b n x1 y1 x2 y2
  dump=$("$A" exec-out cat /sdcard/ui.xml 2>/dev/null | tr -d '\r')
  case "$dump" in
    *"isn't responding"*|*"не отвечает"*) ;;
    *) return 1 ;;
  esac
  b=$(printf '%s' "$dump" | tr '>' '\n' | grep -F 'text="Wait"' \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
  [ -n "$b" ] || return 1
  n=$(printf '%s' "$b" | grep -o '[0-9]\+')
  x1=$(echo "$n" | sed -n 1p); y1=$(echo "$n" | sed -n 2p)
  x2=$(echo "$n" | sed -n 3p); y2=$(echo "$n" | sed -n 4p)
  "$A" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
  sleep 2
  return 0
}

# Тап по кнопке ПО ИМЕНИ, а не по координатам: список действий растёт по мере обогащения,
# и зашитые координаты попадают в соседнюю кнопку — так уже случалось вживую.
tap_by_text() {
  local label="$1"
  for _ in $(seq 1 6); do
    "$A" shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    if dismiss_anr; then
      echo "_помеха эмулятора убрана_" >> "$OUT/report.md"
      "$A" shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    fi
    local bounds
    bounds=$("$A" exec-out cat /sdcard/ui.xml 2>/dev/null | tr -d '
' | tr '>' '
'       | grep -F "text=\"$label\"" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
    if [ -n "$bounds" ]; then
      local nums x1 y1 x2 y2
      nums=$(printf '%s' "$bounds" | grep -o '[0-9]\+')
      x1=$(echo "$nums" | sed -n 1p); y1=$(echo "$nums" | sed -n 2p)
      x2=$(echo "$nums" | sed -n 3p); y2=$(echo "$nums" | sed -n 4p)
      "$A" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
      return 0
    fi
    "$A" shell input swipe 540 1700 540 900 300   # кнопка может быть ниже сгиба
    sleep 1
  done
  return 1
}

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

  if [ -n "$TAP" ]; then
    if tap_by_text "$TAP"; then
      echo "_тап «$TAP»_" >> "$OUT/report.md"
      sleep 45   # сетевое действие — десятки секунд
    else
      echo "_кнопка «$TAP» не найдена на экране_" >> "$OUT/report.md"
    fi
  fi

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
