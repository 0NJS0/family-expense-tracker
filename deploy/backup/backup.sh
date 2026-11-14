#!/bin/sh
# Daily backup of the family expenses database and receipt files.
#
#   backup.sh            make one backup now
#   backup.sh --daemon   make one at BACKUP_TIME every day (and one at start if
#                        there is none for today yet)
#
# Each backup is a folder daily/YYYY-MM-DD_HHMM with:
#   fet_prod.dump      pg_dump custom format (restore with restore.sh)
#   receipts.tar.gz    the receipt files
#   SHA256SUMS         checksums of both
# The first backup of each month is also kept under monthly/YYYY-MM (hard links,
# no extra space). Kept: BACKUP_KEEP_DAILY daily, BACKUP_KEEP_MONTHLY monthly.
# A backup only appears under daily/ once complete and verified readable.
# Plain POSIX sh: the postgres:18-alpine image has only busybox.
set -eu
# Backups hold every transaction and password hash: owner-only files and folders.
umask 077

BACKUPS="${BACKUPS:-/backups}"
RECEIPTS="${RECEIPTS:-/receipts}"
KEEP_DAILY="${BACKUP_KEEP_DAILY:-14}"
KEEP_MONTHLY="${BACKUP_KEEP_MONTHLY:-12}"
BACKUP_TIME="${BACKUP_TIME:-02:30}"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') backup: $*"; }

# Keep only the newest N entries of a directory (names sort by date).
prune() {
	dir="$1"
	keep="$2"
	count=$(ls -1 "$dir" | wc -l)
	excess=$((count - keep))
	if [ "$excess" -gt 0 ]; then
		ls -1 "$dir" | sort | head -n "$excess" | while read -r old; do
			log "removing old backup $dir/$old"
			rm -rf "${dir:?}/$old"
		done
	fi
}

backup_once() {
	: "${BACKUP_DATABASE_URL:?BACKUP_DATABASE_URL is not set}"
	# The password comes from PGPASSWORD (set by compose), never from the URL:
	# a URL argument is visible to every user in `ps`.
	case "$BACKUP_DATABASE_URL" in *://*:*@*) echo "remove the password from the database URL; set it in PGPASSWORD" >&2; exit 1 ;; esac
	mkdir -p "$BACKUPS/daily" "$BACKUPS/monthly"
	stamp=$(date +%Y-%m-%d_%H%M)
	month=$(date +%Y-%m)
	work="$BACKUPS/.in-progress-$stamp"
	rm -rf "$work"
	mkdir -p "$work"
	# Never leave a half-written backup behind.
	trap 'rm -rf "$work"' EXIT

	pg_dump --format=custom --no-owner --no-privileges --file "$work/fet_prod.dump" "$BACKUP_DATABASE_URL"
	# The dump must be readable, or it is not a backup.
	pg_restore --list "$work/fet_prod.dump" >/dev/null
	tar -C "$RECEIPTS" -czf "$work/receipts.tar.gz" .
	(cd "$work" && sha256sum fet_prod.dump receipts.tar.gz >SHA256SUMS)

	target="$BACKUPS/daily/$stamp"
	rm -rf "$target"
	mv "$work" "$target"
	trap - EXIT
	if [ ! -d "$BACKUPS/monthly/$month" ]; then
		mkdir -p "$BACKUPS/monthly/$month"
		ln "$target"/* "$BACKUPS/monthly/$month/"
		log "kept as the monthly backup for $month"
	fi
	prune "$BACKUPS/daily" "$KEEP_DAILY"
	prune "$BACKUPS/monthly" "$KEEP_MONTHLY"
	date '+%Y-%m-%d %H:%M:%S' >"$BACKUPS/LAST_SUCCESS"
	log "done: $target ($(du -sh "$target" | cut -f1))"
}

# Seconds from now until the next BACKUP_TIME (HH:MM, local time).
seconds_until() {
	target_h=${1%%:*}
	target_m=${1##*:}
	target_h=${target_h#0}
	target_m=${target_m#0}
	h=$(date +%H)
	m=$(date +%M)
	s=$(date +%S)
	h=${h#0}
	m=${m#0}
	s=${s#0}
	wait=$(((${target_h:-0} * 3600 + ${target_m:-0} * 60) - (${h:-0} * 3600 + ${m:-0} * 60 + ${s:-0})))
	if [ "$wait" -le 0 ]; then
		wait=$((wait + 86400))
	fi
	echo "$wait"
}

if [ "${1:-}" = "--daemon" ]; then
	log "started; daily at $BACKUP_TIME, keeping $KEEP_DAILY daily and $KEEP_MONTHLY monthly"
	if ! ls -1 "$BACKUPS/daily" 2>/dev/null | grep -q "^$(date +%Y-%m-%d)"; then
		backup_once || log "FAILED (will try again at $BACKUP_TIME)"
	fi
	while true; do
		sleep "$(seconds_until "$BACKUP_TIME")"
		# A failure is logged; the next day's backup still runs.
		(backup_once) || {
			log "FAILED"
			date '+%Y-%m-%d %H:%M:%S' >"$BACKUPS/LAST_FAILURE"
		}
	done
elif [ "${1:-}" = "--seconds-until" ]; then
	seconds_until "$2"
else
	backup_once
fi
