-- Reconciliation (REC-01 to REC-04, spec section 5) and comment threads (WF-02).

CREATE TABLE doc_match (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  filer_entity_id  uuid NOT NULL,
  document_id      uuid NOT NULL,
  invoice_uuid     uuid NOT NULL,
  method           text NOT NULL CHECK (method IN ('UUID','KEY','FUZZY')),
  score            numeric(4,3) NOT NULL,
  status           text NOT NULL CHECK (status IN ('CONFIRMED','PROPOSED','REJECTED')),
  decided_by       text,
  decided_at       timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, document_id, invoice_uuid),
  FOREIGN KEY (tenant_id, document_id) REFERENCES source_document (tenant_id, id)
);
CALL protect_table('doc_match');
CREATE INDEX doc_match_doc_idx ON doc_match (tenant_id, document_id, status);
CREATE INDEX doc_match_invoice_idx ON doc_match (tenant_id, invoice_uuid, status);
-- A document and an e-invoice can each hold only one confirmed partner.
CREATE UNIQUE INDEX doc_match_one_confirmed_doc ON doc_match (tenant_id, document_id) WHERE status = 'CONFIRMED';
CREATE UNIQUE INDEX doc_match_one_confirmed_inv ON doc_match (tenant_id, invoice_uuid) WHERE status = 'CONFIRMED';

CREATE TABLE recon_run (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  entity_id          uuid NOT NULL,
  period_id          uuid NOT NULL,
  trigger            text NOT NULL,
  return_version_id  uuid,
  rule_pack_version  text NOT NULL,
  summary            jsonb NOT NULL,
  started_at         timestamptz NOT NULL,
  finished_at        timestamptz NOT NULL,
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id)
);
CALL protect_table('recon_run', 'SELECT, INSERT');
CREATE INDEX recon_run_period_idx ON recon_run (tenant_id, period_id, finished_at DESC);

-- The fingerprint identifies "the same problem" across runs, so re-runs update it instead of
-- creating a duplicate. data_hash detects when the underlying numbers changed, which reopens a waiver.
CREATE TABLE recon_exception (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  entity_id          uuid NOT NULL,
  period_id          uuid NOT NULL,
  rule_id            text NOT NULL,
  fingerprint        text NOT NULL,
  severity           text NOT NULL CHECK (severity IN ('BLOCKING','WARNING')),
  status             text NOT NULL CHECK (status IN ('OPEN','ASSIGNED','RESOLVED','WAIVED')),
  message            text NOT NULL,
  details            jsonb NOT NULL DEFAULT '{}',
  amount_aed         numeric(18,2),
  data_hash          text NOT NULL,
  owner_id           uuid,
  due_date           date,
  resolution_code    text,
  resolution_note    text,
  resolved_by        text,
  resolved_at        timestamptz,
  waiver_reason      text,
  waiver_approver    uuid,
  waived_at          timestamptz,
  locked_at          timestamptz,
  carried_from_id    uuid,
  first_opened_at    timestamptz NOT NULL DEFAULT now(),
  last_seen_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, period_id, rule_id, fingerprint),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  -- A waiver without its reason and approver is not a waiver.
  CHECK (status <> 'WAIVED' OR (waiver_reason IS NOT NULL AND waiver_approver IS NOT NULL))
);
CALL protect_table('recon_exception');
CREATE INDEX recon_exception_open_idx ON recon_exception (tenant_id, period_id, severity, status);
CREATE INDEX recon_exception_owner_idx ON recon_exception (tenant_id, owner_id) WHERE status IN ('OPEN','ASSIGNED');

-- Timing differences a person has listed against a GL control account (check R06).
CREATE TABLE recon_timing_item (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  period_id     uuid NOT NULL,
  account_type  text NOT NULL CHECK (account_type IN ('OUTPUT','INPUT','REVERSE_CHARGE','IMPORT')),
  amount_aed    numeric(18,2) NOT NULL,
  description   text NOT NULL,
  created_by    text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id)
);
CALL protect_table('recon_timing_item');

CREATE TABLE comment (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  entity_id    uuid NOT NULL,
  period_id    uuid NOT NULL,
  object_type  text NOT NULL CHECK (object_type IN ('PERIOD','BOX','LINE','EXCEPTION')),
  object_id    text NOT NULL,
  parent_id    uuid,
  author_id    uuid NOT NULL,
  body         text NOT NULL CHECK (length(trim(body)) > 0),
  created_at   timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id)
);
CALL protect_table('comment', 'SELECT, INSERT');
CREATE INDEX comment_object_idx ON comment (tenant_id, period_id, object_type, object_id, created_at);
