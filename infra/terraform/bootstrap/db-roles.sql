-- One-time PostgreSQL role bootstrap for an Akshara environment.
-- Run it before the first api-migrate task (and again after rotating either password).
--
-- Run as the RDS master user, connected to the `akshara` database, with the two passwords from
-- Secrets Manager passed as psql variables (never written into this file):
--
--   psql "host=<rds_endpoint> port=5432 dbname=akshara user=<master user> sslmode=require" \
--     -v owner_password="<password from akshara/<env>/db-owner>" \
--     -v app_password="<password from akshara/<env>/db-app>" \
--     -f db-roles.sql
--
-- Safe to re-run: roles are created only when missing and passwords are always (re)set.

\set ON_ERROR_STOP on

-- akshara_owner: owns the database and (through Flyway, run by api-migrate) every schema and table.
SELECT 'CREATE ROLE akshara_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'akshara_owner')
\gexec

-- akshara_app: what the API connects as. Owns nothing and cannot bypass row-level security;
-- table privileges are granted by the migrations.
SELECT 'CREATE ROLE akshara_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'akshara_app')
\gexec

ALTER ROLE akshara_owner WITH LOGIN PASSWORD :'owner_password';
ALTER ROLE akshara_app WITH LOGIN PASSWORD :'app_password';

-- The RDS master user is not a true superuser. PostgreSQL 16+ only lets it hand the database to a
-- role it can SET ROLE to, so it joins akshara_owner just long enough to transfer ownership.
GRANT akshara_owner TO CURRENT_USER;
ALTER DATABASE akshara OWNER TO akshara_owner;
REVOKE akshara_owner FROM CURRENT_USER;

\echo 'Roles akshara_owner and akshara_app are ready. Next: run the api-migrate task.'
