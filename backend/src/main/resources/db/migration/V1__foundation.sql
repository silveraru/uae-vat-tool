-- Tenancy, identity and the tamper-evident audit log (NFR-02, NFR-04, NFR-05).

CREATE FUNCTION app_tenant() RETURNS uuid
  LANGUAGE sql STABLE
  AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

-- Applies the standard tenant policy. FORCE means even the table owner is subject
-- to the policy, so a mistaken owner connection still cannot cross tenants.
CREATE PROCEDURE protect_table(t regclass, privileges text DEFAULT 'SELECT, INSERT, UPDATE, DELETE')
  LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
  EXECUTE format('CREATE POLICY tenant_isolation ON %s USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant())', t);
  EXECUTE format('GRANT %s ON %s TO vat_app', privileges, t);
END $$;

CREATE FUNCTION forbid_mutation() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% on % is not permitted: table is append-only', TG_OP, TG_TABLE_NAME
    USING ERRCODE = 'restrict_violation';
END $$;

CREATE TABLE tenant (
  id          uuid PRIMARY KEY,
  name        text NOT NULL,
  kind        text NOT NULL CHECK (kind IN ('FIRM', 'DIRECT')),
  created_at  timestamptz NOT NULL DEFAULT now()
);
-- ENABLE without FORCE: list_tenant_ids() runs as the owner and must see every id
-- so background workers can visit each tenant. The app role still sees only its own.
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_self ON tenant USING (id = app_tenant()) WITH CHECK (id = app_tenant());
GRANT SELECT, INSERT, UPDATE ON tenant TO vat_app;

CREATE FUNCTION list_tenant_ids() RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
  AS $$ SELECT id FROM tenant $$;
GRANT EXECUTE ON FUNCTION list_tenant_ids() TO vat_app;

CREATE TABLE app_user (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL REFERENCES tenant(id),
  subject       text NOT NULL,
  email         text NOT NULL,
  display_name  text NOT NULL,
  side          text NOT NULL CHECK (side IN ('FIRM', 'CLIENT', 'EXTERNAL')),
  active        boolean NOT NULL DEFAULT true,
  locale        text NOT NULL DEFAULT 'en' CHECK (locale IN ('en', 'ar')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, subject),
  UNIQUE (tenant_id, id)
);
CALL protect_table('app_user');

-- Maps an identity provider subject to its tenant, so the tenant is never taken
-- from a token claim. The app role has no direct access to this table.
CREATE TABLE login_directory (
  subject    text PRIMARY KEY,
  tenant_id  uuid NOT NULL REFERENCES tenant(id),
  user_id    uuid NOT NULL
);

CREATE FUNCTION resolve_login(p_subject text)
  RETURNS TABLE (tenant_id uuid, user_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
  AS $$ SELECT d.tenant_id, d.user_id FROM login_directory d WHERE d.subject = p_subject $$;
GRANT EXECUTE ON FUNCTION resolve_login(text) TO vat_app;

CREATE FUNCTION register_login(p_subject text, p_user uuid) RETURNS void
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
  AS $$
BEGIN
  IF app_tenant() IS NULL THEN
    RAISE EXCEPTION 'register_login requires a tenant context';
  END IF;
  INSERT INTO login_directory (subject, tenant_id, user_id)
  VALUES (p_subject, app_tenant(), p_user)
  ON CONFLICT (subject) DO NOTHING;
END $$;
GRANT EXECUTE ON FUNCTION register_login(text, uuid) TO vat_app;

CREATE TABLE audit_chain_head (
  tenant_id  uuid PRIMARY KEY,
  last_seq   bigint NOT NULL,
  last_hash  text NOT NULL
);
CALL protect_table('audit_chain_head', 'SELECT, INSERT, UPDATE');

-- before_json and after_json are text, not jsonb, because jsonb normalises key
-- order and whitespace and the stored bytes must hash back to the same value.
CREATE TABLE audit_event (
  tenant_id    uuid NOT NULL,
  seq          bigint NOT NULL,
  occurred_at  timestamptz NOT NULL,
  actor_type   text NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM')),
  actor_id     text NOT NULL,
  action       text NOT NULL,
  object_type  text NOT NULL,
  object_id    text NOT NULL,
  entity_id    uuid,
  before_json  text,
  after_json   text,
  reason       text,
  prev_hash    text NOT NULL,
  hash         text NOT NULL,
  PRIMARY KEY (tenant_id, seq)
);
CALL protect_table('audit_event', 'SELECT, INSERT');
CREATE INDEX audit_event_object_idx ON audit_event (tenant_id, object_type, object_id, seq);
CREATE INDEX audit_event_entity_idx ON audit_event (tenant_id, entity_id, seq);

CREATE TRIGGER audit_event_no_change BEFORE UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER audit_event_no_truncate BEFORE TRUNCATE ON audit_event
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- Role grants per legal entity. entity_id NULL means across the whole tenant.
-- The foreign key to legal_entity is added with that table in V2.
CREATE TABLE entity_grant (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenant(id),
  user_id     uuid NOT NULL,
  entity_id   uuid,
  role        text NOT NULL CHECK (role IN ('PREPARER','APPROVER','TAX_REVIEWER','ENGAGEMENT_PARTNER','FIRM_ADMIN','AUDITOR')),
  valid_from  timestamptz NOT NULL DEFAULT now(),
  valid_to    timestamptz,
  granted_by  text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id)
);
CALL protect_table('entity_grant');
CREATE INDEX entity_grant_user_idx ON entity_grant (tenant_id, user_id);
CREATE INDEX entity_grant_entity_idx ON entity_grant (tenant_id, entity_id);
