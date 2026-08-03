#!/bin/bash
# Поднять релей, если он не работает (#161).
#
# «Уже работает?» решается по СЛУШАТЕЛЮ ПОРТА, а не по имени процесса. Прежняя проверка
# (pgrep -f 'python3 .*point-relay/relay.py') ловила сам сторож: строка шаблона лежала в
# аргументах крон-задания, pgrep находил её и считал релей живым — поэтому упавший релей
# не поднимался неделю, а после перезагрузки поднимался (03.08.2026).
cd "$HOME/point-relay" || exit 1

if ss -tln 2>/dev/null | grep -q ':8443 '; then
  exit 0
fi

# Порт свободен — значит живого релея нет; добиваем возможный зависший процесс и стартуем.
pkill -f 'point-relay/relay\.py' 2>/dev/null
sleep 1

export POINT_RELAY_SECRET="$(cat "$HOME/point-relay/secret")"
export POINT_RELAY_PORT=8443
setsid nohup python3 "$HOME/point-relay/relay.py" >> "$HOME/point-relay/relay.log" 2>&1 &
echo "$(date -Is) поднял релей" >> "$HOME/point-relay/watchdog.log"
