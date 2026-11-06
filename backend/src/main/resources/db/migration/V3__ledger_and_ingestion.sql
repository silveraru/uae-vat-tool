-- Ingestion, lineage and the canonical tax ledger (ING-01, 03, 05, 07, 08).

-- Rates are per tenant, so one firm's upload can never change another firm's returns.
CREATE TABLE fx_rate (
  tenant_id     uuid NOT NULL REFERENCES tenant(id),
  currency      text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  rate_date     date NOT NULL,
  aed_per_unit  numeric(18,8) NOT NULL CHECK (aed_per_unit > 0),
  source        text NOT NULL,
  loaded_by     text NOT NULL,
  loaded_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, currency, rate_date)
);
CALL protect_table('fx_rate');

CREATE TABLE ingest_batch (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  entity_id         uuid NOT NULL,
  kind              text NOT NULL CHECK (kind IN ('AR','AP','VAT_SUBLEDGER','GL','CUSTOMS','FX','OPENING_AP')),
  template_version  text NOT NULL,
  channel           text NOT NULL CHECK (channel IN ('API','UPLOAD','SFTP')),
  file_name         text NOT NULL,
  content_hash      text NOT NULL,
  storage_key       text,
  status            text NOT NULL CHECK (status IN ('PROCESSING','PROCESSED')),
  rows_total        integer NOT NULL DEFAULT 0,
  rows_loaded       integer NOT NULL DEFAULT 0,
  rows_unchanged    integer NOT NULL DEFAULT 0,
  rows_rejected     integer NOT NULL DEFAULT 0,
  rows_held         integer NOT NULL DEFAULT 0,
  created_by        text NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  finished_at       timestamptz,
  UNIQUE (tenant_id, entity_id, kind, content_hash),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('ingest_batch');
CREATE INDEX ingest_batch_entity_idx ON ingest_batch (tenant_id, entity_id, created_at DESC);

-- Every source row is kept as received, whatever became of it. That is the far end of the lineage.
CREATE TABLE ingest_row (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  batch_id      uuid NOT NULL,
  entity_id     uuid NOT NULL,
  row_no        integer NOT NULL,
  raw           jsonb NOT NULL,
  status        text NOT NULL CHECK (status IN ('LOADED','UNCHANGED','REJECTED','HELD')),
  errors        text[] NOT NULL DEFAULT '{}',
  row_key       text,
  period_id     uuid,
  document_id   uuid,
  resolved_at   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, batch_id) REFERENCES ingest_batch (tenant_id, id)
);
CALL protect_table('ingest_row');
CREATE INDEX ingest_row_batch_idx ON ingest_row (tenant_id, batch_id, row_no);
CREATE INDEX ingest_row_open_idx ON ingest_row (tenant_id, entity_id, status) WHERE status IN ('REJECTED','HELD') AND resolved_at IS NULL;

CREATE TABLE source_document (
  id                           uuid PRIMARY KEY,
  tenant_id                    uuid NOT NULL,
  filer_entity_id              uuid NOT NULL,
  member_entity_id             uuid NOT NULL,
  source_system                text NOT NULL CHECK (source_system IN ('ERP','CUSTOMS','MANUAL','OPENING')),
  direction                    text NOT NULL CHECK (direction IN ('OUT','IN')),
  doc_type                     text NOT NULL,
  doc_number                   text NOT NULL,
  doc_date                     date NOT NULL,
  natural_key                  text NOT NULL,
  counterparty_trn             text,
  counterparty_name            text,
  counterparty_country         text,
  customer_type                text CHECK (customer_type IN ('B2B','B2G','B2C')),
  original_doc_number          text,
  einvoice_uuid                uuid,
  posting_date                 date,
  has_tax_invoice              boolean NOT NULL DEFAULT false,
  agreed_payment_date          date,
  paid_date                    date,
  paid_amount_aed              numeric(18,2),
  gross_aed                    numeric(18,2),
  declared_vat_aed             numeric(18,2),
  opening_recoverable_vat_aed  numeric(18,2),
  first_batch_id               uuid,
  last_batch_id                uuid,
  voided_at                    timestamptz,
  created_at                   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, filer_entity_id, source_system, direction, natural_key),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, filer_entity_id) REFERENCES legal_entity (tenant_id, id),
  FOREIGN KEY (tenant_id, member_entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('source_document');
CREATE INDEX source_document_lookup_idx ON source_document (tenant_id, filer_entity_id, direction, doc_date);
CREATE INDEX source_document_number_idx ON source_document (tenant_id, filer_entity_id, direction, counterparty_trn, doc_number);
CREATE INDEX source_document_uuid_idx ON source_document (tenant_id, einvoice_uuid) WHERE einvoice_uuid IS NOT NULL;

-- The canonical ledger. Rows are never changed: a correction or reclassification appends a new
-- revision and stamps superseded_at on the old one, the only update the trigger below allows.
-- Foreign keys are left out on purpose: PostgreSQL requires any reference to a partitioned table
-- to include its partition key, and the write path already validates these links.
CREATE TABLE tax_line (
  id                   uuid NOT NULL,
  tenant_id            uuid NOT NULL,
  tax_year             integer NOT NULL,
  logical_line_id      uuid NOT NULL,
  revision             integer NOT NULL,
  filer_entity_id      uuid NOT NULL,
  member_entity_id     uuid NOT NULL,
  establishment_id     uuid,
  emirate              text,
  period_id            uuid NOT NULL,
  document_id          uuid,
  ingest_row_id        uuid,
  line_no              integer NOT NULL,
  line_kind            text NOT NULL CHECK (line_kind IN ('NORMAL','ADJUSTMENT')),
  adj_box              text,
  adjustment_id        uuid,
  source_system        text NOT NULL,
  direction            text NOT NULL CHECK (direction IN ('OUT','IN')),
  goods_or_services    text,
  gl_account           text,
  tax_code             text,
  description          text,
  counterparty_trn     text,
  counterparty_country text,
  tax_point            date NOT NULL,
  currency             text NOT NULL,
  fx_rate              numeric(18,8) NOT NULL,
  net_doc              numeric(18,2) NOT NULL,
  vat_doc              numeric(18,2) NOT NULL,
  net_aed              numeric(18,2) NOT NULL,
  vat_aed              numeric(18,2) NOT NULL,
  recoverable_vat_aed  numeric(18,2) NOT NULL DEFAULT 0,
  recovery_pct         numeric(7,4),
  treatment            text NOT NULL,
  blocked_category     text,
  drc_category         text,
  place_of_supply      text,
  memo                 boolean NOT NULL DEFAULT false,
  review_flags         text[] NOT NULL DEFAULT '{}',
  classified_by        text NOT NULL CHECK (classified_by IN ('MAPPING','RULE','USER','SYSTEM','NONE')),
  confidence           numeric(4,3),
  rule_ids             text[] NOT NULL DEFAULT '{}',
  rule_pack_version    text NOT NULL,
  mapping_version_id   uuid,
  change_reason_code   text,
  change_reason        text,
  changed_by           text NOT NULL,
  tax_hash             text NOT NULL,
  created_at           timestamptz NOT NULL DEFAULT now(),
  superseded_at        timestamptz,
  PRIMARY KEY (tenant_id, tax_year, id)
) PARTITION BY HASH (tenant_id);

DO $$
DECLARE
  h int;
BEGIN
  FOR h IN 0..7 LOOP
    EXECUTE format('CREATE TABLE tax_line_h%s PARTITION OF tax_line FOR VALUES WITH (MODULUS 8, REMAINDER %s) PARTITION BY RANGE (tax_year)', h, h);
    EXECUTE format('CREATE TABLE tax_line_h%s_to2025 PARTITION OF tax_line_h%s FOR VALUES FROM (MINVALUE) TO (2026)', h, h);
    EXECUTE format('CREATE TABLE tax_line_h%s_2026 PARTITION OF tax_line_h%s FOR VALUES FROM (2026) TO (2027)', h, h);
    EXECUTE format('CREATE TABLE tax_line_h%s_2027 PARTITION OF tax_line_h%s FOR VALUES FROM (2027) TO (2028)', h, h);
    EXECUTE format('CREATE TABLE tax_line_h%s_2028 PARTITION OF tax_line_h%s FOR VALUES FROM (2028) TO (2029)', h, h);
    EXECUTE format('CREATE TABLE tax_line_h%s_2029 PARTITION OF tax_line_h%s FOR VALUES FROM (2029) TO (2030)', h, h);
    EXECUTE format('CREATE TABLE tax_line_h%s_from2030 PARTITION OF tax_line_h%s FOR VALUES FROM (2030) TO (MAXVALUE)', h, h);
  END LOOP;
END $$;

CALL protect_table('tax_line', 'SELECT, INSERT, UPDATE');
CREATE INDEX tax_line_current_period_idx ON tax_line (tenant_id, period_id, direction, treatment) WHERE superseded_at IS NULL;
CREATE INDEX tax_line_logical_idx ON tax_line (tenant_id, logical_line_id, revision);
CREATE INDEX tax_line_document_idx ON tax_line (tenant_id, document_id) WHERE superseded_at IS NULL;
CREATE INDEX tax_line_period_changes_idx ON tax_line (tenant_id, period_id, created_at);
CREATE INDEX tax_line_period_supersede_idx ON tax_line (tenant_id, period_id, superseded_at) WHERE superseded_at IS NOT NULL;

CREATE FUNCTION tax_line_guard() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'tax lines cannot be deleted' USING ERRCODE = 'restrict_violation';
  END IF;
  IF OLD.superseded_at IS NOT NULL
     OR NEW.superseded_at IS NULL
     OR (to_jsonb(NEW) - 'superseded_at') IS DISTINCT FROM (to_jsonb(OLD) - 'superseded_at') THEN
    RAISE EXCEPTION 'tax lines are immutable: only superseding a current line is allowed'
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tax_line_immutable BEFORE UPDATE OR DELETE ON tax_line
  FOR EACH ROW EXECUTE FUNCTION tax_line_guard();

-- Set C for reconciliation: VAT control account movements for a period.
CREATE TABLE gl_balance (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  entity_id     uuid NOT NULL,
  period_id     uuid NOT NULL,
  account_code  text NOT NULL,
  account_type  text NOT NULL CHECK (account_type IN ('OUTPUT','INPUT','REVERSE_CHARGE','IMPORT')),
  balance_aed   numeric(18,2) NOT NULL,
  batch_id      uuid NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, period_id, account_code),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id)
);
CALL protect_table('gl_balance');

-- Set A for reconciliation: e-invoices as the ASP reports them.
CREATE TABLE einvoice (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  filer_entity_id    uuid NOT NULL,
  invoice_uuid       uuid NOT NULL,
  direction          text CHECK (direction IN ('ISSUED','RECEIVED')),
  doc_type           text CHECK (doc_type IN ('INVOICE','CREDIT_NOTE','DEBIT_NOTE')),
  number             text,
  issue_date         date,
  supplier_trn       text,
  buyer_trn          text,
  currency           text,
  net_aed            numeric(18,2),
  vat_aed            numeric(18,2),
  billing_reference  text,
  lifecycle_status   text CHECK (lifecycle_status IN ('ISSUED','RECEIVED','REJECTED')),
  lifecycle_at       timestamptz,
  rejection_reason   text,
  corner5_status     text NOT NULL DEFAULT 'PENDING' CHECK (corner5_status IN ('PENDING','REPORTED','FAILED')),
  corner5_at         timestamptz,
  has_invoice_data   boolean NOT NULL DEFAULT false,
  created_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, invoice_uuid),
  FOREIGN KEY (tenant_id, filer_entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('einvoice');
CREATE INDEX einvoice_period_idx ON einvoice (tenant_id, filer_entity_id, direction, issue_date);
CREATE INDEX einvoice_number_idx ON einvoice (tenant_id, filer_entity_id, number);

-- Events seen, so redelivery from Kafka is harmless.
CREATE TABLE einvoice_event (
  tenant_id     uuid NOT NULL REFERENCES tenant(id),
  event_id      uuid NOT NULL,
  invoice_uuid  uuid NOT NULL,
  event_type    text NOT NULL,
  occurred_at   timestamptz NOT NULL,
  received_at   timestamptz NOT NULL DEFAULT now(),
  payload       jsonb NOT NULL,
  PRIMARY KEY (tenant_id, event_id)
);
CALL protect_table('einvoice_event', 'SELECT, INSERT');

-- Periods whose return needs recomputing. The recompute job clears entries as it handles them.
CREATE TABLE period_recompute_request (
  period_id     uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  requested_at  timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id)
);
CALL protect_table('period_recompute_request');
