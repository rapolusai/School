#!/bin/sh
# Creates .env from .env.prod.example (if .env does not exist yet) and replaces every __GENERATE__ placeholder with
# a strong random value. Values already set are never touched, so it is safe to re-run. The file is chmod 600.
#   ./generate-secrets.sh            # then edit .env and fill in the REQUIRED values
set -eu
cd "$(dirname "$0")"
umask 077

if [ ! -f .env ]; then
    cp .env.prod.example .env
    echo "Created .env from .env.prod.example"
fi
chmod 600 .env

# random LENGTH: letters and digits only, so the values need no quoting in .env, URLs or psql.
random() {
    LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"
}

tmp=$(mktemp .env.XXXXXX)
trap 'rm -f "$tmp"' EXIT
generated=0
while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
        *=__GENERATE__)
            key=${line%%=*}
            case "$key" in
                JWT_SECRET) len=64 ;;
                PAYMENTS_SANDBOX_SECRET) len=48 ;;
                PLATFORM_ADMIN_PASSWORD) len=24 ;;
                *) len=32 ;;
            esac
            printf '%s=%s\n' "$key" "$(random "$len")"
            generated=$((generated + 1))
            ;;
        *) printf '%s\n' "$line" ;;
    esac
done < .env > "$tmp"
chmod 600 "$tmp"
mv "$tmp" .env
trap - EXIT

echo "Generated $generated secret(s) in $(pwd)/.env (mode 600)."
if grep -q 'REPLACE' .env; then
    echo "Still to fill in by hand:"
    grep -n 'REPLACE' .env | sed 's/^/  line /'
fi
