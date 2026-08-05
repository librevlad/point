"""Настройки сервера — только из окружения.

Ни `client_id`, ни `client_secret` Google в репозитории нет и быть не может: весь смысл
выбранного потока входа в том, что учётные данные Google живут **на сервере**, в файле
службы, — ни в APK, ни в MSI их нет. Это тот же инвариант `CLAUDE.md`, который сегодня
нарушает общий `RELAY_APP_SECRET` (#419).
"""
from __future__ import annotations

import os
from dataclasses import dataclass

# Пять минут — столько живёт незавершённый вход. Долгий срок здесь не удобство, а окно, в
# которое чужой человек может подтвердить чужой вход.
LOGIN_TTL_SECONDS = 300
# Как часто устройству опрашивать сервер, пока человек ходит в браузер.
POLL_INTERVAL_SECONDS = 2
# Устройство считается «на связи», если сервер слышал его за последние пять минут.
ONLINE_WINDOW_SECONDS = 300
# Как часто обходить хранилище в поисках просроченного. Отдельного сторожа нет: обход делает
# тот, кто кладёт новое, — тем же приёмом, что и уборка незавершённых входов.
SWEEP_EVERY_SECONDS = 15 * 60


@dataclass(frozen=True)
class Settings:
    root: str
    db_path: str
    public_url: str
    google_client_id: str
    google_client_secret: str
    login_ttl: int = LOGIN_TTL_SECONDS
    poll_interval: int = POLL_INTERVAL_SECONDS
    online_window: int = ONLINE_WINDOW_SECONDS
    sweep_every: int = SWEEP_EVERY_SECONDS

    @property
    def redirect_uri(self) -> str:
        return self.public_url.rstrip("/") + "/auth/callback"

    @property
    def google_configured(self) -> bool:
        return bool(self.google_client_id and self.google_client_secret)

    def login_url(self, login_id: str) -> str:
        return "%s/login?d=%s" % (self.public_url.rstrip("/"), login_id)


def settings_from_env(env: dict[str, str] | None = None) -> Settings:
    """Сервер поднимается из окружения службы; значений по умолчанию для секретов нет."""
    env = os.environ if env is None else env
    root = os.path.expanduser(env.get("POINT_SERVER_ROOT", "~/point-server"))
    return Settings(
        root=root,
        db_path=env.get("POINT_SERVER_DB", os.path.join(root, "point.db")),
        # Публичный адрес — не секрет: он вкомпилируется в клиентов как константа сборки.
        public_url=env.get("POINT_SERVER_URL", "https://point.leerio.app"),
        google_client_id=env.get("POINT_GOOGLE_CLIENT_ID", ""),
        google_client_secret=env.get("POINT_GOOGLE_CLIENT_SECRET", ""),
        login_ttl=int(env.get("POINT_LOGIN_TTL", LOGIN_TTL_SECONDS)),
    )
