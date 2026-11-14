# Deployment on the homelab (Docker Compose)

Everything runs on the homelab: the API, the background worker (OCR and AI
summaries), the dashboard, and daily backups. The database is the homelab's
existing PostgreSQL 18. Access is plain HTTP on the home Wi-Fi and through a
VPN (which encrypts traffic outside the home).

```
phone / browser ──http──▶ web (Caddy, :WEB_PORT) ──▶ api (uvicorn) ──▶ PostgreSQL 18 (host)
                                                   worker ─────────▶ PostgreSQL, llama.cpp (optional)
                                                   backup ─────────▶ PostgreSQL → BACKUP_DIR (daily)
```

| Service | Image | Notes |
|---------|-------|-------|
| `migrate` | backend | `alembic upgrade head`, then exits; the others wait for it |
| `api` | backend | one process (the login rate limiter is in memory); health-checked; not published |
| `worker` | backend | receipt OCR (Tesseract, Bangla + English) and AI summaries |
| `web` | Caddy + built dashboard | the only published port; serves `/` and proxies `/api` |
| `backup` | `postgres:18-alpine` | daily `pg_dump` + receipts, 14 daily and 12 monthly kept |

All containers run without root privileges inside the app (the backend as uid
10001), with read-only file systems, no new privileges and dropped
capabilities. The database password and JWT secret live only in `deploy/.env`
on the homelab.

## First installation

Run these on the homelab, in the repository folder.

1. **A fresh database.** As the PostgreSQL superuser, with a new strong password:

   ```bash
   sudo -u postgres psql -v app_password="'<new strong password>'" -f deploy/setup-database.sql
   ```

   Allow the app's Docker network (`APP_SUBNET`, default `172.30.250.0/24`) in
   `pg_hba.conf`, then reload PostgreSQL:

   ```
   host  fet_prod  fet_app  172.30.250.0/24  scram-sha-256
   ```

   The containers reach PostgreSQL as `host.docker.internal`. That needs
   PostgreSQL to listen on all addresses (`listen_addresses = '*'`). If it
   listens only on the LAN address, put that address in the two database URLs
   instead of `host.docker.internal`.

2. **Folders** for receipts and backups:

   ```bash
   sudo mkdir -p /srv/family-expenses/receipts /srv/family-expenses/backups
   sudo chown 10001:10001 /srv/family-expenses/receipts
   sudo chmod 700 /srv/family-expenses/backups   # backups hold all the data
   ```

3. **Settings.** `cp deploy/.env.example deploy/.env`, then fill in
   `FET_DATABASE_URL` (the password from step 1, percent-encoded),
   `BACKUP_DATABASE_PASSWORD` (the same password, as typed), `FET_JWT_SECRET`
   (`openssl rand -base64 48`) and `WEB_BIND`, the homelab's home-network
   address, and `WEB_PORT`, any free port. `chmod 600 deploy/.env`.

4. **Start:**

   ```bash
   docker compose -f deploy/compose.yaml --env-file deploy/.env up -d --build
   ```

5. **The first admin** (asks for the password; refuses to run twice):

   ```bash
   docker compose -f deploy/compose.yaml --env-file deploy/.env run --rm -it api \
     python -m app.cli bootstrap --username admin --display-name "Admin" --household "আমাদের পরিবার"
   ```

   Other family members join through invitation links from the dashboard
   (Family & settings → Members).

6. **Check:** `sh deploy/verify.sh`. It checks the containers, the API, the
   dashboard and its security headers, that OCR has Bangla, and the first backup
   (one is made right after start).

The dashboard and the phone app then use `http://<homelab-address>:<WEB_PORT>` (the
same address works through the VPN). The phone must use the
**debug build**: release builds talk only to HTTPS servers.

## Updating

```bash
git pull
docker compose -f deploy/compose.yaml --env-file deploy/.env up -d --build
```

Migrations run automatically before the new API starts. They are additive (see
`data-model.md`). Make sure a recent backup exists first (`deploy/verify.sh`).

## Backups

- Every day at `BACKUP_TIME` (02:30 Dhaka time), and once right after the
  backup service starts if there is none for that day.
- Each backup is a folder `BACKUP_DIR/daily/YYYY-MM-DD_HHMM` containing
  `fet_prod.dump` (`pg_dump` custom format), `receipts.tar.gz` and `SHA256SUMS`.
  It only appears once complete and checked readable (`pg_restore --list`).
- The month's first backup is also kept under `monthly/YYYY-MM` (hard links, no
  extra space). Kept: 14 daily, 12 monthly (`BACKUP_KEEP_*`).
- `BACKUP_DIR/LAST_SUCCESS` and `LAST_FAILURE` show the latest outcome; details
  are in `docker compose … logs backup`.
- To keep a copy away from the homelab's disk, point `BACKUP_DIR` at a mounted
  NAS share.

### Testing a restore (do this once, and after big changes)

```bash
sudo -u postgres createdb -O fet_app fet_restore_check
docker compose -f deploy/compose.yaml --env-file deploy/.env exec backup \
  sh /backup/restore.sh /backups/daily/<folder> \
  "postgresql://fet_app@host.docker.internal:5432/fet_restore_check" /tmp/restored-receipts
sudo -u postgres psql fet_restore_check -c "select count(*) from transactions"
sudo -u postgres dropdb fet_restore_check
```

`restore.sh` checks the checksums first and stops on the first error. To
recover for real, restore into a new empty `fet_prod` (the same steps, with the
API stopped) and copy the receipts back into `RECEIPTS_DIR`.

## Operations

| Task | Command (prefix: `docker compose -f deploy/compose.yaml --env-file deploy/.env`) |
|------|------|
| Status | `ps` |
| Logs | `logs -f api worker backup` |
| Restart | `restart api worker` |
| Backup now | `exec backup sh /backup/backup.sh` |
| Stop | `down` (data stays in PostgreSQL and the folders) |

## Security notes

- Only the web port is published, and only on the homelab's home-network
  address (`WEB_BIND`): reachable from the home Wi-Fi and the VPN,
  not on other addresses such as a public IPv6 one. Docker opens
  published ports itself, past `ufw`, so this binding is what limits access.
  Keep the address fixed with a DHCP reservation on the router.
- Nothing is reachable from the internet unless the router forwards the port
  (check port forwarding and UPnP).
- Plain HTTP: on the home Wi-Fi, traffic is not encrypted (owner decision;
  `security.md` S4).
- The API, worker and backup are internal.
- llama.cpp still has no API key (`security.md` S1). Start it with `--api-key`
  and set `FET_LLAMA_API_KEY`.
- The `fet_test` role (tests only) should get a new password (S3); it has no
  access to `fet_prod`.

## Verified so far

On the development machine (no Docker there): the backend's exact install
from `requirements.txt` with hashes in a clean environment; all migrations on a
fresh database; the API in production mode (ready, API docs hidden, the
health-check command); the worker starting; the dashboard build from a clean
`npm ci`; the backup script's logic with stub tools (`tests/test_backup_script.py`).
The images themselves, the Caddy configuration and the backup against the real
PostgreSQL run for the first time on the homelab; `deploy/verify.sh` checks them.
