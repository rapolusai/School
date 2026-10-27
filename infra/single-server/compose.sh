#!/bin/sh
# docker compose for the production stack, with .env (settings and secrets) and release.env (APP_TAG, the image tag
# that runs; written by deploy.sh). Use it for everything: ./compose.sh ps | logs -f api | exec db psql -U postgres
set -eu
cd "$(dirname "$0")"
if [ ! -f .env ]; then
    echo "No .env here: run ./generate-secrets.sh and fill in the REQUIRED values first." >&2
    exit 1
fi
[ -f release.env ] || echo "APP_TAG=latest" > release.env
exec docker compose -f docker-compose.prod.yml --env-file .env --env-file release.env "$@"
