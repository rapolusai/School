#!/bin/sh
# Restores a dump made by backup.sh. Runbook: docs/runbooks/single-server.md ("Backups and restore").
#
#   backup/restore.sh --drill FILE|s3://...   restore into a throwaway PostgreSQL container, compare the row count of
#                                             every table with the live database, then remove the container.
#                                             Touches nothing live. Do this monthly.
#   backup/restore.sh --live  FILE|s3://...   REPLACE the live database with the dump: takes a safety dump first,
#                                             stops api/web, recreates the database, restores, starts the stack
#                                             (the API migrates an older dump up to the running version).
#   Add --yes to skip the confirmation of --live.
set -eu

HERE=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE="$HERE/.env"
COMPOSE="$HERE/compose.sh"
DRILL=akshara-restore-drill

env_get() {
    v=$(sed -n "s/^$1=//p" "$ENV_FILE" 2>/dev/null | tail -n 1)
    printf '%s' "${v:-$2}"
}
log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) restore: $*"; }
die() { log "ERROR: $*"; exit 1; }
usage() { echo "usage: $0 --drill|--live [--yes] FILE|s3://bucket/key" >&2; exit 2; }

MODE=""; YES=0; SRC=""
for arg in "$@"; do
    case "$arg" in
        --drill) MODE=drill ;;
        --live) MODE=live ;;
        --yes) YES=1 ;;
        -*) usage ;;
        *) SRC=$arg ;;
    esac
done
[ -n "$MODE" ] && [ -n "$SRC" ] || usage

BACKUP_DIR=${BACKUP_DIR:-$(env_get BACKUP_DIR /var/backups/akshara)}
umask 077
mkdir -p "$BACKUP_DIR"

# --- fetch -------------------------------------------------------------------------------------------------------
case "$SRC" in
    s3://*)
        command -v aws >/dev/null 2>&1 || die "the AWS CLI is not installed"
        DUMP="$BACKUP_DIR/restore-$(basename "$SRC")"
        log "downloading $SRC"
        aws s3 cp --only-show-errors "$SRC" "$DUMP"
        if aws s3 cp --only-show-errors "$SRC.sha256" "$DUMP.sha256.orig" 2>/dev/null; then
            want=$(cut -d' ' -f1 "$DUMP.sha256.orig")
            got=$(sha256sum "$DUMP" | cut -d' ' -f1)
            rm -f "$DUMP.sha256.orig"
            [ "$want" = "$got" ] || die "checksum mismatch for $DUMP"
            log "checksum ok"
        fi
        ;;
    *)
        DUMP=$SRC
        [ -f "$DUMP" ] || die "no such file: $DUMP"
        if [ -f "$DUMP.sha256" ]; then
            (cd "$(dirname "$DUMP")" && sha256sum -c --status "$(basename "$DUMP").sha256") \
                || die "checksum mismatch for $DUMP"
            log "checksum ok"
        fi
        ;;
esac

# Exact row count of every table, as "schema.table count" lines. Run as postgres (superuser: no row-level security).
COUNT_SQL="select n.nspname || '.' || c.relname || ' ' ||
  (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', n.nspname, c.relname),
   false, true, '')))[1]::text
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where c.relkind = 'r' and n.nspname not in ('pg_catalog', 'information_schema') and n.nspname not like 'pg_toast%'
order by 1;"

live_counts() {
    "$COMPOSE" exec -T db psql -U postgres -d akshara -At -v ON_ERROR_STOP=1 -c "$COUNT_SQL"
}

# --- drill -------------------------------------------------------------------------------------------------------
if [ "$MODE" = drill ]; then
    docker rm -f "$DRILL" >/dev/null 2>&1 || true
    # Throwaway passwords: the drill database is never reachable (no network) and is deleted afterwards.
    pw() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24; }
    log "starting throwaway database container $DRILL"
    docker run -d --name "$DRILL" --network none --memory 512m \
        -e POSTGRES_DB=akshara -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD="$(pw)" \
        -e DB_OWNER_PASSWORD="$(pw)" -e DB_APP_PASSWORD="$(pw)" \
        -v "$HERE/postgres/init-roles.sh:/docker-entrypoint-initdb.d/10-roles.sh:ro" \
        -v "$HERE/../terraform/bootstrap/db-roles.sql:/akshara/db-roles.sql:ro" \
        postgres:17-alpine >/dev/null
    trap 'docker rm -f "$DRILL" >/dev/null 2>&1 || true' EXIT
    # The entrypoint restarts PostgreSQL after running the init scripts; wait for the final server.
    i=0
    until docker exec "$DRILL" sh -c 'test -f /var/lib/postgresql/data/postmaster.pid && pg_isready -q -U postgres -d akshara' \
        && docker logs "$DRILL" 2>&1 | grep -q 'PostgreSQL init process complete'; do
        i=$((i + 1)); [ $i -lt 90 ] || die "drill database did not start"; sleep 1
    done
    sleep 2
    START=$(date +%s)
    log "restoring $DUMP"
    docker exec -i "$DRILL" pg_restore -U postgres -d akshara --exit-on-error < "$DUMP" || die "pg_restore failed"
    log "restored in $(( $(date +%s) - START ))s"
    docker exec "$DRILL" psql -U postgres -d akshara -At -v ON_ERROR_STOP=1 -c "$COUNT_SQL" > "$BACKUP_DIR/drill-restored.counts"
    live_counts > "$BACKUP_DIR/drill-live.counts"
    tables=$(wc -l < "$BACKUP_DIR/drill-restored.counts")
    rows=$(awk '{s += $2} END {print s + 0}' "$BACKUP_DIR/drill-restored.counts")
    log "restored copy: $tables tables, $rows rows"
    if diff "$BACKUP_DIR/drill-live.counts" "$BACKUP_DIR/drill-restored.counts" > "$BACKUP_DIR/drill.diff"; then
        log "row counts match the live database for all $tables tables"
    else
        log "row counts differ from the live database (expected for tables written since the dump was taken):"
        grep '^[<>]' "$BACKUP_DIR/drill.diff" | sed 's/^</  live    /; s/^>/  restored/'
    fi
    log "drill finished; the throwaway container is removed"
    exit 0
fi

# --- live --------------------------------------------------------------------------------------------------------
if [ "$YES" != 1 ]; then
    domain=$(env_get APP_DOMAIN "")
    printf 'This REPLACES the live database of %s with %s.\nType the domain to continue: ' "$domain" "$DUMP"
    read -r answer
    [ "$answer" = "$domain" ] || die "not confirmed"
fi

log "safety dump of the current database first"
"$HERE/backup/backup.sh" --no-upload

log "stopping web and api (Caddy keeps answering; visitors see an error page until the end)"
"$COMPOSE" stop web api

log "recreating database akshara"
"$COMPOSE" exec -T db psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
    -c "drop database if exists akshara with (force)" \
    -c "create database akshara owner akshara_owner"

START=$(date +%s)
log "restoring $DUMP"
"$COMPOSE" exec -T db pg_restore -U postgres -d akshara --exit-on-error < "$DUMP" || die "pg_restore failed; the safety dump is in $BACKUP_DIR"
log "restored in $(( $(date +%s) - START ))s"

log "starting the stack (the API applies any newer migrations as it starts)"
"$COMPOSE" up -d --wait
live_counts | awk '{t++; s += $2} END {print "  " t " tables, " s " rows"}'
log "done"
