#!/bin/sh
# Run on the homelab after `docker compose ... up -d --build` to check the
# deployment. It only reads; it changes nothing.
#   sh deploy/verify.sh
set -u
cd "$(dirname "$0")"
. ./.env 2>/dev/null || { echo "deploy/.env not found"; exit 1; }
compose="docker compose -f compose.yaml --env-file .env"
port="${WEB_PORT:?WEB_PORT missing in deploy/.env}"
host="${WEB_BIND:?WEB_BIND missing in deploy/.env}"
base="http://$host:$port"
fail=0
ok() { echo "  ok    $*"; }
bad() { echo "  FAIL  $*"; fail=1; }

echo "Containers"
for svc in api worker web backup; do
	state=$($compose ps --format '{{.Service}} {{.State}} {{.Health}}' | awk -v s="$svc" '$1 == s {print $2, $3}')
	case "$state" in
		"running healthy" | "running ") ok "$svc running ${state#running}" ;;
		*) bad "$svc: ${state:-not running}" ;;
	esac
done
migrate=$($compose ps -a --format '{{.Service}} {{.State}} {{.ExitCode}}' | awk '$1 == "migrate" {print $2, $3}')
[ "$migrate" = "exited 0" ] && ok "migrations applied" || bad "migrate: $migrate"

echo "Web and API ($base)"
[ "$(curl -s "$base/api/v1/ready")" = '{"status":"ok"}' ] && ok "API ready (database reachable)" || bad "API not ready"
curl -s -o /dev/null -w '%{http_code}' "$base/" | grep -q 200 && ok "dashboard served" || bad "dashboard not served"
curl -sI "$base/" | grep -qi "content-security-policy" && ok "security headers" || bad "no security headers"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$base/api/v1/docs")" = 404 ] && ok "API docs hidden" || bad "API docs exposed"

# Must NOT answer on other addresses (only on the home-network address).
if curl -s -m 3 -o /dev/null "http://127.0.0.1:$port/"; then
	bad "also reachable on 127.0.0.1 (should listen only on $host)"
else
	ok "listens only on $host"
fi

echo "Worker"
langs=$($compose exec -T worker tesseract --list-langs 2>/dev/null)
echo "$langs" | grep -qx ben && echo "$langs" | grep -qx eng && ok "OCR: Bangla and English" || bad "OCR languages missing"

# The backup folder is root-only (mode 0700), so read it inside the container.
echo "Backups ($BACKUP_DIR)"
in_backup() { $compose exec -T backup sh -c "$1" 2>/dev/null; }
if last=$(in_backup 'cat /backups/LAST_SUCCESS'); then
	ok "last backup: $last"
	latest=$(in_backup 'ls -1 /backups/daily | sort | tail -n 1')
	in_backup "cd /backups/daily/$latest && sha256sum -c -s SHA256SUMS" && ok "checksums of $latest" || bad "checksums of $latest"
else
	bad "no backup yet (see: $compose logs backup)"
fi
failed=$(in_backup 'cat /backups/LAST_FAILURE') && echo "  note  a backup failed at $failed; see the backup logs"

[ "$fail" = 0 ] && echo "All checks passed." || echo "Some checks failed."
exit "$fail"
