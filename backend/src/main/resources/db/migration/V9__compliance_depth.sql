-- Phase 2 and 3 schema: corrections, refunds, audit support, bank and other-ASP ingestion, input tax
-- schemes, classification suggestions, e-signature, practice tools, analytics inputs, connectors,
-- tenant keys and retention.

-- A generated file: the row says what it is, and locked storage holds it.
CREATE TABLE generated_document (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL REFERENCES tenant(id),
  entity_id     uuid NOT NULL,
  period_id     uuid,
  kind          text NOT NULL,
  object_id     text NOT NULL,
  file_name     text NOT NULL,
  content_type  text NOT NULL,
  storage_key   text NOT NULL,
  sha256        text NOT NULL,
  size_bytes    bigint NOT NULL,
  draft_rules   boolean NOT NULL,
  created_by    text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('generated_document', 'SELECT, INSERT');
CREATE INDEX generated_document_object_idx ON generated_document (tenant_id, kind, object_id, created_at DESC);

ALTER TABLE return_pack ADD COLUMN pdf_ar_key text, ADD COLUMN xlsx_ar_key text;

ALTER TABLE ingest_batch DROP CONSTRAINT ingest_batch_kind_check;
ALTER TABLE ingest_batch ADD CONSTRAINT ingest_batch_kind_check
  CHECK (kind IN ('AR','AP','VAT_SUBLEDGER','GL','CUSTOMS','FX','OPENING_AP','UBL','GL_JOURNAL','MARGIN','OPENING_ASSETS','OPENING_APPORTIONMENT'));

ALTER TABLE adjustment DROP CONSTRAINT adjustment_type_check;
ALTER TABLE adjustment ADD CONSTRAINT adjustment_type_check
  CHECK (type IN ('SUPPLIER_REVERSAL','SUPPLIER_REINSTATEMENT','MANUAL_JOURNAL','DEEMED_SUPPLY','PRIOR_PERIOD_CORRECTION',
                  'BAD_DEBT_RELIEF','BAD_DEBT_RECOVERY','APPORTIONMENT','APPORTIONMENT_ANNUAL','CAPITAL_ASSET'));

ALTER TABLE evidence_link DROP CONSTRAINT evidence_link_object_type_check;
ALTER TABLE evidence_link ADD CONSTRAINT evidence_link_object_type_check
  CHECK (object_type IN ('PERIOD','BOX','LINE','EXCEPTION','ADJUSTMENT','CORRECTION','FILING','PAYMENT','DOCUMENT','DISCLOSURE','REFUND','AUDIT_CASE','AUDIT_REQUEST','ASSET','BAD_DEBT'));

ALTER TABLE legal_entity
  ADD COLUMN fta_giban text,
  ADD COLUMN require_esignature boolean NOT NULL DEFAULT false,
  ADD COLUMN tax_year_start_month integer CHECK (tax_year_start_month BETWEEN 1 AND 12);

-- Where an e-invoice in set A came from: the platform's events, or an import from another ASP.
ALTER TABLE einvoice ADD COLUMN source text NOT NULL DEFAULT 'ASP' CHECK (source IN ('ASP','UBL_IMPORT'));

-- ---- Voluntary disclosure (COR-02) ----
CREATE TABLE voluntary_disclosure (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  entity_id          uuid NOT NULL,
  period_id          uuid NOT NULL,
  correction_id      uuid NOT NULL,
  discovered_on      date NOT NULL,
  due_by             date NOT NULL,
  nature_of_error    text NOT NULL,
  status             text NOT NULL CHECK (status IN ('DRAFT','APPROVED','SUBMITTED','SETTLED')),
  penalty_estimate   jsonb NOT NULL,
  created_by         text NOT NULL,
  created_at         timestamptz NOT NULL DEFAULT now(),
  approved_by        text,
  approved_at        timestamptz,
  submission_ref     text,
  submitted_on       date,
  ack_evidence_id    uuid,
  settled_on         date,
  settled_amount_aed numeric(18,2),
  giban_reference    text,
  UNIQUE (tenant_id, correction_id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, correction_id) REFERENCES correction_case (tenant_id, id),
  FOREIGN KEY (tenant_id, ack_evidence_id) REFERENCES evidence (tenant_id, id)
);
CALL protect_table('voluntary_disclosure', 'SELECT, INSERT, UPDATE');

-- ---- Refund requests (COR-04) ----
CREATE TABLE refund_request (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  entity_id           uuid NOT NULL,
  period_id           uuid NOT NULL,
  version_id          uuid NOT NULL,
  amount_aed          numeric(18,2) NOT NULL CHECK (amount_aed > 0),
  time_limit_on       date NOT NULL,
  transitional        boolean NOT NULL DEFAULT false,
  status              text NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','RECEIVED')),
  created_by          text NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  submission_ref      text,
  submitted_on        date,
  ack_evidence_id     uuid,
  decided_on          date,
  approved_amount_aed numeric(18,2),
  decision_note       text,
  received_on         date,
  received_amount_aed numeric(18,2),
  bank_txn_id         uuid,
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, version_id) REFERENCES return_version (tenant_id, id),
  FOREIGN KEY (tenant_id, ack_evidence_id) REFERENCES evidence (tenant_id, id)
);
CALL protect_table('refund_request', 'SELECT, INSERT, UPDATE');
CREATE UNIQUE INDEX refund_one_live_per_period ON refund_request (tenant_id, period_id) WHERE status <> 'REJECTED';

-- ---- Audit cases (AUD-03) and the general ledger listing the FAF needs (AUD-01) ----
CREATE TABLE audit_case (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  entity_id      uuid NOT NULL,
  fta_reference  text NOT NULL,
  notified_on    date NOT NULL,
  period_from    date NOT NULL,
  period_to      date NOT NULL,
  description    text,
  status         text NOT NULL CHECK (status IN ('OPEN','CLOSED')),
  outcome        text,
  opened_by      text NOT NULL,
  opened_at      timestamptz NOT NULL DEFAULT now(),
  closed_by      text,
  closed_at      timestamptz,
  UNIQUE (tenant_id, id),
  CHECK (period_to >= period_from),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('audit_case', 'SELECT, INSERT, UPDATE');
CREATE INDEX audit_case_entity_idx ON audit_case (tenant_id, entity_id, status);

CREATE TABLE audit_request (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  case_id        uuid NOT NULL,
  description    text NOT NULL,
  requested_on   date NOT NULL,
  due_on         date NOT NULL,
  status         text NOT NULL CHECK (status IN ('OPEN','RESPONDED')),
  response_note  text,
  responded_by   text,
  responded_on   date,
  alerted_on     date,
  created_by     text NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, case_id) REFERENCES audit_case (tenant_id, id)
);
CALL protect_table('audit_request', 'SELECT, INSERT, UPDATE');
CREATE INDEX audit_request_due_idx ON audit_request (tenant_id, status, due_on);

CREATE TABLE gl_journal_line (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  entity_id           uuid NOT NULL,
  transaction_date    date NOT NULL,
  account_id          text NOT NULL,
  account_name        text,
  description         text,
  party_name          text,
  transaction_id      text NOT NULL,
  line_no             integer NOT NULL DEFAULT 1,
  source_document_id  text,
  source_type         text,
  debit_aed           numeric(18,2) NOT NULL DEFAULT 0,
  credit_aed          numeric(18,2) NOT NULL DEFAULT 0,
  balance_aed         numeric(18,2) NOT NULL DEFAULT 0,
  batch_id            uuid NOT NULL,
  UNIQUE (tenant_id, entity_id, transaction_id, line_no),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('gl_journal_line', 'SELECT, INSERT, UPDATE');
CREATE INDEX gl_journal_date_idx ON gl_journal_line (tenant_id, entity_id, transaction_date);

-- ---- Bank statements (ING-06, FIL-03) ----
CREATE TABLE bank_statement (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  entity_id         uuid NOT NULL,
  format            text NOT NULL CHECK (format IN ('MT940','CAMT053')),
  file_name         text NOT NULL,
  content_hash      text NOT NULL,
  evidence_id       uuid NOT NULL,
  account           text NOT NULL,
  statement_ref     text,
  opening_balance   numeric(18,2),
  closing_balance   numeric(18,2),
  balance_checked   boolean NOT NULL DEFAULT false,
  balance_problem   text,
  txn_count         integer NOT NULL,
  txn_new           integer NOT NULL,
  loaded_by         text NOT NULL,
  loaded_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, entity_id, content_hash),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id),
  FOREIGN KEY (tenant_id, evidence_id) REFERENCES evidence (tenant_id, id)
);
CALL protect_table('bank_statement', 'SELECT, INSERT');
GRANT UPDATE (txn_new) ON bank_statement TO vat_app;

CREATE TABLE bank_txn (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  entity_id      uuid NOT NULL,
  statement_id   uuid NOT NULL,
  account        text NOT NULL,
  booked_on      date NOT NULL,
  value_on       date,
  amount         numeric(18,2) NOT NULL CHECK (amount > 0),
  currency       text NOT NULL,
  direction      text NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  reference      text,
  counterparty   text,
  narrative      text,
  dedupe_key     text NOT NULL,
  UNIQUE (tenant_id, entity_id, dedupe_key),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, statement_id) REFERENCES bank_statement (tenant_id, id)
);
CALL protect_table('bank_txn', 'SELECT, INSERT');
CREATE INDEX bank_txn_lookup_idx ON bank_txn (tenant_id, entity_id, direction, booked_on);

-- Every match stores its method and score, as for e-invoices.
CREATE TABLE bank_doc_match (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  entity_id    uuid NOT NULL,
  bank_txn_id  uuid NOT NULL,
  document_id  uuid NOT NULL,
  amount_aed   numeric(18,2) NOT NULL,
  method       text NOT NULL,
  score        numeric(4,3) NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, bank_txn_id),
  FOREIGN KEY (tenant_id, bank_txn_id) REFERENCES bank_txn (tenant_id, id),
  FOREIGN KEY (tenant_id, document_id) REFERENCES source_document (tenant_id, id)
);
CALL protect_table('bank_doc_match', 'SELECT, INSERT');
CREATE INDEX bank_doc_match_doc_idx ON bank_doc_match (tenant_id, document_id);

ALTER TABLE payment ADD COLUMN bank_txn_id uuid;
ALTER TABLE payment ADD FOREIGN KEY (tenant_id, bank_txn_id) REFERENCES bank_txn (tenant_id, id);
CREATE UNIQUE INDEX payment_one_per_bank_txn ON payment (tenant_id, bank_txn_id) WHERE bank_txn_id IS NOT NULL;

-- ---- Input tax schemes (ADJ-02, ADJ-03, ADJ-04, RET-05, ONB-06) ----
-- TAXABLE and EXEMPT are direct attribution. RESIDUAL is what apportionment acts on. Set when a line is written.
ALTER TABLE tax_line ADD COLUMN input_attribution text CHECK (input_attribution IN ('TAXABLE','EXEMPT','RESIDUAL'));

CREATE TABLE bad_debt_claim (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  entity_id           uuid NOT NULL,
  period_id           uuid NOT NULL,
  document_id         uuid NOT NULL,
  kind                text NOT NULL CHECK (kind IN ('RELIEF','RECOVERY')),
  amount_aed          numeric(18,2) NOT NULL CHECK (amount_aed > 0),
  vat_aed             numeric(18,2) NOT NULL CHECK (vat_aed > 0),
  written_off_on      date,
  notified_on         date,
  writeoff_evidence_id uuid NOT NULL,
  notice_evidence_id   uuid,
  checklist           jsonb NOT NULL,
  adjustment_ids      uuid[] NOT NULL,
  created_by          text NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, document_id) REFERENCES source_document (tenant_id, id),
  FOREIGN KEY (tenant_id, writeoff_evidence_id) REFERENCES evidence (tenant_id, id),
  FOREIGN KEY (tenant_id, notice_evidence_id) REFERENCES evidence (tenant_id, id)
);
CALL protect_table('bad_debt_claim', 'SELECT, INSERT');
CREATE INDEX bad_debt_doc_idx ON bad_debt_claim (tenant_id, document_id);

CREATE TABLE apportionment_run (
  id                      uuid PRIMARY KEY,
  tenant_id               uuid NOT NULL,
  entity_id               uuid NOT NULL,
  period_id               uuid NOT NULL,
  kind                    text NOT NULL CHECK (kind IN ('PERIOD','ANNUAL')),
  method                  text NOT NULL,
  from_date               date NOT NULL,
  to_date                 date NOT NULL,
  direct_recoverable_aed  numeric(18,2) NOT NULL,
  direct_exempt_aed       numeric(18,2) NOT NULL,
  taxable_supplies_aed    numeric(18,2) NOT NULL,
  total_supplies_aed      numeric(18,2) NOT NULL,
  ratio_pct               numeric(7,4) NOT NULL,
  residual_input_aed      numeric(18,2) NOT NULL,
  target_recoverable_aed  numeric(18,2) NOT NULL,
  already_recovered_aed   numeric(18,2) NOT NULL,
  delta_aed               numeric(18,2) NOT NULL,
  further_adjustment      boolean NOT NULL DEFAULT false,
  adjustment_id           uuid,
  created_by              text NOT NULL,
  created_at              timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id)
);
CALL protect_table('apportionment_run', 'SELECT, INSERT');
CREATE INDEX apportionment_run_period_idx ON apportionment_run (tenant_id, period_id, created_at DESC);

-- What happened in a tax year before the entity was onboarded, for the annual adjustment.
CREATE TABLE apportionment_opening (
  id                      uuid PRIMARY KEY,
  tenant_id               uuid NOT NULL,
  entity_id               uuid NOT NULL,
  tax_year_start          date NOT NULL,
  direct_recoverable_aed  numeric(18,2) NOT NULL,
  direct_exempt_aed       numeric(18,2) NOT NULL,
  taxable_supplies_aed    numeric(18,2) NOT NULL,
  total_supplies_aed      numeric(18,2) NOT NULL,
  residual_input_aed      numeric(18,2) NOT NULL,
  recovered_aed           numeric(18,2) NOT NULL,
  batch_id                uuid NOT NULL,
  UNIQUE (tenant_id, entity_id, tax_year_start),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('apportionment_opening', 'SELECT, INSERT, UPDATE');

CREATE TABLE capital_asset (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  entity_id           uuid NOT NULL,
  reference           text NOT NULL,
  description         text NOT NULL,
  category            text NOT NULL CHECK (category IN ('REAL_ESTATE','OTHER')),
  cost_aed            numeric(18,2) NOT NULL,
  input_vat_aed       numeric(18,2) NOT NULL,
  initial_recovery_pct numeric(7,4) NOT NULL CHECK (initial_recovery_pct BETWEEN 0 AND 100),
  first_used_on       date NOT NULL,
  adjustment_years    integer NOT NULL,
  disposed_on         date,
  disposal_taxable    boolean,
  opening             boolean NOT NULL DEFAULT false,
  created_by          text NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, entity_id, reference),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('capital_asset', 'SELECT, INSERT, UPDATE');

CREATE TABLE capital_asset_interval (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  asset_id          uuid NOT NULL,
  interval_no       integer NOT NULL,
  interval_end      date NOT NULL,
  taxable_use_pct   numeric(7,4) NOT NULL CHECK (taxable_use_pct BETWEEN 0 AND 100),
  vat_adjustment    numeric(18,2) NOT NULL,
  period_id         uuid,
  adjustment_id     uuid,
  recorded_by       text NOT NULL,
  recorded_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, asset_id, interval_no),
  FOREIGN KEY (tenant_id, asset_id) REFERENCES capital_asset (tenant_id, id)
);
CALL protect_table('capital_asset_interval', 'SELECT, INSERT');

CREATE TABLE margin_sale (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  entity_id          uuid NOT NULL,
  period_id          uuid NOT NULL,
  document_id        uuid NOT NULL,
  doc_number         text NOT NULL,
  doc_date           date NOT NULL,
  category           text NOT NULL,
  description        text,
  purchase_price_aed numeric(18,2) NOT NULL,
  acquisition_costs_aed numeric(18,2) NOT NULL DEFAULT 0,
  selling_price_aed  numeric(18,2) NOT NULL,
  margin_aed         numeric(18,2) NOT NULL,
  vat_aed            numeric(18,2) NOT NULL,
  UNIQUE (tenant_id, document_id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, document_id) REFERENCES source_document (tenant_id, id)
);
CALL protect_table('margin_sale', 'SELECT, INSERT, UPDATE, DELETE');
CREATE INDEX margin_sale_period_idx ON margin_sale (tenant_id, period_id);

-- Bank evidence of payment, kept apart from what the ERP says, so a reload of AP cannot erase it.
ALTER TABLE source_document ADD COLUMN bank_paid_date date, ADD COLUMN bank_paid_amount_aed numeric(18,2);

-- ---- Zero-rating evidence (TXC-05) ----
CREATE TABLE export_evidence (
  id                      uuid PRIMARY KEY,
  tenant_id               uuid NOT NULL,
  entity_id               uuid NOT NULL,
  document_id             uuid NOT NULL,
  exported_on             date,
  exit_reference          text,
  official_evidence_id    uuid,
  commercial_evidence_id  uuid,
  extended_until          date,
  extension_evidence_id   uuid,
  recorded_by             text NOT NULL,
  recorded_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, document_id),
  FOREIGN KEY (tenant_id, document_id) REFERENCES source_document (tenant_id, id),
  FOREIGN KEY (tenant_id, official_evidence_id) REFERENCES evidence (tenant_id, id),
  FOREIGN KEY (tenant_id, commercial_evidence_id) REFERENCES evidence (tenant_id, id),
  FOREIGN KEY (tenant_id, extension_evidence_id) REFERENCES evidence (tenant_id, id)
);
CALL protect_table('export_evidence', 'SELECT, INSERT, UPDATE');

-- ---- Classification suggestions (TXC-06, NFR-15): logged with inputs and engine version ----
CREATE TABLE classification_suggestion (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  entity_id        uuid NOT NULL,
  period_id        uuid NOT NULL,
  line_id          uuid NOT NULL,
  logical_line_id  uuid NOT NULL,
  current_treatment text NOT NULL,
  treatment        text NOT NULL,
  confidence       numeric(4,3) NOT NULL,
  method           text NOT NULL,
  reason           text NOT NULL,
  inputs           jsonb NOT NULL,
  engine_version   text NOT NULL,
  status           text NOT NULL CHECK (status IN ('PROPOSED','ACCEPTED','REJECTED','STALE')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  decided_by       text,
  decided_at       timestamptz,
  UNIQUE (tenant_id, line_id, treatment),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id)
);
CALL protect_table('classification_suggestion', 'SELECT, INSERT, UPDATE');
CREATE INDEX classification_suggestion_period_idx ON classification_suggestion (tenant_id, period_id, status);

-- ---- Client approval e-signature (WF-04) ----
CREATE TABLE esignature (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  entity_id        uuid NOT NULL,
  period_id        uuid NOT NULL,
  version_id       uuid NOT NULL,
  values_hash      text NOT NULL,
  signer_user_id   uuid NOT NULL,
  typed_name       text NOT NULL,
  signer_email     text NOT NULL,
  signatory_id     uuid,
  consent_version  text NOT NULL,
  ip_address       text,
  user_agent       text,
  signature_hash   text NOT NULL,
  signed_at        timestamptz NOT NULL,
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, version_id) REFERENCES return_version (tenant_id, id)
);
CALL protect_table('esignature', 'SELECT, INSERT');
CREATE INDEX esignature_period_idx ON esignature (tenant_id, period_id, signed_at DESC);

-- ---- Practice tools (PRC-02, PRC-03, ANL-03) ----
CREATE TABLE staff_assignment (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  entity_id    uuid NOT NULL,
  user_id      uuid NOT NULL,
  duty         text NOT NULL CHECK (duty IN ('PREPARER','REVIEWER','PARTNER')),
  assigned_by  text NOT NULL,
  assigned_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, entity_id, user_id, duty),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id),
  FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id)
);
CALL protect_table('staff_assignment');
CREATE INDEX staff_assignment_user_idx ON staff_assignment (tenant_id, user_id);

CREATE TABLE webhook_endpoint (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL REFERENCES tenant(id),
  name              text NOT NULL,
  url               text NOT NULL,
  secret_encrypted  text NOT NULL,
  events            text[] NOT NULL,
  active            boolean NOT NULL DEFAULT true,
  audit_cursor      bigint NOT NULL DEFAULT 0,
  created_by        text NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id)
);
CALL protect_table('webhook_endpoint', 'SELECT, INSERT, UPDATE');

CREATE TABLE webhook_delivery (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  endpoint_id      uuid NOT NULL,
  audit_seq        bigint NOT NULL,
  event_type       text NOT NULL,
  payload          text NOT NULL,
  status           text NOT NULL CHECK (status IN ('PENDING','DELIVERED','FAILED')),
  attempts         integer NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  last_status      integer,
  last_error       text,
  delivered_at     timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, endpoint_id, audit_seq),
  FOREIGN KEY (tenant_id, endpoint_id) REFERENCES webhook_endpoint (tenant_id, id)
);
CALL protect_table('webhook_delivery', 'SELECT, INSERT, UPDATE');
CREATE INDEX webhook_delivery_due_idx ON webhook_delivery (tenant_id, status, next_attempt_at);

-- A client not yet registered for VAT. It has no TRN, so it is not a legal_entity in the filing sense.
CREATE TABLE registration_watch (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenant(id),
  name        text NOT NULL,
  notes       text,
  alerted_status text,
  created_by  text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id)
);
CALL protect_table('registration_watch');

CREATE TABLE registration_watch_month (
  tenant_id          uuid NOT NULL,
  watch_id           uuid NOT NULL,
  month_start        date NOT NULL CHECK (EXTRACT(DAY FROM month_start) = 1),
  taxable_supplies_aed numeric(18,2) NOT NULL,
  zero_rated_aed     numeric(18,2) NOT NULL DEFAULT 0,
  expected_next_30_days_aed numeric(18,2),
  PRIMARY KEY (tenant_id, watch_id, month_start),
  FOREIGN KEY (tenant_id, watch_id) REFERENCES registration_watch (tenant_id, id)
);
CALL protect_table('registration_watch_month');

-- ---- ERP connectors (ING-04) ----
CREATE TABLE connector (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  entity_id         uuid NOT NULL,
  system            text NOT NULL,
  base_url          text NOT NULL,
  auth_kind         text NOT NULL CHECK (auth_kind IN ('NONE','BEARER','BASIC','API_KEY')),
  secret_encrypted  text,
  kinds             text[] NOT NULL,
  enabled           boolean NOT NULL DEFAULT false,
  created_by        text NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, entity_id, system),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('connector');

CREATE TABLE connector_run (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  connector_id  uuid NOT NULL,
  kind          text NOT NULL,
  from_date     date NOT NULL,
  to_date       date NOT NULL,
  status        text NOT NULL CHECK (status IN ('OK','FAILED')),
  rows_fetched  integer NOT NULL DEFAULT 0,
  batch_id      uuid,
  error         text,
  started_at    timestamptz NOT NULL,
  finished_at   timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, connector_id) REFERENCES connector (tenant_id, id)
);
CALL protect_table('connector_run', 'SELECT, INSERT');
CREATE INDEX connector_run_idx ON connector_run (tenant_id, connector_id, finished_at DESC);

-- ---- Per-tenant keys (NFR-02, NFR-03): each data key is stored wrapped by the KMS ----
CREATE TABLE tenant_key (
  tenant_id    uuid NOT NULL REFERENCES tenant(id),
  key_version  integer NOT NULL,
  wrapped_key  text NOT NULL,
  kms_key_ref  text NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  retired_at   timestamptz,
  PRIMARY KEY (tenant_id, key_version)
);
CALL protect_table('tenant_key', 'SELECT, INSERT, UPDATE');

-- ---- Retention (NFR-06) ----
CREATE TABLE period_legal_hold (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  entity_id    uuid NOT NULL,
  period_id    uuid NOT NULL,
  reason       text NOT NULL,
  case_id      uuid,
  set_by       text NOT NULL,
  set_at       timestamptz NOT NULL DEFAULT now(),
  released_by  text,
  released_at  timestamptz,
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id)
);
CALL protect_table('period_legal_hold', 'SELECT, INSERT, UPDATE');
CREATE INDEX period_legal_hold_idx ON period_legal_hold (tenant_id, period_id) WHERE released_at IS NULL;

ALTER TABLE tax_period ADD COLUMN real_estate_records boolean NOT NULL DEFAULT false;

-- Tax records must outlast their retention dates whatever the application does. Only working tables
-- keep DELETE: the recompute queue, the entity grant list (access is revocable) and the margin
-- schedule, which is rebuilt from source rows.
REVOKE DELETE ON tax_period, source_document, ingest_batch, ingest_row, einvoice, gl_balance, recon_exception,
  recon_timing_item, signoff, adjustment, legal_entity, establishment, counterparty, tax_agency, tax_agent,
  entity_tax_agent, authorised_signatory, fx_rate, doc_match, rule_pack, notification, gl_journal_line
  FROM vat_app;
