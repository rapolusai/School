#!/bin/sh
# Daily database backup for the single-server setup (run by akshara-backup.timer, or by hand).
#   backup/backup.sh               dump, check, upload to s3://$BACKUP_S3_BUCKET/db/YYYY/MM/, prune local copies
#   backup/backup.sh --no-upload   same without the S3 upload (tests, or a quick copy before a deploy)
#
# - pg_dump in custom format (compressed, -Z 6), taken as the postgres superuser so row-level security never hides
#   rows; checked with pg_restore --list before it counts as a backup; a .sha256 file next to it.
# - Upload with the AWS CLI using the instance role (no keys on the server). The bucket's lifecycle rule deletes
#   copies after 30 days (docs/runbooks/single-server.md).
# - Local copies older than BACKUP_KEEP_DAYS (default 7) are deleted.
# - Uploaded files: with FILES_STORE=database they are inside this dump. With FILES_STORE=s3 they live in the
#   versioned files bucket (old versions kept 30 days by its lifecycle rule), which this script does not copy.
# - Optional BACKUP_PING_URL (e.g. a healthchecks.io check): pinged on success, "/fail" on failure.
set -eu

HERE=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE="$HERE/.env"

env_get() { # KEY DEFAULT: value from .env (the file is not sourced: some values contain spaces)
    v=$(sed -n "s/^$1=//p" "$ENV_FILE" 2>/dev/null | tail -n 1)
    printf '%s' "${v:-$2}"
}
log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: $*"; }

UPLOAD=1
case "${1:-}" in
    --no-upload) UPLOAD=0 ;;
    "") ;;
    *) echo "usage: $0 [--no-upload]" >&2; exit 2 ;;
esac

BACKUP_DIR=${BACKUP_DIR:-$(env_get BACKUP_DIR /var/backups/akshara)}
KEEP_DAYS=${BACKUP_KEEP_DAYS:-$(env_get BACKUP_KEEP_DAYS 7)}
BUCKET=${BACKUP_S3_BUCKET:-$(env_get BACKUP_S3_BUCKET "")}
FILES_STORE=$(env_get FILES_STORE database)
PING_URL=${BACKUP_PING_URL:-$(env_get BACKUP_PING_URL "")}
case "$BUCKET" in *REPLACE*) BUCKET="" ;; esac

ping_monitor() {
    [ -n "$PING_URL" ] && curl -fsS -m 10 --retry 3 -o /dev/null "$PING_URL$1" || true
}
fail() {
    log "FAILED: $*"
    ping_monitor /fail
    exit 1
}

umask 077
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

# One run at a time (a slow upload must not overlap the next timer run).
exec 9>"$BACKUP_DIR/.lock"
if command -v flock >/dev/null 2>&1; then
    flock -n 9 || fail "another backup is still running"
fi

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
FILE="$BACKUP_DIR/akshara-$STAMP.dump"
PART="$FILE.part"

log "dumping database akshara to $FILE"
"$HERE/compose.sh" exec -T db pg_dump -U postgres -d akshara --format=custom --compress=6 > "$PART" \
    || { rm -f "$PART"; fail "pg_dump failed"; }
"$HERE/compose.sh" exec -T db pg_restore --list < "$PART" > /dev/null \
    || { rm -f "$PART"; fail "the dump does not read back (pg_restore --list)"; }
mv "$PART" "$FILE"
(cd "$BACKUP_DIR" && sha256sum "$(basename "$FILE")" > "$(basename "$FILE").sha256")
SIZE=$(du -h "$FILE" | cut -f1)
log "dump ok ($SIZE)"
if [ "$FILES_STORE" = "database" ]; then
    log "uploaded files are stored in the database (FILES_STORE=database), so they are in this dump"
else
    log "uploaded files are in the S3 files bucket (FILES_STORE=$FILES_STORE): protected by its versioning, not by this dump"
fi

if [ "$UPLOAD" = 1 ]; then
    if [ -z "$BUCKET" ]; then
        log "WARNING: BACKUP_S3_BUCKET is not set; this backup exists only on this server"
    else
        command -v aws >/dev/null 2>&1 || fail "the AWS CLI is not installed (docs/runbooks/single-server.md)"
        DEST="s3://$BUCKET/db/$(date -u +%Y/%m)/"
        log "uploading to $DEST"
        aws s3 cp --only-show-errors "$FILE" "$DEST" || fail "upload of the dump failed"
        aws s3 cp --only-show-errors "$FILE.sha256" "$DEST" || fail "upload of the checksum failed"
        log "upload ok"
    fi
else
    log "upload skipped (--no-upload)"
fi

# Local retention: -mtime +N matches files older than N+1 days.
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'akshara-*.dump*' -mtime +"$((KEEP_DAYS - 1))" -print -delete \
    | sed 's/^/  deleted old local copy: /'
log "done: $FILE"
ping_monitor ""
