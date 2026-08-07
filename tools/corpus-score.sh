#!/usr/bin/env bash
# Число корпуса по результату прогона (#262): сколько кадров Point понял САМ, без правок.
#
# Прогон `tools/corpus-run.sh` снимает с каждого кадра `NN.flow.json` — факты объекта, которые
# видит схема готовности. Раньше итог по ним собирался руками; теперь его считает та же
# `scoreCorpus`, что и тесты, — второй копии правил не существует.
#
#   bash tools/corpus-score.sh <каталог прогона> [карта кадров] [отчёт.md]
#
# По умолчанию карта — tools/corpus/frames.tsv, отчёт дописывается в <каталог>/report.md.
set -u
RUN="${1:?нужен каталог прогона}"
ROOT=$(cd "$(dirname "$0")/.." && { pwd -W 2>/dev/null || pwd; })
FRAMES="${2:-$ROOT/tools/corpus/frames.tsv}"
REPORT="${3:-$RUN/report.md}"
[ -d "$RUN" ] || { echo "нет каталога прогона: $RUN" >&2; exit 2; }
[ -s "$FRAMES" ] || { echo "нет карты кадров: $FRAMES" >&2; exit 2; }
cd "$ROOT" || exit 2
./gradlew --quiet --console=plain :core:flow:scoreCorpus \
  -Prun="$RUN" -Pframes="$FRAMES" -Preport="$REPORT" \
  || { echo "_счёт корпуса не получен_" >> "$REPORT"; exit 1; }
tail -12 "$REPORT"
