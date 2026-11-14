-- Editing the firm's rule pack, and rules for one client on top of it (NFR-10).

-- An edit is a new version that names the one it was copied from and says why. A pack is never
-- changed in place: the application may only sign it off or retire it.
ALTER TABLE rule_pack ADD COLUMN based_on uuid REFERENCES rule_pack (id);
ALTER TABLE rule_pack ADD COLUMN edit_note text;
ALTER TABLE rule_pack ADD COLUMN retired_by text;
ALTER TABLE rule_pack ADD COLUMN retired_at timestamptz;
ALTER TABLE rule_pack ADD COLUMN retire_note text;
REVOKE UPDATE ON rule_pack FROM vat_app;
GRANT UPDATE (status, signed_off_by, signed_off_at, sign_off_note, retired_by, retired_at, retire_note) ON rule_pack TO vat_app;

-- Client rules add to the firm's pack for one filer, or relax and tighten its review thresholds.
-- They cannot change the law: rates, the form, deadlines and penalties come only from the pack.
CREATE TABLE client_rule_version (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  entity_id       uuid NOT NULL,
  version_no      integer NOT NULL,
  effective_from  date NOT NULL,
  content         jsonb NOT NULL,
  content_hash    text NOT NULL,
  note            text NOT NULL,
  status          text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SIGNED_OFF')),
  created_by      text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  signed_off_by   text,
  signed_off_at   timestamptz,
  sign_off_note   text,
  UNIQUE (tenant_id, entity_id, version_no),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('client_rule_version', 'SELECT, INSERT');
GRANT UPDATE (status, signed_off_by, signed_off_at, sign_off_note) ON client_rule_version TO vat_app;
CREATE INDEX client_rule_version_idx ON client_rule_version (tenant_id, entity_id, effective_from DESC, version_no DESC);

-- Which client rules classified a line, and which were in force when a return was computed.
ALTER TABLE tax_line ADD COLUMN client_rule_version_id uuid;
ALTER TABLE return_version ADD COLUMN client_rule_version_id uuid;
