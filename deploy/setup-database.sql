-- One-time setup of a fresh production database on the homelab's PostgreSQL.
-- Run as the postgres superuser, with a new strong password:
--
--   sudo -u postgres psql -v app_password="'<new strong password>'" -f deploy/setup-database.sql
--
-- Then allow the app's Docker network in pg_hba.conf (see docs/deployment.md)
-- and reload PostgreSQL. The app creates its tables itself (the migrate service).

CREATE ROLE fet_app LOGIN PASSWORD :app_password;
CREATE DATABASE fet_prod OWNER fet_app ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE fet_prod FROM PUBLIC;
\connect fet_prod
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO fet_app;
