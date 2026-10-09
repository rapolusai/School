#!/bin/sh
# Local development only: creates the two database roles the API expects.
#   akshara_owner owns the schema and runs migrations.
#   akshara_app is what the API connects as. It owns nothing and cannot bypass row-level security.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v owner_password="$DB_OWNER_PASSWORD" -v app_password="$DB_APP_PASSWORD" <<'EOSQL'
create role akshara_owner login password :'owner_password';
create role akshara_app login password :'app_password' nosuperuser nobypassrls;
alter database akshara owner to akshara_owner;
EOSQL
