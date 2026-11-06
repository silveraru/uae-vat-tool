-- vat_owner runs migrations and owns the schema.
-- vat_app is what the application connects as. It is not an owner, not a
-- superuser and cannot bypass row-level security.
CREATE ROLE vat_owner LOGIN PASSWORD 'vat_owner' NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS;
CREATE ROLE vat_app   LOGIN PASSWORD 'vat_app'   NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS;
ALTER SCHEMA public OWNER TO vat_owner;
GRANT USAGE ON SCHEMA public TO vat_app;
REVOKE CREATE ON SCHEMA public FROM vat_app;
