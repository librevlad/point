# Point relay (#161 v2)

A **blind store-and-forward mailbox** that makes the phone↔PC link reliable across firewalls,
changing IPs and different networks (LTE ↔ home Wi-Fi). Both devices connect **outbound** to it,
so neither needs an open inbound port. It never holds the pairing token or a plaintext object —
only ciphertext addressed by an opaque, token-derived mailbox id (`RelayCrypto` on the app side).

## Deployed on `35.185.31.106` (GCP project `leerio`)

- `relay.py` runs as an **unprivileged user process** (no root / no systemd) on **:8443**, TLS with
  a self-signed cert (IP SAN `35.185.31.106`), gated by a build-baked `X-Point-App` secret.
- Files under `~/point-relay/`: `relay.py`, `cert.pem` / `key.pem`, `secret`, `mbx/` (blobs), `start.sh`.
- Autostart: `crontab @reboot ~/point-relay/start.sh` (relaunches only if not already running).
- Verified locally: `health` / push → pull → ack / empty-after-ack / auth-reject all pass.

## The one thing that must be done in the cloud

The instance's gcloud lacks firewall scopes, so the relay port must be opened in the **GCP firewall
of project `leerio`** (Console → VPC → Firewall → Create rule, or from a machine authed to leerio):

```
gcloud compute firewall-rules create point-relay \
  --project=leerio --direction=INGRESS --allow=tcp:8443 --source-ranges=0.0.0.0/0
```

Safe to open to `0.0.0.0/0`: the relay is TLS + app-secret + end-to-end encrypted, so it physically
cannot read the objects that cross it.

## Operate

- Logs: `~/point-relay/relay.log`
- Restart: `pkill -f point-relay/relay.py; ~/point-relay/start.sh`
- The app pins `cert.pem` (this dir) and sends `secret` as `X-Point-App`; both live in the app's
  **git-ignored** `local.properties` (`RELAY_APP_SECRET`, `RELAY_URL`).

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/health` | 200 `ok` (no auth) |
| POST | `/mbx/<id>` | body = ciphertext → 200 + `X-Blob-Id` (≤ 50 MB) |
| GET | `/mbx/<id>?wait=N` | oldest blob (+ `X-Blob-Id`) or 204 after N s long-poll |
| POST | `/mbx/<id>/ack` | `X-Blob-Id` header → 200 (delete; repeat ack still 200) |

All but `/health` require `X-Point-App: <secret>`.
