-- Onboarding and master data (ONB-01 to ONB-05, ONB-07 flag).

CREATE TABLE legal_entity (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid NOT NULL REFERENCES tenant(id),
  legal_name            text NOT NULL,
  legal_name_ar         text,
  trn                   text CHECK (trn IS NULL OR trn ~ '^[0-9]{15}$'),
  entity_kind           text NOT NULL CHECK (entity_kind IN ('STANDALONE', 'GROUP_REPRESENTATIVE', 'GROUP_MEMBER')),
  group_rep_id          uuid,
  filing_frequency      text CHECK (filing_frequency IN ('MONTHLY', 'QUARTERLY')),
  stagger               text,
  first_period_start    date CHECK (first_period_start IS NULL OR EXTRACT(DAY FROM first_period_start) = 1),
  einvoice_go_live      date,
  default_recovery_pct  numeric(7,4) NOT NULL DEFAULT 100 CHECK (default_recovery_pct BETWEEN 0 AND 100),
  partner_threshold_aed numeric(18,2),
  require_reviewer      boolean NOT NULL DEFAULT true,
  created_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, group_rep_id) REFERENCES legal_entity (tenant_id, id),
  -- A group member files through its representative: it has a parent and no calendar of its own.
  CHECK ((entity_kind = 'GROUP_MEMBER') = (group_rep_id IS NOT NULL)),
  CHECK (entity_kind = 'GROUP_MEMBER'
         OR (trn IS NOT NULL AND filing_frequency IS NOT NULL AND first_period_start IS NOT NULL))
);
CALL protect_table('legal_entity');
CREATE UNIQUE INDEX legal_entity_filer_trn_uq ON legal_entity (tenant_id, trn) WHERE entity_kind <> 'GROUP_MEMBER';
CREATE INDEX legal_entity_group_idx ON legal_entity (tenant_id, group_rep_id);

ALTER TABLE entity_grant
  ADD FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id);

CREATE TABLE establishment (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  entity_id        uuid NOT NULL,
  code             text NOT NULL,
  name             text NOT NULL,
  emirate          text NOT NULL CHECK (emirate IN ('AUH','DXB','SHJ','AJM','UAQ','RAK','FUJ')),
  designated_zone  boolean NOT NULL DEFAULT false,
  zone_name        text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, entity_id, code),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('establishment');

-- Doubles as the local TRN register for reconciliation check R08, because no public FTA
-- verification API was found.
CREATE TABLE counterparty (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL REFERENCES tenant(id),
  trn              text NOT NULL CHECK (trn ~ '^[0-9]{15}$'),
  name             text NOT NULL,
  country          text NOT NULL DEFAULT 'AE' CHECK (country ~ '^[A-Z]{2}$'),
  designated_zone  boolean NOT NULL DEFAULT false,
  trn_status       text NOT NULL DEFAULT 'UNKNOWN' CHECK (trn_status IN ('ACTIVE','INACTIVE','UNKNOWN')),
  active_from      date,
  active_to        date,
  checked_on       date,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, trn)
);
CALL protect_table('counterparty');

CREATE TABLE tax_agency (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenant(id),
  name        text NOT NULL,
  tan         text NOT NULL,
  UNIQUE (tenant_id, tan),
  UNIQUE (tenant_id, id)
);
CALL protect_table('tax_agency');

CREATE TABLE tax_agent (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  agency_id   uuid NOT NULL,
  name        text NOT NULL,
  taan        text NOT NULL,
  UNIQUE (tenant_id, taan),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, agency_id) REFERENCES tax_agency (tenant_id, id)
);
CALL protect_table('tax_agent');

CREATE TABLE entity_tax_agent (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  entity_id   uuid NOT NULL,
  agent_id    uuid NOT NULL,
  valid_from  date NOT NULL,
  valid_to    date,
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id),
  FOREIGN KEY (tenant_id, agent_id) REFERENCES tax_agent (tenant_id, id)
);
CALL protect_table('entity_tax_agent');

CREATE TABLE authorised_signatory (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  entity_id   uuid NOT NULL,
  name        text NOT NULL,
  email       text NOT NULL,
  position    text,
  valid_from  date NOT NULL,
  valid_to    date,
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('authorised_signatory');

-- Filing calendar. A period always belongs to the filer (the standalone entity or group representative).
CREATE TABLE tax_period (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  entity_id          uuid NOT NULL,
  period_start       date NOT NULL,
  period_end         date NOT NULL,
  due_date           date NOT NULL,
  state              text NOT NULL DEFAULT 'OPEN'
                     CHECK (state IN ('OPEN','IN_REVIEW','APPROVED','FILED','PAID','AMENDED')),
  locked_version_id  uuid,
  locked_at          timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, entity_id, period_start),
  UNIQUE (tenant_id, id),
  CHECK (period_end > period_start),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('tax_period');
CREATE INDEX tax_period_due_idx ON tax_period (tenant_id, due_date);

-- Versioned tax code and GL mapping (ONB-04). Versions are append-only, so what applied on any
-- past date can always be shown.
CREATE TABLE mapping_version (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  entity_id       uuid NOT NULL,
  version_no      integer NOT NULL,
  effective_from  date NOT NULL,
  note            text,
  created_by      text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, entity_id, version_no),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('mapping_version', 'SELECT, INSERT');

CREATE TABLE mapping_entry (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  mapping_version_id  uuid NOT NULL,
  match_type          text NOT NULL CHECK (match_type IN ('TAX_CODE', 'GL_ACCOUNT', 'VENDOR_TRN')),
  source_code         text NOT NULL,
  treatment           text CHECK (treatment IN ('STANDARD','ZERO_RATED','EXEMPT','OUT_OF_SCOPE','REVERSE_CHARGE','IMPORT','IMPORT_ADJUSTMENT','DRC','TOURIST_REFUND')),
  blocked_category    text,
  drc_category        text,
  UNIQUE (tenant_id, mapping_version_id, match_type, source_code),
  FOREIGN KEY (tenant_id, mapping_version_id) REFERENCES mapping_version (tenant_id, id),
  CHECK (treatment IS NOT NULL OR blocked_category IS NOT NULL OR drc_category IS NOT NULL)
);
CALL protect_table('mapping_entry', 'SELECT, INSERT');

CREATE TRIGGER mapping_version_no_change BEFORE UPDATE OR DELETE ON mapping_version
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER mapping_entry_no_change BEFORE UPDATE OR DELETE ON mapping_entry
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Rule packs are stored per tenant, so each firm's tax team signs off the version it relies on.
CREATE TABLE rule_pack (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenant(id),
  jurisdiction    text NOT NULL,
  version         text NOT NULL,
  effective_from  date NOT NULL,
  effective_to    date,
  status          text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SIGNED_OFF','RETIRED')),
  content_yaml    text NOT NULL,
  content_hash    text NOT NULL,
  loaded_by       text NOT NULL,
  loaded_at       timestamptz NOT NULL DEFAULT now(),
  signed_off_by   text,
  signed_off_at   timestamptz,
  sign_off_note   text,
  UNIQUE (tenant_id, jurisdiction, version)
);
CALL protect_table('rule_pack');
