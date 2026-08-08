#!/usr/bin/env bash
# Собрать «свою» сборку и выложить её так, чтобы сайт показал свежую (#403).
#
# Это НЕ релизный поезд (#306): тот собирает ужатый релиз, проверяет воротами и отдаёт человеку
# черновик. Здесь другое — быстрая сборка для себя, которую владелец ставит с сайта поверх
# предыдущей.
#
# Что делает эту сборку раздаваемой:
#   * подпись релизным ключом — обновление ставится ПОВЕРХ и ничего не стирает;
#   * ключей моделей внутри нет (их подставляет только debug), проверяется сканом ниже;
#   * минификации нет — сборка занимает минуты, а не десятки минут.
#
#   bash tools/publish-dogfood.sh            # собрать, проверить, выложить
#   bash tools/publish-dogfood.sh --dry-run  # только собрать и проверить
set -eu

cd "$(cd "$(dirname "$0")/.." && { pwd -W 2>/dev/null || pwd; })"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"

DRY_RUN=false
[ "${1:-}" = "--dry-run" ] && DRY_RUN=true

APK="app/build/outputs/apk/dogfood/app-dogfood.apk"
TAG="dogfood"

echo "→ сборка"
./gradlew --quiet :app:assembleDogfood

[ -f "$APK" ] || { echo "нет $APK — сборка не дала артефакта" >&2; exit 1; }

echo "→ ворота: подпись должна быть релизной, а не отладочной"
CERT=$(powershell.exe -NoProfile -Command "
  \$env:JAVA_HOME = '$JAVA_HOME'
  \$tool = (Get-ChildItem 'C:\Users\User\AppData\Local\Android\Sdk\build-tools\*\apksigner.bat' | Sort-Object Name | Select-Object -Last 1).FullName
  (& \$tool verify --print-certs '$(pwd -W 2>/dev/null || pwd)\\$APK' 2>&1 | Select-String 'certificate DN') -join ''
" 2>&1 | tr -d '\r')
case "$CERT" in
  *"Android Debug"*)
    echo "ОТКАЗ: сборка подписана отладочным ключом — обновление поверх не встанет." >&2
    echo "Проверьте RELEASE_* в local.properties." >&2
    exit 1 ;;
  *CN=Point*) echo "  подпись: свой ключ (CN=Point)" ;;
  *) echo "ОТКАЗ: подпись не опознана: $CERT" >&2; exit 1 ;;
esac

echo "→ ворота: внутри не должно быть ключей"
bash tools/release-scan.sh --content-only "$APK"

SIZE=$(( $(stat -c%s "$APK" 2>/dev/null || wc -c < "$APK") / 1024 / 1024 ))
STAMP=$(git rev-parse --short HEAD)
NAME="Point-$STAMP.apk"
cp "$APK" "/tmp/$NAME" 2>/dev/null || cp "$APK" "$TMPDIR/$NAME"
OUT="${TMPDIR:-/tmp}/$NAME"

if [ "$DRY_RUN" = true ]; then
  echo "готово (без публикации): $OUT · ${SIZE} МБ"
  exit 0
fi

echo "→ публикация в GitHub"
NOTES="Своя сборка Point от $(git log -1 --format=%cd --date=format:'%d.%m.%Y %H:%M') · коммит $STAMP

Ставится поверх предыдущей своей сборки — история, ключи и связь с компьютером сохраняются.
Первая установка поверх отладочной потребует удалить старую: у них разные подписи.

Ключей моделей внутри нет — свой ключ вводится в приложении."

if gh release view "$TAG" > /dev/null 2>&1; then
  gh release upload "$TAG" "$OUT" --clobber
  # Старые сборки удаляются, а не копятся. `--clobber` перезаписывает только одноимённое, а имя
  # несёт коммит — поэтому без уборки на странице скапливается свалка, и человек скачивает
  # позавчерашнее, думая, что берёт свежее. У метки всегда ровно одна сборка.
  gh release view "$TAG" --json assets -q '.assets[].name' 2>/dev/null | grep -v "^$NAME$" | while read -r old; do
    [ -n "$old" ] && gh release delete-asset "$TAG" "$old" --yes > /dev/null 2>&1 && echo "  убрано старое: $old"
  done
  gh release edit "$TAG" --notes "$NOTES" --prerelease > /dev/null
else
  gh release create "$TAG" "$OUT" --title "Своя сборка Point" --notes "$NOTES" --prerelease
fi

echo "готово: $NAME · ${SIZE} МБ → https://github.com/librevlad/point/releases/tag/$TAG"
