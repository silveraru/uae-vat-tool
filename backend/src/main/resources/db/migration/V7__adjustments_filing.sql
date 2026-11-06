-- Evidence vault, adjustments, corrections, return packs, filing and payment
-- (AUD-02, ADJ-01, ADJ-05, COR-01, FIL-01 to FIL-03).

CREATE TABLE evidence (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  entity_id        uuid NOT NULL,
  period_id        uuid,
  file_name        text NOT NULL,
  content_type     text NOT NULL,
  sha256           text NOT NULL,
  size_bytes       bigint NOT NULL,
  storage_key      text NOT NULL,
  version_id       text,
  retention_class  text NOT NULL CHECK (retention_class IN ('STANDARD','REAL_ESTATE')),
  retain_until     date NOT NULL,
  legal_hold       boolean NOT NULL DEFAULT false,
  description      text,
  uploaded_by      text NOT NULL,
  uploaded_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, entity_id) REFERENCES legal_entity (tenant_id, id)
);
CALL protect_table('evidence', 'SELECT, INSERT');
GRANT UPDATE (legal_hold) ON evidence TO vat_app;
CREATE INDEX evidence_period_idx ON evidence (tenant_id, entity_id, period_id);

CREATE TABLE evidence_link (
  tenant_id    uuid NOT NULL,
  evidence_id  uuid NOT NULL,
  object_type  text NOT NULL CHECK (object_type IN ('PERIOD','BOX','LINE','EXCEPTION','ADJUSTMENT','CORRECTION','FILING','PAYMENT')),
  object_id    text NOT NULL,
  created_by   text NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, evidence_id, object_type, object_id),
  FOREIGN KEY (tenant_id, evidence_id) REFERENCES evidence (tenant_id, id)
);
CALL protect_table('evidence_link', 'SELECT, INSERT');
CREATE INDEX evidence_link_object_idx ON evidence_link (tenant_id, object_type, object_id);

CREATE TABLE adjustment (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  entity_id    uuid NOT NULL,
  period_id    uuid NOT NULL,
  type         text NOT NULL CHECK (type IN ('SUPPLIER_REVERSAL','SUPPLIER_REINSTATEMENT','MANUAL_JOURNAL','DEEMED_SUPPLY','PRIOR_PERIOD_CORRECTION')),
  box_code     text NOT NULL,
  emirate      text,
  net_aed      numeric(18,2) NOT NULL DEFAULT 0,
  vat_aed      numeric(18,2) NOT NULL,
  description  text NOT NULL,
  reason       text,
  document_id  uuid,
  evidence_id  uuid,
  status       text NOT NULL CHECK (status IN ('PROPOSED','APPROVED','REJECTED')),
  created_by   text NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  decided_by   text,
  decided_at   timestamptz,
  decision_note text,
  line_id      uuid,
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, evidence_id) REFERENCES evidence (tenant_id, id),
  -- ADJ-05: a manual journal or deemed supply cannot exist without its evidence.
  CHECK (type NOT IN ('MANUAL_JOURNAL','DEEMED_SUPPLY') OR evidence_id IS NOT NULL)
);
CALL protect_table('adjustment');
CREATE INDEX adjustment_period_idx ON adjustment (tenant_id, period_id);

-- Running position of the 6-month rule per supplier invoice: reversals are positive and
-- reinstatements negative, so the sum is what is currently reversed.
CREATE TABLE supplier_reversal (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  entity_id      uuid NOT NULL,
  document_id    uuid NOT NULL,
  period_id      uuid NOT NULL,
  adjustment_id  uuid NOT NULL,
  vat_aed        numeric(18,2) NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, document_id) REFERENCES source_document (tenant_id, id),
  FOREIGN KEY (tenant_id, adjustment_id) REFERENCES adjustment (tenant_id, id)
);
CALL protect_table('supplier_reversal', 'SELECT, INSERT');
CREATE INDEX supplier_reversal_doc_idx ON supplier_reversal (tenant_id, document_id);

CREATE TABLE correction_case (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid NOT NULL,
  entity_id             uuid NOT NULL,
  period_id             uuid NOT NULL,
  original_version_id   uuid NOT NULL,
  corrected_version_id  uuid NOT NULL,
  tax_difference_aed    numeric(18,2) NOT NULL,
  threshold_aed         numeric(18,2) NOT NULL,
  route                 text NOT NULL CHECK (route IN ('NEXT_RETURN','FORM_211')),
  target_period_id      uuid,
  reason                text NOT NULL,
  evidence_id           uuid,
  handled_through       timestamptz NOT NULL,
  created_by            text NOT NULL,
  created_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, original_version_id) REFERENCES return_version (tenant_id, id),
  FOREIGN KEY (tenant_id, corrected_version_id) REFERENCES return_version (tenant_id, id)
);
CALL protect_table('correction_case', 'SELECT, INSERT');
CREATE INDEX correction_case_period_idx ON correction_case (tenant_id, period_id, created_at DESC);

CREATE TABLE return_pack (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  entity_id     uuid NOT NULL,
  period_id     uuid NOT NULL,
  version_id    uuid NOT NULL,
  draft_rules   boolean NOT NULL,
  pdf_key       text NOT NULL,
  xlsx_key      text NOT NULL,
  json_key      text NOT NULL,
  pack_sha256   text NOT NULL,
  audit_head    text NOT NULL,
  created_by    text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, version_id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, version_id) REFERENCES return_version (tenant_id, id)
);
CALL protect_table('return_pack', 'SELECT, INSERT');

CREATE TABLE filing (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  entity_id        uuid NOT NULL,
  period_id        uuid NOT NULL,
  version_id       uuid NOT NULL,
  submission_ref   text NOT NULL,
  submitted_on     date NOT NULL,
  ack_evidence_id  uuid NOT NULL,
  recorded_by      text NOT NULL,
  recorded_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, period_id),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, ack_evidence_id) REFERENCES evidence (tenant_id, id)
);
CALL protect_table('filing', 'SELECT, INSERT');

CREATE TABLE payment (
  id                         uuid PRIMARY KEY,
  tenant_id                  uuid NOT NULL,
  entity_id                  uuid NOT NULL,
  period_id                  uuid NOT NULL,
  amount_due_aed             numeric(18,2) NOT NULL,
  amount_paid_aed            numeric(18,2) NOT NULL,
  giban_reference            text NOT NULL,
  paid_on                    date NOT NULL,
  bank_reference             text,
  bank_amount_aed            numeric(18,2),
  confirmation_evidence_id   uuid,
  match_status               text NOT NULL CHECK (match_status IN ('MATCHED','MISMATCHED','UNCONFIRMED')),
  match_detail               text,
  recorded_by                text NOT NULL,
  recorded_at                timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, confirmation_evidence_id) REFERENCES evidence (tenant_id, id)
);
CALL protect_table('payment', 'SELECT, INSERT');
CREATE INDEX payment_period_idx ON payment (tenant_id, period_id, recorded_at DESC);
