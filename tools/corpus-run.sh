#!/usr/bin/env bash
# Прогон корпуса через Point на устройстве (#262).
#
# Меряет ДВЕ величины, потому что это два разных обещания человеку:
#   1) что Point понял САМ, без сети — режим по умолчанию;
#   2) что он понимает после ОДНОГО явного тапа по действию — режим `--tap "Название"`.
# Смешивать их в одно число нельзя.
#
# Третий режим — `--table <эталон>`: кадр с таблицей меряется не готовностью полей, а РЕЗУЛЬТАТОМ.
# После тапа по «В Excel» харнесс забирает готовый .xlsx с устройства и считает по нему числа
# метрики (`tools/table-score.sh`): нашлось строк, потеряно, лишних, доля сверенных ячеек и —
# главное — сколько расхождений прошло молча. Схемы готовности у таблиц нет и быть не может.
#
#   bash tools/corpus-run.sh --tap "В Excel" --table tools/corpus/23.expected.tsv out 23.jpg
#
# На кадр снимается дословно: текст из scratch, слой атомов (слово+bbox+уверенность),
# журнал метаданных объекта (вход метрики) и снимок экрана. Ничего не выдумывает.
set -u
export ANDROID_HOME="${ANDROID_HOME:-C:/Users/User/AppData/Local/Android/Sdk}"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
A="$ANDROID_HOME/platform-tools/adb.exe"

TAP=""
TABLE=""
# Сколько ждать сам .xlsx после тапа «В Excel». Не «на глаз»: живой кадр 23 строился ~2.5 минуты,
# и предел взят с запасом. Переопределяется переменной окружения, потому что кадры разной тяжести.
TABLE_WAIT="${TABLE_WAIT:-300}"
# Сколько ждать конца чтения страницы. Предел был зашит числом 200 с, и на тяжёлых фотографиях
# (3 МБ, доворот, поиск табло) прогон объявлял «строки OCR в логе нет» там, где чтение ещё шло, —
# то есть отвечал «не смог» вместо «не успел». Это разные ответы, и путать их метрике нельзя.
# Значение переопределяется окружением: скорость машины к качеству чтения отношения не имеет.
OCR_WAIT="${OCR_WAIT:-200}"
while :; do
  case "${1:-}" in
    --tap) TAP="$2"; shift 2 ;;
    --table) TABLE="$2"; shift 2 ;;
    *) break ;;
  esac
done
OUT="$1"; shift
[ -z "$TABLE" ] || [ -s "$TABLE" ] || { echo "нет эталона таблицы: $TABLE" >&2; exit 2; }
# Без тапа действие «В Excel» не выполняется вовсе, и мерить было бы нечего — а молчание на этом
# месте выглядело бы как ноль результата вместо «замер не проводился».
[ -z "$TABLE" ] || [ -n "$TAP" ] || { echo "--table без --tap: таблицу никто не построит" >&2; exit 2; }
mkdir -p "$OUT"
: > "$OUT/report.md"

# Софтверный эмулятор регулярно вешает поверх приложения диалог «System UI isn't responding»
# (заметка про эмулятор в #257): он перехватывает тапы, и замер молча меряет пустоту. Поэтому
# перед тапом помеха убирается, и это записывается в отчёт, а не проглатывается.
dismiss_anr() {
  local dump b n x1 y1 x2 y2
  # Свой дамп, а не чужой: между чужим дампом и этим тапом диалог успевает закрыться сам, и тогда
  # тап уходит в экран приложения — то есть нажимает действие вместо кнопки диалога.
  "$A" shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
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
    # Помеха эмулятора убирается ТОЛЬКО когда кнопки на экране нет, и по своему свежему дампу.
    # Прежний порядок (сначала убрать помеху, потом искать кнопку) стоил замера 04.08.2026: диалог
    # успевал закрыться сам, тап по запомненному месту «Wait» приходился уже по экрану приложения
    # и открывал соседнее действие — дальше харнесс шесть раз искал «В Excel» на чужом экране и
    # записал «кнопка не найдена». Тап мимо диалога хуже, чем невыключенная помеха: он выполняет
    # действие, которого человек не выбирал.
    if dismiss_anr; then
      echo "_помеха эмулятора убрана_" >> "$OUT/report.md"
      continue
    fi
    "$A" shell input swipe 540 1700 540 900 300   # кнопка может быть ниже сгиба
    sleep 1
  done
  return 1
}

# Занят ли экран действием прямо сейчас: строка хода («Идёт 73 с · …», «Обрабатываю…») живёт,
# пока действие идёт, и исчезает, чем бы оно ни кончилось. Отказ файла не создаёт — без этого
# вопроса ожидание файла всегда досиживало предел до конца и путало «не сделал» с «не успели».
action_busy() {
  "$A" shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  "$A" exec-out cat /sdcard/ui.xml 2>/dev/null | tr -d '\r' | grep -qE 'text="(Идёт |Обрабатываю)'
}

# Согласие на облако (#468). Сетевое действие спрашивает человека прежде, чем объект уедет
# наружу, — и правильно делает. Харнесс, написанный до этого вопроса, тапал действие и ждал файл,
# которого никогда не будет: замер молча превращался в «действие до файла не дошло», хотя оно и не
# начиналось. Подтверждаем — но только тогда, когда прогон запущен человеком с `--tap`, и
# **пишем об этом в отчёт**: без этой строки замер выглядел бы так, будто объект уехал в облако сам.
confirm_cloud() {
  local dump b n x1 y1 x2 y2
  for _ in $(seq 1 12); do
    "$A" shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    dump=$("$A" exec-out cat /sdcard/ui.xml 2>/dev/null | tr -d '\r')
    b=$(printf '%s' "$dump" | tr '>' '\n' | grep -F 'text="Разрешить"' \
      | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
    if [ -n "$b" ]; then
      n=$(printf '%s' "$b" | grep -o '[0-9]\+')
      x1=$(echo "$n" | sed -n 1p); y1=$(echo "$n" | sed -n 2p)
      x2=$(echo "$n" | sed -n 3p); y2=$(echo "$n" | sed -n 4p)
      "$A" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
      return 0
    fi
    sleep 5
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
  # Таблица прошлого кадра из scratch убирается ДО прогона: иначе «самый свежий .xlsx» окажется
  # чужим, и метрика посчитает предыдущий кадр, ничем себя не выдав.
  [ -z "$TABLE" ] || "$A" shell "run-as com.point sh -c 'rm -f files/scratch/*.xlsx'" > /dev/null 2>&1
  "$A" logcat -c
  "$A" shell am start -a android.intent.action.SEND -t image/jpeg \
    --eu android.intent.extra.STREAM "file:///data/user/0/com.point/files/c.jpg" \
    -n com.point/.ShareActivity > /dev/null 2>&1

  # OCR на софтверном эмуляторе идёт десятками секунд — ждём строку в логе, не «на глаз».
  # Сколько именно ждали, пишется рядом с пределом: без этих двух чисел «строки нет» читается
  # как «движок не смог», хотя означать может «мы не дождались».
  line=""
  read_waited=0
  while [ "$read_waited" -lt "$OCR_WAIT" ]; do
    line=$("$A" logcat -d 2>/dev/null | grep -F "OCR done" | tail -1)
    [ -n "$line" ] && break
    sleep 5
    read_waited=$((read_waited + 5))
  done
  echo "\`${line:-нет строки OCR в логе (ждали ${read_waited} с, предел ${OCR_WAIT} с)}\`" >> "$OUT/report.md"

  sleep 12  # дать энричерам дописать метаданные и журнал

  waited=""
  if [ -n "$TAP" ]; then
    if tap_by_text "$TAP"; then
      echo "_тап «$TAP»_" >> "$OUT/report.md"
      if confirm_cloud; then
        echo "_согласие на облако подтверждено_" >> "$OUT/report.md"
      fi
      if [ -n "$TABLE" ]; then
        # Таблицу строит зрячая модель: живое «В Excel» на кадре 23 заняло ~2.5 минуты. Слепой
        # sleep короче работы объявил бы «файла нет» там, где действие ещё идёт, — то есть соврал
        # бы ОТКАЗОМ, а это та же подмена факта, от которой лечит сама метрика. Поэтому ждём сам
        # файл и пишем в отчёт, сколько ждали: «не успели» и «не сделал» — разные ответы.
        waited=0
        idle=0
        while [ "$waited" -lt "$TABLE_WAIT" ]; do
          x=$("$A" shell "run-as com.point ls files/scratch 2>/dev/null" | tr -d '\r' | grep -m1 '[.]xlsx$')
          [ -n "$x" ] && break
          sleep 5
          waited=$((waited + 5))
          # Каждые полминуты спрашиваем экран, идёт ли ещё действие. Отказ («на странице не
          # нашлось таблицы») файла не создаёт, и без этого вопроса прогон досиживал предел до
          # конца: полчаса ожидания на кадр, а в отчёт — «не дошло до файла», то есть тот же
          # ответ, что и у не успевшего. Два молчания подряд — действие кончилось; один пропуск
          # прощается, потому что экран между стадиями бывает пуст.
          if [ $((waited % 30)) -eq 0 ]; then
            if action_busy; then idle=0; else idle=$((idle + 1)); fi
            [ "$idle" -ge 2 ] && break
          fi
        done
        sleep 5   # файл заводится пустым и дописывается — даём писателю закрыть zip
        if [ -z "$x" ] && [ "$idle" -ge 2 ]; then
          echo "_действие кончилось без файла на ${waited}-й секунде (экран больше не занят)_" >> "$OUT/report.md"
        else
          echo "_ждали .xlsx ${waited} с (предел ${TABLE_WAIT} с)_" >> "$OUT/report.md"
        fi
      else
        sleep 45   # сетевое действие — десятки секунд
      fi
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

  # Результат «В Excel»: сам файл, а не рассказ о нём. Отсутствие файла — тоже результат, и он
  # записывается словами: тихий ноль опаснее честного отказа.
  if [ -n "$TABLE" ]; then
    # Файл прошлого прогона ЭТОГО кадра — тоже чужой результат: не сотрёшь, и `-s` ниже
    # посчитает вчерашнюю таблицу как сегодняшнюю.
    rm -f "$OUT/$name.xlsx" "$OUT/$name.tsv"
    x=$("$A" shell "run-as com.point ls -t files/scratch 2>/dev/null" | tr -d '\r' | grep -m1 '[.]xlsx$')
    if [ -n "$x" ]; then
      "$A" exec-out run-as com.point cat "files/scratch/$x" > "$OUT/$name.xlsx" 2>/dev/null
    fi
    if [ -s "$OUT/$name.xlsx" ]; then
      bash "$(dirname "$0")/table-score.sh" "$OUT/$name.xlsx" "$TABLE" "$OUT/report.md" \
        || echo "_счёт таблицы не получен_" >> "$OUT/report.md"
    else
      rm -f "$OUT/$name.xlsx"
      echo "_файла .xlsx на устройстве нет${waited:+ (ждали $waited с)} — действие до файла не дошло_" \
        >> "$OUT/report.md"
    fi
  fi

  "$A" exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
  echo "" >> "$OUT/report.md"
done
echo "DONE $(date)" >> "$OUT/report.md"
