#!/bin/sh
# Runs once, when the database volume is first created (docker-entrypoint-initdb.d): creates the two roles the API
# expects with infra/terraform/bootstrap/db-roles.sql, the same script the AWS setup uses.
#   akshara_owner owns the schema and runs migrations (api-migrate only).
#   akshara_app is what the API connects as: owns nothing, cannot bypass row-level security.
# To re-set both passwords after changing them in .env (the script is idempotent):
#   ./compose.sh exec db sh /docker-entrypoint-initdb.d/10-roles.sh
set -eu
psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER:-postgres}" --dbname "${POSTGRES_DB:-akshara}" \
    -v owner_password="$DB_OWNER_PASSWORD" -v app_password="$DB_APP_PASSWORD" \
    -f /akshara/db-roles.sql
