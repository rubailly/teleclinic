#!/bin/bash
# Runs once, on the node that initialises the cluster. Patroni passes a superuser connection URL as $1.
set -euo pipefail
psql "$1" -v ON_ERROR_STOP=1 \
  -v app_user="$APP_DB_USER" -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';
CREATE DATABASE teleclinic OWNER :"app_user";
-- pgAudit per role: every statement by an administrator; schema and role changes by the application, whose
-- data access is recorded with more meaning (who, patient, purpose) by the application audit interceptor
ALTER ROLE postgres SET pgaudit.log = 'read, write, ddl, role';
ALTER ROLE :"app_user" SET pgaudit.log = 'ddl, role';
\c teleclinic
CREATE EXTENSION IF NOT EXISTS pgaudit;
SQL
