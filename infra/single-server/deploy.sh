#!/bin/sh
# Builds and rolls out a version on the single server, keeping the previous images for a quick rollback.
# Run from the repository checkout on the server (e.g. /opt/akshara), as a user in the docker group.
#
#   infra/single-server/deploy.sh v1.4.0          deploy a tag, branch or commit (fetched from origin)
#   infra/single-server/deploy.sh --rollback      go back to the previous deployed version (images kept, no build)
#   infra/single-server/deploy.sh --rollback TAG  go back to a specific earlier version from .deploy-history
#   infra/single-server/deploy.sh --status        show the running version and the history
#
# Steps: check out the ref (detached) -> build akshara-api:<sha> and akshara-web:<sha> while the old version keeps
# serving -> backup without upload -> write release.env -> `up -d` (API and web are recreated, the new API runs the
# Flyway migrations as it starts: one to two minutes of 502s) -> smoke test through Caddy -> on failure, roll back.
# Like the AWS pipeline, a rollback changes the code only, never the database, so migrations must stay compatible
# with the previous version (docs/runbooks/release-and-rollback.md).
# Options: SKIP_BACKUP=1 skips the pre-deploy dump; KEEP_IMAGES (default 3) versions are kept.
set -eu

# Run from a temporary copy: checking out another ref rewrites this file, and sh reads scripts as it goes.
if [ -z "${AKSHARA_DEPLOY_HERE:-}" ]; then
    AKSHARA_DEPLOY_HERE=$(cd "$(dirname "$0")" && pwd)
    export AKSHARA_DEPLOY_HERE
    copy=$(mktemp "${TMPDIR:-/tmp}/akshara-deploy.XXXXXX")
    cp "$0" "$copy"
    exec sh "$copy" "$@"
fi
case "$0" in */akshara-deploy.*) trap 'rm -f "$0"' EXIT ;; esac
HERE=$AKSHARA_DEPLOY_HERE
REPO=$(cd "$HERE/../.." && pwd)
COMPOSE="$HERE/compose.sh"
HISTORY="$HERE/.deploy-history"
KEEP_IMAGES=${KEEP_IMAGES:-3}

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) deploy: $*"; }
die() { log "ERROR: $*"; exit 1; }
env_get() {
    v=$(sed -n "s/^$1=//p" "$HERE/.env" 2>/dev/null | tail -n 1)
    printf '%s' "${v:-$2}"
}
current_tag() { sed -n 's/^APP_TAG=//p' "$HERE/release.env" 2>/dev/null | tail -n 1; }

smoke_test() {
    domain=$(env_get APP_DOMAIN "")
    port=$(env_get HTTPS_PORT 443)
    insecure=""
    [ "$(env_get CADDY_TLS "")" = "internal" ] && insecure="-k"
    i=0
    while :; do
        if curl -fsS $insecure -o /dev/null --resolve "$domain:$port:127.0.0.1" "https://$domain:$port/login" \
            && "$COMPOSE" exec -T api wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '"UP"'; then
            return 0
        fi
        i=$((i + 1)); [ $i -lt 30 ] || return 1
        sleep 5
    done
}

checkout_sha() { # SHA from .deploy-history ("-" or empty: unknown, keep the current checkout)
    case "${1:--}" in
        -) log "no commit recorded for this version; the checkout stays as it is" ;;
        *) git -C "$REPO" checkout -q --detach "$1" ;;
    esac
}

roll_out() { # TAG SHA: switch release.env to TAG, start it, smoke test
    printf 'APP_TAG=%s\n' "$1" > "$HERE/release.env"
    log "starting $1"
    "$COMPOSE" up -d --remove-orphans --wait --wait-timeout 300 && smoke_test
}

prune_images() {
    # Tags to keep: the last KEEP_IMAGES deployed versions (one line, space separated) and the running one.
    keep=" $(tail -n "$KEEP_IMAGES" "$HISTORY" 2>/dev/null | cut -d' ' -f2 | tr '\n' ' ') $(current_tag) "
    for repo in akshara-api akshara-web; do
        docker image ls "$repo" --format '{{.Tag}}' | while read -r tag; do
            case "$keep" in *" $tag "*) continue ;; esac
            log "removing old image $repo:$tag"
            docker image rm "$repo:$tag" >/dev/null || true
        done
    done
    docker image prune -f >/dev/null
    docker builder prune -f --keep-storage 3GB >/dev/null 2>&1 || true
}

case "${1:-}" in
    --status)
        echo "running: $(current_tag)"
        echo "history (date tag sha ref):"
        tail -n 10 "$HISTORY" 2>/dev/null || echo "  (none)"
        "$COMPOSE" ps
        exit 0
        ;;
    --rollback)
        cur=$(current_tag)
        if [ -n "${2:-}" ]; then
            target=$2
        else
            target=$(cut -d' ' -f2 "$HISTORY" 2>/dev/null | grep -vx "$cur" | tail -n 1 || true)
        fi
        [ -n "$target" ] || die "no earlier version in $HISTORY"
        docker image inspect "akshara-api:$target" "akshara-web:$target" >/dev/null 2>&1 \
            || die "images for $target are no longer on this server; deploy that ref instead"
        sha=$(awk -v t="$target" '$2 == t {s = $3} END {print s}' "$HISTORY")
        checkout_sha "$sha"
        log "rolling back from $cur to $target"
        roll_out "$target" || die "rollback to $target is not healthy either: check ./compose.sh logs api"
        echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $target ${sha:--} rollback" >> "$HISTORY"
        log "rolled back to $target"
        exit 0
        ;;
    ""|-*) sed -n '2,15p' "$0"; exit 2 ;;
esac

REF=$1
[ -f "$HERE/.env" ] || die "no .env: run ./generate-secrets.sh first"
if [ -n "$(git -C "$REPO" status --porcelain --untracked-files=no)" ]; then
    die "the checkout at $REPO has local changes; commit or discard them first"
fi

log "fetching $REF"
git -C "$REPO" fetch -q --tags origin
SHA=$(git -C "$REPO" rev-parse --verify -q "origin/$REF^{commit}" || git -C "$REPO" rev-parse --verify "$REF^{commit}") \
    || die "unknown ref $REF"
TAG=$(git -C "$REPO" rev-parse --short=12 "$SHA")
PREV=$(current_tag)
[ "$TAG" != "$PREV" ] || die "$TAG is already running"

git -C "$REPO" checkout -q --detach "$SHA"
log "building $TAG ($REF) while $PREV keeps running"
APP_TAG=$TAG "$COMPOSE" build --pull api web || die "build failed; nothing changed"

if [ "${SKIP_BACKUP:-0}" != 1 ]; then
    "$HERE/backup/backup.sh" --no-upload || die "pre-deploy backup failed; nothing changed"
fi

if roll_out "$TAG"; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $TAG $SHA $REF" >> "$HISTORY"
    log "deployed $TAG"
    prune_images
    exit 0
fi

log "$TAG is not healthy"
"$COMPOSE" logs --tail 50 api web || true
if [ -n "$PREV" ] && [ "$PREV" != latest ] && docker image inspect "akshara-api:$PREV" >/dev/null 2>&1; then
    log "rolling back to $PREV"
    prev_sha=$(awk -v t="$PREV" '$2 == t {s = $3} END {print s}' "$HISTORY")
    checkout_sha "$prev_sha"
    if roll_out "$PREV"; then
        log "back on $PREV"
    else
        log "rollback to $PREV is not healthy either: check ./compose.sh logs api"
    fi
fi
exit 1
