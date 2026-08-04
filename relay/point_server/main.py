"""Точка входа службы: `python -m point_server` или `uvicorn point_server.main:app`.

Сервер слушает **127.0.0.1** и снаружи не виден: TLS терминирует Caddy (срез #470), он же
получает и продлевает сертификат Let's Encrypt. Приложение не занимается TLS вовсе.
"""
from __future__ import annotations

import os

from .app import create_app

app = create_app()


def main() -> None:  # pragma: no cover - запуск службы
    import uvicorn

    uvicorn.run(
        app,
        host=os.environ.get("POINT_SERVER_HOST", "127.0.0.1"),
        port=int(os.environ.get("POINT_SERVER_PORT", "8080")),
        # Приватность: сервер не пишет ни путей, ни адресов. Правило перенесено дословно
        # из релея, где `log_message` заглушён нарочно.
        access_log=False,
        proxy_headers=True,
        forwarded_allow_ips="127.0.0.1",
    )


if __name__ == "__main__":  # pragma: no cover
    main()
