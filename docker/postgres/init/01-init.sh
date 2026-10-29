#!/bin/bash
# Runs once, when the volume is created (official postgres image entrypoint).
# - nexora_reports: audit database. Flyway migrates it as the owner; the application uses nexora_reports_app.
# - synthetic_erp: SYNTHETIC enterprise data, read by Reports through the read-only role nexora_reader.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname nexora_reports \
     -v app_password="$REPORTS_DB_APP_PASSWORD" <<'EOSQL'
CREATE ROLE nexora_reports_app LOGIN PASSWORD :'app_password';
REVOKE ALL ON DATABASE nexora_reports FROM PUBLIC;
GRANT CONNECT ON DATABASE nexora_reports TO nexora_reports_app;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname nexora_reports -c "CREATE DATABASE synthetic_erp"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname synthetic_erp \
     -f /docker-entrypoint-initdb.d/sql/synthetic-erp.sql

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname synthetic_erp \
     -v reader_password="$SYNTHETIC_READER_PASSWORD" <<'EOSQL'
CREATE ROLE nexora_reader LOGIN PASSWORD :'reader_password';
REVOKE ALL ON DATABASE synthetic_erp FROM PUBLIC;
GRANT CONNECT ON DATABASE synthetic_erp TO nexora_reader;
REVOKE ALL ON SCHEMA public FROM nexora_reader;
GRANT USAGE ON SCHEMA plant, finance, docs TO nexora_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA plant, finance, docs TO nexora_reader;
ALTER ROLE nexora_reader SET default_transaction_read_only = on;
EOSQL
