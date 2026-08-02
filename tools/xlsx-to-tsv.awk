# xl/worksheets/sheet1.xml -> TSV, без единой библиотеки (#262).
#
# Зовётся как `awk -v flagfill=FFFFD199 -f xlsx-to-tsv.awk sharedStrings.xml styles.xml sheet1.xml`
# (первые два файла могут быть пустыми). Разбор идёт по разделителю записей "<": запись — это тег со
# своими атрибутами плюс текст до следующего тега. Хватает ровно на то, что бывает в ячейке xlsx:
# inline-строка (так пишет Point), ссылка в общую таблицу строк (так пересохраняет Excel) и голое
# число.
#
# ЗАЧЕМ ЗДЕСЬ styles.xml. Знака «⚠» в тексте ячейки НЕТ: `OoxmlSpreadsheetWriter` снимает маркер
# (`styleCell`) и превращает неуверенность в ЗАЛИВКУ ячейки — человек видит оранжевый фон, а файл
# хранит только номер стиля. Без обратного перевода метрика посчитала бы каждое предупреждённое
# расхождение МОЛЧАЛИВЫМ, то есть соврала бы ровно в главном своём числе и в худшую сторону.
# Поэтому здесь: `s="N"` → `cellXfs[N].fillId` → цвет заливки; совпал с `flagfill` — маркер «⚠»
# возвращается в текст, и дальше его читает та же `styleCell`, что и в приложении.
#
# Жёлтая заливка правки («~~53~~ 40») предупреждением НЕ считается сознательно: она говорит «я
# увидел зачёркнутое», а не «я не уверен». Ошибка в такой ячейке молчалива — человек прочтёт число
# как окончательное.
#
# Что этот разбор НЕ делает и почему это не тихая потеря:
# - пропущенные Excel'ом пустые строки (`<row r="7">` после `r="5"`) не восстанавливаются: строки
#   печатаются в том порядке, в каком встретились. Point пишет строки подряд, а расхождение с
#   документом всё равно поймает метрика — она считает строки, а не верит разметке;
# - таб и перевод строки внутри ячейки заменяются пробелом: TSV их не переживёт. Замена сказана
#   вслух здесь, чтобы «ячейка не совпала» позже не искали в метрике.

function attr(s, name,   pat) {
  s = " " s
  pat = " " name "=\"[^\"]*\""
  if (match(s, pat)) return substr(s, RSTART + length(name) + 3, RLENGTH - length(name) - 4)
  return ""
}

function colnum(ref,   i, c, n) {
  n = 0
  for (i = 1; i <= length(ref); i++) {
    c = substr(ref, i, 1)
    if (c >= "A" && c <= "Z") n = n * 26 + index(ALPHABET, c)
    else break
  }
  return n
}

function unesc(s) {
  gsub(/&lt;/, "<", s)
  gsub(/&gt;/, ">", s)
  gsub(/&quot;/, "\"", s)
  gsub(/&apos;/, APOS, s)
  gsub(/&#10;/, " ", s)
  gsub(/&#13;/, " ", s)
  gsub(/&#9;/, " ", s)
  gsub(/&amp;/, "\\&", s)   # последним: иначе «&amp;lt;» развернулось бы дважды
  gsub(/[\t\r\n]/, " ", s)
  return s
}

function flush(   c, line) {
  if (!open) return
  line = ""
  for (c = 1; c <= maxcol; c++) line = line (c > 1 ? "\t" : "") cur[c]
  print line
  delete cur
  maxcol = 0
  open = 0
}

BEGIN {
  RS = "<"
  ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
  APOS = sprintf("%c", 39)
  MARK = "\342\232\240"   # ⚠ в UTF-8: awk-у нельзя доверять чтение не-ASCII из своего же исходника
  shared_n = 0
  in_si = 0
  open = 0
  maxcol = 0
  fill_n = -1
  xf_n = -1
  section = ""
  flagfill = toupper(flagfill)
}

{
  p = index($0, ">")
  if (p == 0) next
  tag = substr($0, 1, p - 1)
  text = substr($0, p + 1)
  selfclosing = (substr(tag, length(tag), 1) == "/")
  if (selfclosing) tag = substr(tag, 1, length(tag) - 1)
}

# --- общая таблица строк (первый файл; он же бывает пустым, поэтому не FNR==NR) ---
# Файлы различаются по ИМЕНИ, а не по порядку: пустой первый файл сбил бы счёт по FNR. Имя привязано
# к концу пути — каталог с «styles» в названии иначе увёл бы лист не в тот разбор.
FILENAME ~ /sharedStrings\.xml$/ {
  if (tag ~ /^si([ ]|$)/) { si = ""; in_si = 1 }
  else if (tag ~ /^t([ ]|$)/ && in_si) si = si text
  else if (tag == "/si") { shared[shared_n++] = si; in_si = 0 }
  next
}

# --- палитра (второй файл): какой номер стиля означает «модель не уверена» ---
# Считаются только заливки и только `cellXfs` — `cellStyleXfs` идёт раньше и своей нумерации не
# отдаёт. Индексы обеих таблиц — порядок появления, как и требует формат.
FILENAME ~ /styles\.xml$/ {
  if (tag ~ /^fills([ ]|$)/) section = "fills"
  else if (tag == "/fills") section = ""
  else if (tag ~ /^cellXfs([ ]|$)/) section = "xfs"
  else if (tag == "/cellXfs") section = ""
  else if (section == "fills" && tag ~ /^fill([ ]|$)/) fill_n++
  else if (section == "fills" && tag ~ /^fgColor([ ]|$)/) fillrgb[fill_n] = toupper(attr(tag, "rgb"))
  else if (section == "xfs" && tag ~ /^xf([ ]|$)/) xffill[++xf_n] = attr(tag, "fillId") + 0
  next
}

# --- сам лист ---
tag ~ /^row([ ]|$)/ { flush(); open = 1; next }
tag == "/row" { flush(); next }

tag ~ /^c([ ]|$)/ {
  col = colnum(attr(tag, "r"))
  if (col == 0) col = maxcol + 1
  type = attr(tag, "t")
  style = attr(tag, "s")
  val = ""
  has_t = 0
  if (selfclosing) {
    if (col > maxcol) maxcol = col
    cur[col] = mark("")
  }
  next
}

tag ~ /^t([ ]|$)/ { val = val text; has_t = 1; next }
tag ~ /^v([ ]|$)/ { v = text; next }

tag == "/c" {
  if (type == "s") out = (v in shared) ? shared[v] : ""
  else if (has_t) out = val
  else out = v
  if (col > maxcol) maxcol = col
  cur[col] = mark(unesc(out))
  v = ""
  val = ""
  has_t = 0
  next
}

# Заливка неуверенности → маркер обратно в текст. Пустая ячейка тоже помечается: «не прочитал и
# говорю об этом» — предупреждение, а не молчание.
function mark(s) {
  if (flagfill == "" || style == "") return s
  if (!((style + 0) in xffill)) return s
  if (fillrgb[xffill[style + 0]] != flagfill) return s
  if (index(s, MARK)) return s
  return s MARK
}

END { flush() }
