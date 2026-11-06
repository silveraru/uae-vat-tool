-- Maker-checker, sign-offs and deadline alerts (WF-01, WF-03).

-- A sign-off counts only for the exact figures and data it was given on. Invalidating one keeps
-- the row, so the history of who signed what stays visible.
CREATE TABLE signoff (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  entity_id          uuid NOT NULL,
  period_id          uuid NOT NULL,
  step               text NOT NULL CHECK (step IN ('PREPARED','REVIEWED','PARTNER','APPROVED')),
  version_id         uuid NOT NULL,
  values_hash        text NOT NULL,
  data_fingerprint   text NOT NULL,
  user_id            uuid NOT NULL,
  note               text,
  signed_at          timestamptz NOT NULL DEFAULT now(),
  invalidated_at     timestamptz,
  invalidated_reason text,
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id),
  FOREIGN KEY (tenant_id, version_id) REFERENCES return_version (tenant_id, id)
);
CALL protect_table('signoff');
CREATE INDEX signoff_period_idx ON signoff (tenant_id, period_id, signed_at);
CREATE UNIQUE INDEX signoff_one_live_per_step ON signoff (tenant_id, period_id, step) WHERE invalidated_at IS NULL;

CREATE TABLE notification (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  user_id     uuid NOT NULL,
  entity_id   uuid,
  period_id   uuid,
  kind        text NOT NULL,
  title       text NOT NULL,
  body        text NOT NULL,
  read_at     timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CALL protect_table('notification');
CREATE INDEX notification_user_idx ON notification (tenant_id, user_id, created_at DESC);

-- One row per period and tier, so a re-run of the daily job cannot send the same alert twice.
CREATE TABLE deadline_alert (
  tenant_id  uuid NOT NULL,
  period_id  uuid NOT NULL,
  tier       integer NOT NULL,
  sent_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, period_id, tier),
  FOREIGN KEY (tenant_id, period_id) REFERENCES tax_period (tenant_id, id)
);
CALL protect_table('deadline_alert', 'SELECT, INSERT');
