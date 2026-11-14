#!/bin/sh
# Restore one backup folder into a database (and, optionally, receipt files).
#
#   restore.sh BACKUP_FOLDER DATABASE_URL [RECEIPTS_TARGET_DIR]
#
# The target database must exist and be empty (create it as the postgres
# superuser, owned by fet_app). Checksums are verified first; nothing is
# restored from a damaged backup. The password comes from PGPASSWORD, which
# the backup container already has. Run it inside the backup container:
#
#   docker compose -f deploy/compose.yaml --env-file deploy/.env exec backup \
#     sh /backup/restore.sh /backups/daily/2026-10-20_0230 \
#        postgresql://fet_app@host.docker.internal:5432/fet_restore_check
set -eu
umask 077
folder="${1:?backup folder}"
url="${2:?target database URL}"
# The password comes from PGPASSWORD (set by compose), never from the URL:
# a URL argument is visible to every user in `ps`.
case "$url" in *://*:*@*) echo "remove the password from the database URL; set it in PGPASSWORD" >&2; exit 1 ;; esac
receipts_target="${3:-}"

(cd "$folder" && sha256sum -c SHA256SUMS)
pg_restore --no-owner --no-privileges --exit-on-error --dbname "$url" "$folder/fet_prod.dump"
if [ -n "$receipts_target" ]; then
	mkdir -p "$receipts_target"
	tar -C "$receipts_target" -xzf "$folder/receipts.tar.gz"
fi
echo "restored $folder"
