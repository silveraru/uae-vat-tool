-- Return engine (RET-01 to RET-04, RET-06).

ALTER TABLE tax_period ADD COLUMN data_fingerprint text;

-- Numbered, append-only versions. Only status can ever change, which column privileges enforce, so
-- a stored return's figures cannot be altered even by a bug in the application.
CREATE TABLE return_version (
  id                     uuid PRIMARY KEY,
  tenant_id              uuid NOT NULL,
  entity_id              uuid NOT NULL,
  period_id              uuid NOT NULL,
  version_no             integer NOT NULL,
  status                 text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','LOCKED','SUPERSEDED','CORRECTED')),
  form_code              text NOT NULL,
  form_version           text NOT NULL,
  rule_pack_id           uuid NOT NULL,
  rule_pack_version      text NOT NULL,
  rule_pack_signed_off   boolean NOT NULL,
  box_values             jsonb NOT NULL,
  member_values          jsonb NOT NULL,
  values_hash            text NOT NULL,
  data_fingerprint       text NOT NULL,
  diff                   jsonb,
  line_count             bigint NOT NULL,
  trigger                text NOT NULL,
  created_by             text NOT NULL,
  created_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, period_id, version_no),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('return_version', 'SELECT, INSERT');
GRANT UPDATE (status) ON return_version TO vat_app;

ALTER TABLE tax_period
  ADD FOREIGN KEY (tenant_id, locked_version_id) REFERENCES return_version (tenant_id, id);
